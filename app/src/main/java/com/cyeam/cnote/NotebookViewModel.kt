package com.cyeam.cnote

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.json.JSONObject

/** The bridge only enqueues strings; parsing, HTML and SQLite never run on the UI thread. */
class NotebookViewModel(app:Application):AndroidViewModel(app),NotebookSession {
    val repository=(app as CNoteApp).repository
    override val state=repository.state
    override val effects=repository.events
    // Keep the live editing trees through Activity recreation, without retaining an Activity context.
    override var promptedUpdate:Long = -1
    override var pendingInstall=false
    override var editorView:android.webkit.WebView?=null
    @Volatile override var editorReady=false
    override val pendingScripts=ArrayDeque<String>()
    override fun bridge(raw:String) {
        if(raw=="{\"type\":\"ready\"}"){editorReady=true;effects.trySend(JSONObject().put("type","web_ready"))}
        post(raw)
    }
    private val commands=Channel<String>(Channel.UNLIMITED)
    init { viewModelScope.launch(Dispatchers.IO) {
        repository.ready()
        for(raw in commands)try {
            val m=JSONObject(raw);val id=m.optString("id");val type=m.getString("type")
            when(type) {
                "ready"->{repository.refreshList();repository.status(state.value.status)}
                "navigation"->repository.navigation(m.optString("id").takeIf { it.isNotEmpty()&&it!="null" },m.optLong("intent"))
                "new"->repository.create(m.getString("title"))
                "open"->repository.open(id,m.optBoolean("cached"))
                "change"->repository.save(id,m.getString("html"),m.getLong("seq"))
                "editing"->repository.markEditing(id,true)
                "settled"->repository.markEditing(id,false)
                "save"->repository.scheduleSync()
                "delete"->repository.delete(id)
                "list"->{repository.navigation(null,repository.intent);repository.refreshList()}
                "sync"->(getApplication<CNoteApp>()).scope.launch { repository.sync(true) }
                "review","conflict_view"->repository.showConflict(id)
                "resolve"->repository.resolve(m)
                "login"->repository.login()
                "commit_login"->repository.commitLogin()
                "cancel_login"->repository.cancelLogin()
                "logout"->repository.logout()
                "image_clipboard"->copyImage(m)
                "close","export","print","link","sidebar","image","paste_image","install_update","switch_workspace"->effects.trySend(m)
            }
        } catch(t:Exception) { repository.error(t) }
    } }
    private fun copyImage(message:JSONObject) {
        viewModelScope.launch(Dispatchers.IO) {
            val result=runCatching { ImageClipboard.copy(getApplication(),message.getString("src")) }
            effects.trySend(JSONObject().put("type","image_clipboard_result").put("token",message.getLong("token")).put("ok",result.isSuccess).put("message",result.exceptionOrNull()?.message.orEmpty()))
        }
    }
    fun post(raw:String) { commands.trySend(raw) }
    override fun command(type:String,data:JSONObject)=post(data.put("type",type).toString())
}
