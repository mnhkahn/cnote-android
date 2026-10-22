package com.cyeam.cnote

import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class RemoteItem(val id:String,val name:String,val etag:String,val modified:Long=System.currentTimeMillis())
data class CloudContent(val html:String,val etag:String)
sealed interface UploadResult {
    data class Uploaded(val item:RemoteItem):UploadResult
    data class Conflict(val content:CloudContent,val remoteId:String):UploadResult
}
class GraphFailure(val status:Int,val code:String):Exception("OneDrive 请求失败（$status，$code）")

class GraphClient(private val vault:TokenVault,val generation:AtomicLong=AtomicLong()) {
    companion object {
        const val CLIENT_ID="e1f582c1-1568-4347-8c5b-1906164e637f"
        const val SCOPES="Files.ReadWrite User.Read offline_access"
        const val AUTH="https://login.microsoftonline.com/consumers/oauth2/v2.0"
        const val GRAPH="https://graph.microsoft.com/v1.0"
    }
    private val http=OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS).readTimeout(25,TimeUnit.SECONDS).callTimeout(30,TimeUnit.SECONDS).build()
    private val authLock=Any()
    private val refreshLock=Any()
    @Volatile private var tokens:Tokens?=null
    fun valid(epoch:Long)=epoch==generation.get()
    fun check(epoch:Long) { if(!valid(epoch))throw kotlinx.coroutines.CancellationException("账号已切换或登录已取消") }
    fun hasStoredSession()=synchronized(authLock) { tokens!=null||vault.read()!=null }
    fun load()=synchronized(authLock) { tokens=vault.read() }
    fun logout()=synchronized(authLock) { generation.incrementAndGet();tokens=null;vault.clear() }
    fun cancelLogin() { generation.incrementAndGet() }
    private fun checked(response:Response):Response {
        if(!response.isSuccessful) {
            val status=response.code
            val code=runCatching { JSONObject(response.body?.string().orEmpty()).let { it.optJSONObject("error")?.optString("code")?:it.optString("error","request_failed") } }.getOrDefault("request_failed").take(100).replace(Regex("[^A-Za-z0-9_.-]"),"")
            response.close();throw GraphFailure(status,code)
        }
        return response
    }
    private fun store(json:JSONObject,epoch:Long)=synchronized(authLock) {
        check(epoch)
        val next=Tokens(json.getString("access_token"),json.optString("refresh_token").ifEmpty { tokens?.refresh.orEmpty() },System.currentTimeMillis()+json.getLong("expires_in")*1000)
        vault.write(next);tokens=next
    }
    private fun access(epoch:Long):String=synchronized(refreshLock) {
        val current=synchronized(authLock) { check(epoch);tokens?:error("请先登录 OneDrive") }
        if(System.currentTimeMillis()+90_000>=current.expires) {
            val body=FormBody.Builder().add("client_id",CLIENT_ID).add("grant_type","refresh_token").add("refresh_token",current.refresh).add("scope",SCOPES).build()
            val json=checked(http.newCall(Request.Builder().url("$AUTH/token").post(body).build()).execute()).use { JSONObject(it.body!!.string()) }
            store(json,epoch)
        }
        synchronized(authLock) { check(epoch);tokens!!.access }
    }
    private fun request(method:String,path:String,epoch:Long,body:String?=null,etag:String?=null):Response {
        check(epoch)
        val builder=Request.Builder().url("$GRAPH$path").header("Authorization","Bearer ${access(epoch)}").header("User-Agent","CNote-Android/${BuildConfig.VERSION_NAME}")
        if(etag!=null) { require(etag.isNotEmpty()) { "缺少云端版本，请先刷新" };builder.header("If-Match",etag) }
        builder.method(method,body?.toRequestBody((if(method=="PUT")"text/html; charset=utf-8" else "application/json; charset=utf-8").toMediaType()))
        return http.newCall(builder.build()).execute().also { if(!valid(epoch)){it.close();check(epoch)} }
    }
    fun account(epoch:Long):Session=checked(request("GET","/me?\$select=id,displayName",epoch)).use {
        val json=JSONObject(it.body!!.string());Session(json.getString("id"),json.optString("displayName","OneDrive"),true)
    }
    suspend fun login(epoch:Long,onCode:(String,String)->Unit):Session {
        val form=FormBody.Builder().add("client_id",CLIENT_ID).add("scope",SCOPES).build()
        val device=checked(http.newCall(Request.Builder().url("$AUTH/devicecode").post(form).build()).execute()).use { JSONObject(it.body!!.string()) }
        check(epoch);onCode(device.getString("user_code"),device.getString("verification_uri"))
        val until=System.currentTimeMillis()+minOf(device.getLong("expires_in"),1200)*1000;var interval=maxOf(1,device.optInt("interval",5))
        while(System.currentTimeMillis()<until) {
            delay(interval*1000L);check(epoch);currentCoroutineContext().ensureActive()
            val body=FormBody.Builder().add("client_id",CLIENT_ID).add("grant_type","urn:ietf:params:oauth:grant-type:device_code").add("device_code",device.getString("device_code")).build()
            http.newCall(Request.Builder().url("$AUTH/token").post(body).build()).execute().use { response->
                val json=JSONObject(response.body!!.string())
                if(response.isSuccessful) { store(json,epoch);return account(epoch) }
                when(json.optString("error")) { "authorization_pending"->Unit;"slow_down"->interval+=5;else->error("Microsoft 登录未完成，请重新登录") }
            }
        }
        error("登录码已过期，请重新登录")
    }
    private fun item(json:JSONObject)=RemoteItem(json.getString("id"),json.optString("name"),json.getString("eTag"),runCatching { Instant.parse(json.optString("lastModifiedDateTime")).toEpochMilli() }.getOrDefault(System.currentTimeMillis()))
    fun list(epoch:Long):List<RemoteItem> {
        var path="/me/drive/root:/Notes:/children?\$select=id,name,eTag,file,lastModifiedDateTime&\$top=200";val items=mutableListOf<RemoteItem>()
        while(true) {
            val json=request("GET",path,epoch).use { response-> if(response.code==404)return items;JSONObject(checked(response).body!!.string()) }
            val values=json.getJSONArray("value");for(i in 0 until values.length()){val value=values.getJSONObject(i);if(value.has("file")&&value.optString("name").endsWith(".html",true))items.add(item(value))}
            val next=json.optString("@odata.nextLink");if(next.isEmpty())break
            require(next.startsWith("$GRAPH/")) { "拒绝不可信的分页地址" };path=next.removePrefix(GRAPH)
        }
        return items
    }
    private fun segment(value:String)=URLEncoder.encode(value,Charsets.UTF_8.name()).replace("+","%20")
    fun download(remoteId:String,epoch:Long):CloudContent {
        val path="/me/drive/items/${segment(remoteId)}"
        repeat(3) {
            val before=checked(request("GET",path,epoch)).use { item(JSONObject(it.body!!.string())) }
            val bytes=checked(request("GET","$path/content",epoch)).use { response->
                val body=response.body!!;require(body.contentLength()<=Rules.MAX_HTML) { "云端笔记超过 20 MB" }
                body.byteStream().use { input->val output=ByteArrayOutputStream();val buffer=ByteArray(16*1024);while(true){val n=input.read(buffer);if(n<0)break;require(output.size()+n<=Rules.MAX_HTML) { "云端笔记超过 20 MB" };output.write(buffer,0,n)};output.toByteArray() }
            }
            val after=checked(request("GET",path,epoch)).use { item(JSONObject(it.body!!.string())) }
            if(before.etag==after.etag&&after.etag.isNotEmpty())return CloudContent(String(bytes,Charsets.UTF_8),after.etag)
        }
        error("笔记正在其他设备修改，请稍后重试")
    }
    private fun ensureFolder(epoch:Long) {
        request("GET","/me/drive/root:/Notes",epoch).use { response->
            if(response.code!=404){checked(response);return}
        }
        request("POST","/me/drive/root/children",epoch,JSONObject().put("name","Notes").put("folder",JSONObject()).put("@microsoft.graph.conflictBehavior","fail").toString()).use { if(it.code!=409)checked(it) }
    }
    fun upload(note:Note,epoch:Long):UploadResult {
        val response:Response
        if(note.remoteId!=null)response=request("PUT","/me/drive/items/${segment(note.remoteId)}/content",epoch,note.html,note.etag?:error("缺少云端版本"))
        else {
            ensureFolder(epoch);val path="/me/drive/root:/Notes/${segment(Rules.filename(note))}"
            request("GET",path,epoch).use { existing->
                if(existing.isSuccessful) {
                    val item=item(JSONObject(existing.body!!.string()));val content=download(item.id,epoch)
                    return if(content.html==note.html)UploadResult.Uploaded(item.copy(etag=content.etag))else UploadResult.Conflict(content,item.id)
                }
                if(existing.code!=404)checked(existing)
            }
            response=request("PUT","$path:/content?@microsoft.graph.conflictBehavior=fail",epoch,note.html)
        }
        response.use {
            if(it.code==409||it.code==412) {
                val remote=note.remoteId?:error("同名文件已存在，请刷新后重试")
                return UploadResult.Conflict(download(remote,epoch),remote)
            }
            return UploadResult.Uploaded(item(JSONObject(checked(it).body!!.string())))
        }
    }
    /** A missing file is an acknowledged delete; stale ETags always become conflicts. */
    fun delete(note:Note,epoch:Long):CloudContent? {
        val remote=note.remoteId?:return null
        request("DELETE","/me/drive/items/${segment(remote)}",epoch,etag=note.etag?:error("缺少云端版本")).use {
            if(it.code==412)return download(remote,epoch)
            if(it.code!=404)checked(it)
        }
        return null
    }
}
