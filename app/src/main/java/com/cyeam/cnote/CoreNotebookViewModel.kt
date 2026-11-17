package com.cyeam.cnote

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Adapter between the shared Rust engine and the unchanged Android note UI/editor. */
class CoreNotebookViewModel(app:Application):AndroidViewModel(app),NotebookSession {
    private val runtime=(app as CNoteApp).shared
    private val mutable=MutableStateFlow(UiState())
    override val state=mutable.asStateFlow()
    override val effects=Channel<JSONObject>(Channel.UNLIMITED)
    override var promptedUpdate:Long = -1
    override var pendingInstall=false
    override var editorView:android.webkit.WebView?=null
    @Volatile override var editorReady=false
    override val pendingScripts=ArrayDeque<String>()
    private val commands=Channel<String>(Channel.UNLIMITED)
    @Volatile private var wantsEditor=false
    @Volatile private var navigationIntent=0L
    private var account=""
    private var generation:Long = -1
    private val cached=mutableSetOf<String>()
    init {
        viewModelScope.launch(Dispatchers.IO) { runtime.events.flow.collect { accept(it) } }
        viewModelScope.launch(Dispatchers.IO) { for(raw in commands)try { dispatch(JSONObject(raw)) }catch(t:Exception){effects.send(JSONObject().put("type","error").put("message",t.message))} }
    }
    private suspend fun accept(event:JSONObject) {
        when(event.optString("type")) {
            "state"->{
                val current=event.optString("cache_account")
                if(current!=account||event.optLong("cache_generation")!=generation){for(id in cached)effects.send(JSONObject().put("type","invalidate").put("id",id));cached.clear()}
                account=current;generation=event.optLong("cache_generation")
                val notes=event.getJSONArray("notes");val summaries=(0 until notes.length()).map { i->val n=notes.getJSONObject(i);Summary(n.getString("id"),n.getString("title"),n.getLong("modified"),n.getBoolean("dirty"),n.getBoolean("conflict"),false) }
                mutable.update { it.copy(notes=summaries,session=Session(account,event.optString("account"),event.optBoolean("authenticated")),status=event.optString("status")) }
                effects.send(JSONObject().put("type","state").put("status",event.optString("status")))
            }
            "load"->{
                if(event.optLong("intent",navigationIntent)<navigationIntent)return
                val id=event.optString("id").takeIf { it.isNotEmpty()&&it!="null" }
                if(id==null){wantsEditor=false;mutable.update { it.copy(selected=null) };effects.send(JSONObject().put("type","load").put("id",JSONObject.NULL));effects.send(JSONObject().put("type","list"))}
                else if(wantsEditor){cached.add(id);mutable.update { it.copy(selected=id) };effects.send(event)}
                else if(id in cached)effects.send(JSONObject().put("type","invalidate").put("id",id))
            }
            "selected"->if(wantsEditor&&event.optLong("intent",navigationIntent)>=navigationIntent)mutable.update { it.copy(selected=event.getString("id")) }
            "saved"->{mutable.update { it.copy(notes=it.notes.map { n->if(n.id==event.optString("id"))n.copy(title=event.optString("title",n.title),dirty=true) else n },status="已保存到本机") };effects.send(event)}
            "title"->mutable.update { it.copy(notes=it.notes.map { n->if(n.id==event.optString("id"))n.copy(title=event.getString("title")) else n }) }
            "dialog"->when(event.optString("kind")) {
                "login"->effects.send(JSONObject(event.getJSONObject("data").toString()).put("type","login_code"))
                "login_done"->effects.send(JSONObject().put("type","login_done"))
                "conflict","conflict_view","conflict_result"->{
                    if(event.optString("kind")=="conflict_view"&&event.getJSONObject("data").optString("etag")=="__cnote_remote_missing__")event.getJSONObject("data").put("missing",true)
                    effects.send(event)
                }
            }
            "closed"->effects.send(JSONObject().put("type","close"))
            "close_failed"->effects.send(JSONObject().put("type","error").put("message",event.optString("message")))
            "error","save_failed"->{mutable.update { it.copy(status=event.optString("message")) };effects.send(event)}
            "invalidate"->effects.send(event)
        }
    }
    private suspend fun dispatch(message:JSONObject) {
        when(message.optString("type")) {
            "ready"->{editorReady=true;effects.send(JSONObject().put("type","web_ready"));runtime.post("{\"type\":\"state\"}")}
            "navigation"->{navigationIntent=maxOf(navigationIntent,message.optLong("intent"));wantsEditor=!message.isNull("id")&&message.optString("id").isNotEmpty();mutable.update { it.copy(selected=message.optString("id").takeIf { id->id.isNotEmpty()&&id!="null" }) }}
            "new","open"->{wantsEditor=true;runtime.post(message.toString())}
            "list"->{wantsEditor=false;runtime.post("{\"type\":\"state\"}")}
            "sync","save"->runtime.post("{\"type\":\"save\"}")
            "review"->runtime.post(message.put("type","conflict_view").toString())
            "cancel_login"->runtime.post("{\"type\":\"logout\"}")
            "commit_login"->Unit
            "image_clipboard"->{
                val result=runCatching { ImageClipboard.copy(getApplication(),message.getString("src")) }
                effects.send(JSONObject().put("type","image_clipboard_result").put("token",message.getLong("token")).put("ok",result.isSuccess).put("message",result.exceptionOrNull()?.message.orEmpty()))
            }
            "export","print","link","sidebar","image","paste_image","install_update","switch_workspace"->effects.send(message)
            else->runtime.post(message.toString())
        }
    }
    override fun bridge(raw:String){commands.trySend(raw)}
    override fun command(type:String,data:JSONObject){commands.trySend(data.put("type",type).toString())}
}
