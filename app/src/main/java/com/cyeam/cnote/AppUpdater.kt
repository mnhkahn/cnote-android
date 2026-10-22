package com.cyeam.cnote

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.security.MessageDigest

enum class UpdateStage { IDLE,CHECKING,DOWNLOADING,READY,FAILED }
data class UpdateState(val stage:UpdateStage=UpdateStage.IDLE,val version:String="",val notes:String="",val progress:Int=0,val message:String="",val id:Long = -1)

/** One application-owned monitor. All network, package parsing and file work stay off the UI thread. */
class AppUpdater(private val context:Context,private val scope:CoroutineScope,private val checkLatest:()->AppUpdate?={AppUpdateClient().check()}) {
    private val downloads=context.getSystemService(DownloadManager::class.java)
    private val prefs=context.getSharedPreferences("app_updates",Context.MODE_PRIVATE)
    private val lock=Mutex()
    private val mutable=MutableStateFlow(UpdateState())
    val state=mutable.asStateFlow()
    private var lastCheck=0L
    private val apk get()=File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),"updates/cnote-update.apk")
    fun checkForUpdate(manual:Boolean=false) {
        scope.launch {
            if(!lock.tryLock())return@launch
            try {
                val now=System.currentTimeMillis()
                if(!manual&&now-lastCheck<6*60*60*1000&&prefs.getLong("id",-1)<0)return@launch
                lastCheck=now
                var id=prefs.getLong("id",-1)
                if(id>=0&&prefs.getLong("versionCode",0)<=BuildConfig.VERSION_CODE){clear();id = -1}
                if(id<0||mutable.value.stage==UpdateStage.FAILED) {
                    clear();mutable.value=UpdateState(UpdateStage.CHECKING,message="正在检查更新…")
                    val update=checkLatest()
                    if(update==null){mutable.value=UpdateState(message="已是最新版本");return@launch}
                    val directory=checkNotNull(apk.parentFile);check(directory.isDirectory||directory.mkdirs()) { "无法创建下载目录" }
                    val request=DownloadManager.Request(android.net.Uri.parse(update.downloadUrl))
                        .setTitle("CNote ${update.versionName}").setDescription("正在后台下载更新，完成后可安装")
                        .setMimeType("application/vnd.android.package-archive").setAllowedOverMetered(false).setAllowedOverRoaming(false)
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                        .setDestinationInExternalFilesDir(context,Environment.DIRECTORY_DOWNLOADS,"updates/cnote-update.apk")
                    id=downloads.enqueue(request)
                    // Commit before monitoring so a process restart can recover the same system download.
                    val stored=prefs.edit().putLong("id",id).putLong("versionCode",update.versionCode).putLong("size",update.sizeBytes)
                        .putString("versionName",update.versionName).putString("notes",update.notes).putString("sha256",update.sha256).commit()
                    if(!stored){downloads.remove(id);error("更新记录保存失败")}
                }
                val current=UpdateState(UpdateStage.DOWNLOADING,prefs.getString("versionName","").orEmpty(),prefs.getString("notes","").orEmpty(),id=id)
                while(currentCoroutineContext().isActive) {
                    val result=downloads.query(DownloadManager.Query().setFilterById(id)).use { cursor->
                        check(cursor.moveToFirst()) { "下载任务已移除，请重试" }
                        Triple(cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)))
                    }
                    when(result.first) {
                        DownloadManager.STATUS_SUCCESSFUL->{verifyApk();mutable.value=current.copy(stage=UpdateStage.READY,progress=100,message="下载完成，可安装更新");return@launch}
                        DownloadManager.STATUS_FAILED->{clear();throw AppUpdateCheckException("下载失败或链接已过期，请重试获取新地址")}
                        else->mutable.value=current.copy(progress=(result.second*100/prefs.getLong("size",1).coerceAtLeast(1)).toInt().coerceIn(0,100),message=if(result.first==DownloadManager.STATUS_PAUSED)"等待 Wi-Fi 或网络恢复" else "后台下载中（仅使用非计费网络）")
                    }
                    delay(1000)
                }
            } catch(e:CancellationException){throw e}
            catch(e:Exception){mutable.value=mutable.value.copy(stage=UpdateStage.FAILED,message=if(e is AppUpdateCheckException)e.message.orEmpty() else "更新失败，请检查网络后重试")}
            finally { lock.unlock() }
        }
    }
    internal fun verifyApk()=verifyUpdateApk(context,apk,prefs.getLong("size",-1),prefs.getLong("versionCode",-1),prefs.getString("sha256",null))
    suspend fun installIntent():Intent=withContext(Dispatchers.IO) {
        check(state.value.stage==UpdateStage.READY);verifyApk()
        val uri=FileProvider.getUriForFile(context,"${context.packageName}.updates",apk)
        Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    private fun clear() {
        val id=prefs.getLong("id",-1);if(id>=0)downloads.remove(id)
        if(apk.exists())check(apk.delete()) { "无法清理旧安装包" };check(prefs.edit().clear().commit()) { "无法清理更新记录" }
    }
}

/** PackageManager parses real APK signing certificates on every supported Android version. */
@Suppress("DEPRECATION")
internal fun verifyUpdateApk(context:Context,apk:File,size:Long,version:Long,sha256:String?,installedVersion:Long=BuildConfig.VERSION_CODE.toLong()) {
    check(apk.isFile&&apk.length()==size) { "安装包不完整" }
    sha256?.let { expected->
        val digest=MessageDigest.getInstance("SHA-256");apk.inputStream().use { input->val bytes=ByteArray(65536);while(true){val n=input.read(bytes);if(n<0)break;digest.update(bytes,0,n)} }
        check(digest.digest().joinToString(""){"%02x".format(it)}==expected) { "安装包摘要不匹配" }
    }
    val flags=if(Build.VERSION.SDK_INT>=28)PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    val pm=context.packageManager;val incoming=checkNotNull(pm.getPackageArchiveInfo(apk.absolutePath,flags)) { "安装包无法读取" };val installed=pm.getPackageInfo(context.packageName,flags)
    val code=if(Build.VERSION.SDK_INT>=28)incoming.longVersionCode else incoming.versionCode.toLong()
    check(incoming.packageName==context.packageName&&code==version&&code>installedVersion) { "安装包版本不匹配" }
    val next=if(Build.VERSION.SDK_INT>=28)incoming.signingInfo?.apkContentsSigners else incoming.signatures
    val current=if(Build.VERSION.SDK_INT>=28)installed.signingInfo?.apkContentsSigners else installed.signatures
    check(!next.isNullOrEmpty()&&!current.isNullOrEmpty()&&next.toSet()==current.toSet()) { "安装包签名不匹配" }
}
