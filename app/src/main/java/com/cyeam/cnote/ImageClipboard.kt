package com.cyeam.cnote

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Images leave the sandbox only through a read-granted content URI, never a large Binder payload. */
class ImageFileProvider:FileProvider()

object ImageClipboard {
    private val client=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).callTimeout(25,TimeUnit.SECONDS).followSslRedirects(false).build()
    suspend fun copy(context:Context,source:String) {
        val uri=prepare(context,source)
        withContext(Dispatchers.Main) {
            val clipboard=context.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver,"CNote 图片",uri))
        }
    }
    fun prepare(context:Context,source:String):Uri {
        val bytes=if(source.startsWith("data:image/")) {
            val comma=source.indexOf(',');require(comma>0&&source.substring(0,comma).endsWith(";base64")) { "图片数据格式错误" }
            val encoded=source.substring(comma+1);require(encoded.length<=Rules.MAX_HTML*4/3+4) { "图片超过 20 MB" }
            Base64.decode(encoded,Base64.DEFAULT)
        } else {
            val uri=Uri.parse(source);require(uri.scheme=="https") { "图片地址必须使用 HTTPS" }
            client.newCall(Request.Builder().url(source).build()).execute().use { response->
                check(response.isSuccessful) { "图片下载失败（${response.code}）" }
                val body=response.body?:error("图片内容为空")
                body.byteStream().use { it.readBytesBounded(Rules.MAX_HTML) }
            }
        }
        require(bytes.isNotEmpty()&&bytes.size<=Rules.MAX_HTML) { "图片为空或超过 20 MB" }
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        val extension=when(bounds.outMimeType) { "image/png"->"png";"image/jpeg"->"jpg";"image/webp"->"webp";"image/gif"->"gif";"image/bmp"->"bmp";else->error("图片格式无效或不支持") }
        require(bounds.outWidth>0&&bounds.outHeight>0) { "图片无法读取" }
        val directory=File(context.cacheDir,"clipboard_images").apply { mkdirs() }
        val file=File(directory,"${UUID.randomUUID()}.$extension");file.writeBytes(bytes)
        return FileProvider.getUriForFile(context,"${context.packageName}.images",file)
    }
}
