package com.cyeam.cnote

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.json.JSONObject

/** The bridge only enqueues strings; parsing, HTML and SQLite never run on the UI thread. */
class NotebookViewModel(app:Application):AndroidViewModel(app) {
    val repository=(app as CNoteApp).repository
    val state=repository.state
    val effects=repository.events
    // Keep the live editing trees through Activity recreation, without retaining an Activity context.
    var promptedUpdate:Long = -1
    var pendingInstall=false
    var editorView:android.webkit.WebView?=null
    @Volatile var editorReady=false
    val pendingScripts=ArrayDeque<String>()
    fun bridge(raw:String) {
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
                "review"->repository.showConflict(id)
                "resolve"->repository.resolve(id,m.getString("choice"))
                "approve"->repository.approve(id,m.getLong("revision"))
                "login"->repository.login()
                "commit_login"->repository.commitLogin()
                "cancel_login"->repository.cancelLogin()
                "logout"->repository.logout()
                "close","export","print","link","sidebar","image","install_update"->effects.trySend(m)
            }
        } catch(t:Exception) { repository.error(t) }
    } }
    fun post(raw:String) { commands.trySend(raw) }
    fun command(type:String,data:JSONObject=JSONObject())=post(data.put("type",type).toString())
}
