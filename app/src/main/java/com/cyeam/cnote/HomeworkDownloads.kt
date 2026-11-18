package com.cyeam.cnote

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException

/** Saves into the public Downloads collection; no per-file document picker. */
object HomeworkDownloads {
    fun save(context:Context,name:String,mime:String,bytes:ByteArray):String {
        require(name.isNotBlank()&&name!="."&&name!=".."&&name.none { it.isISOControl()||it in "/\\:" }) { "下载文件名无效" }
        require(mime.startsWith("image/")||mime.startsWith("audio/")) { "此附件不是图片或音频" }
        require(bytes.isNotEmpty()&&bytes.size<=25*1024*1024) { "附件为空或超过 25 MB" }
        if(Build.VERSION.SDK_INT>=29){
            val resolver=context.contentResolver
            val values=ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME,name);put(MediaStore.Downloads.MIME_TYPE,mime)
                put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/CNote/")
                put(MediaStore.Downloads.IS_PENDING,1)
            }
            val uri=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)?:throw IOException("无法创建下载文件")
            try{
                (resolver.openOutputStream(uri)?:throw IOException("无法写入下载目录")).use { it.write(bytes) }
                check(resolver.update(uri,ContentValues().apply { put(MediaStore.Downloads.IS_PENDING,0) },null,null)>0) { "下载文件未能完成保存" }
                return uri.toString()
            }catch(error:Exception){resolver.delete(uri,null,null);throw error}
        }
        @Suppress("DEPRECATION") val directory=File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),"CNote")
        check(directory.isDirectory||directory.mkdirs()) { "无法创建下载目录" }
        val base=File(name);val stem=base.nameWithoutExtension;val extension=base.extension
        for(index in 0..9999){
            val suffix=if(index==0)name else if(extension.isEmpty())"$stem ($index)" else "$stem ($index).$extension"
            val file=File(directory,suffix)
            if(!file.createNewFile())continue
            try{file.outputStream().use { it.write(bytes);it.fd.sync() };return file.absolutePath}
            catch(error:Exception){file.delete();throw error}
        }
        throw IOException("下载目录同名文件过多")
    }
}
