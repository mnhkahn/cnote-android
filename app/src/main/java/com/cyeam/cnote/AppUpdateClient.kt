package com.cyeam.cnote

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URL
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Pgyer credentials stay in CI/the gateway, never in the APK. */
data class AppUpdate(val versionName:String,val versionCode:Long,val notes:String,val downloadUrl:String,val sizeBytes:Long,val sha256:String?=null)
class AppUpdateCheckException(message:String):Exception(message)

class AppUpdateClient {
    private val http=OkHttpClient.Builder().connectTimeout(8,TimeUnit.SECONDS).readTimeout(12,TimeUnit.SECONDS).callTimeout(20,TimeUnit.SECONDS).build()
    fun check():AppUpdate? {
        val endpoint=URL(BuildConfig.APP_UPDATE_URL+"?versionCode="+BuildConfig.VERSION_CODE)
        try {
            http.newCall(Request.Builder().url(endpoint).build()).execute().use { response->
                if(response.isSuccessful)return parseGateway(read(response.body!!.byteStream()),endpoint,BuildConfig.VERSION_CODE.toLong(),BuildConfig.APPLICATION_ID)
                if(response.code !in listOf(404,502,503))throw AppUpdateCheckException("检查更新失败（HTTP ${response.code}）")
            }
        } catch(e:IllegalArgumentException) { throw AppUpdateCheckException("更新信息校验失败，请稍后重试") }
        catch(e:AppUpdateCheckException) { throw e }
        catch(_:java.io.IOException) { /* The public release mirror can outlive a gateway outage. */ }
        http.newCall(Request.Builder().url(BuildConfig.APP_UPDATE_MIRROR).build()).execute().use { response->
            if(!response.isSuccessful)throw AppUpdateCheckException("更新服务暂不可用，请稍后重试")
            return parseMirror(read(response.body!!.byteStream()),BuildConfig.VERSION_CODE.toLong(),BuildConfig.APPLICATION_ID)
        }
    }
    private fun read(input:java.io.InputStream)=input.use {
        val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192);while(true){val n=it.read(buffer);if(n<0)break;require(output.size()+n<=256*1024) { "更新信息过大" };output.write(buffer,0,n)};output.toString("UTF-8")
    }
    companion object {
        const val REPOSITORY="mnhkahn/cnote-android"
        private fun common(json:JSONObject,installed:Long,packageName:String):Long {
            require(json.getString("appId")=="cnote"&&json.getString("packageName")==packageName) { "更新应用不匹配" }
            val version=json.getLong("versionCode");require(version>installed&&version<=Int.MAX_VALUE) { "更新版本无效" }
            require(json.getLong("sizeBytes") in 1..200L*1024*1024) { "安装包大小无效" }
            require(json.getString("versionName").length in 1..80) { "版本名称无效" };return version
        }
        internal fun parseGateway(body:String,endpoint:URL,installed:Long,packageName:String):AppUpdate? {
            val json=JSONObject(body);if(!json.getBoolean("hasUpdate"))return null
            val version=common(json,installed,packageName);val url=URL(endpoint,json.getString("downloadUrl"))
            require(url.protocol=="https"&&url.host==endpoint.host&&url.port==endpoint.port&&url.userInfo==null&&url.ref==null&&url.path=="/api/apps/cnote/download") { "更新地址不可信" }
            require(Instant.parse(json.getString("expiresAt")).isAfter(Instant.now())) { "下载地址已过期" }
            return AppUpdate(json.getString("versionName"),version,json.optString("releaseNotes"),url.toString(),json.getLong("sizeBytes"))
        }
        internal fun parseMirror(body:String,installed:Long,packageName:String):AppUpdate? {
            val json=JSONObject(body)
            require(json.getString("appId")=="cnote"&&json.getString("packageName")==packageName) { "更新应用不匹配" }
            if(json.getLong("versionCode")<=installed)return null
            val version=common(json,installed,packageName);val url=URL(json.getString("downloadUrl"));val tag=json.getString("tag")
            require(tag.matches(Regex("v?\\d+\\.\\d+\\.\\d+"))) { "版本标签无效" }
            require(url.protocol=="https"&&url.host=="github.com"&&url.port==-1&&url.userInfo==null&&url.ref==null&&url.query==null&&url.path=="/$REPOSITORY/releases/download/$tag/CNote-release.apk") { "更新地址不可信" }
            val hash=json.getString("sha256");require(hash.matches(Regex("[a-f0-9]{64}"))) { "安装包摘要无效" }
            return AppUpdate(json.getString("versionName"),version,json.optString("releaseNotes"),url.toString(),json.getLong("sizeBytes"),hash)
        }
    }
}
