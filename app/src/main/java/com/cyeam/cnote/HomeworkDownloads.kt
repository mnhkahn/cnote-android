package com.cyeam.cnote

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Saves into the public Downloads collection; no per-file document picker. */
object HomeworkDownloads {
    @Synchronized fun save(context:Context,name:String,mime:String,bytes:ByteArray):String {
        require(name.isNotBlank()&&name!="."&&name!=".."&&name.none { it.isISOControl()||it in "/\\:" }) { "下载文件名无效" }
        require(mime.startsWith("image/")||mime.startsWith("audio/")) { "此附件不是图片或音频" }
        require(bytes.isNotEmpty()&&bytes.size<=25*1024*1024) { "附件为空或超过 25 MB" }
        if(Build.VERSION.SDK_INT>=29){
            val resolver=context.contentResolver
            val relativePath=Environment.DIRECTORY_DOWNLOADS+"/CNote/"
            val existing=resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ? AND ${MediaStore.Downloads.OWNER_PACKAGE_NAME} = ?",
                arrayOf(name,relativePath,context.packageName),"${MediaStore.Downloads._ID} ASC"
            )?.use { cursor->if(cursor.moveToFirst())ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI,cursor.getLong(0)) else null }
            val values=ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME,name);put(MediaStore.Downloads.MIME_TYPE,mime)
                put(MediaStore.Downloads.RELATIVE_PATH,relativePath)
                put(MediaStore.Downloads.IS_PENDING,1)
            }
            val uri=existing?:resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)?:throw IOException("无法创建下载文件")
            // Retain the previous bytes until replacement succeeds, so a failed
            // write can restore the existing public download.
            val backup=if(existing!=null)File.createTempFile("homework-backup-",".tmp",context.cacheDir).also { file->
                try {
                    (resolver.openInputStream(existing)?:throw IOException("无法读取已有下载文件")).use { input->file.outputStream().use { input.copyTo(it) } }
                }catch(error:Exception){file.delete();throw error}
            } else null
            try{
                (resolver.openOutputStream(uri,"rwt")?:throw IOException("无法写入下载目录")).use { it.write(bytes) }
                check(resolver.update(uri,ContentValues().apply { put(MediaStore.Downloads.IS_PENDING,0);put(MediaStore.Downloads.MIME_TYPE,mime) },null,null)>0) { "下载文件未能完成保存" }
                return uri.toString()
            }catch(error:Exception){
                try {
                    if(backup!=null)(resolver.openOutputStream(uri,"rwt")?:throw IOException("无法恢复已有下载")).use { output->backup.inputStream().use { it.copyTo(output) } }
                    else resolver.delete(uri,null,null)
                }catch(restoreError:Exception){error.addSuppressed(restoreError)}
                throw error
            }finally{backup?.delete()}
        }
        @Suppress("DEPRECATION") val directory=File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),"CNote")
        check(directory.isDirectory||directory.mkdirs()) { "无法创建下载目录" }
        return replaceFile(directory,name,bytes).absolutePath
    }
    internal fun replaceFile(directory:File,name:String,bytes:ByteArray):File {
        val file=File(directory,name)
        val staging=File.createTempFile(".cnote-download-",".tmp",directory)
        try {
            staging.outputStream().use { it.write(bytes);it.fd.sync() }
            Files.move(staging.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
            return file
        } finally { staging.delete() }
    }
}
