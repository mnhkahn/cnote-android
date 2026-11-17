package com.cyeam.cnote

import android.app.Application
import android.webkit.WebView
import androidx.lifecycle.AndroidViewModel
import org.json.JSONObject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

class SharedViewModel(app:Application):AndroidViewModel(app) {
    val runtime=(app as CNoteApp).shared
    var web:WebView?=null
    var ready=false
    val scripts=ArrayDeque<String>()
    var export:Pair<String,ByteArray>?=null
    var exportRequest:Long?=null
    var imageTarget:String?=null
    var selected:String?=null
    var pendingInstall=false
    var promptedUpdate:Long = -1
    private val bridgeMessages=Channel<String>(Channel.UNLIMITED)
    init { (app as CNoteApp).scope.launch { for(raw in bridgeMessages)try {
        val message=JSONObject(raw)
        when(message.optString("type")) {
            "ready"->runtime.post("{\"type\":\"state\"}")
            "mobile_ready","link","image","image_clipboard","export","print","check_update","install_update","close","sidebar","workspace","guard","tool_guard","refresh_workspace"->runtime.events.send(message)
            else->runtime.post(raw)
        }
    }catch(t:Exception){runtime.events.send(JSONObject().put("type","error").put("message",t.message))} } }
    fun bridge(raw:String){bridgeMessages.trySend(raw)}
    fun post(raw:String)=runtime.post(raw)
    override fun onCleared(){bridgeMessages.close();web?.destroy();web=null;super.onCleared()}
    fun reply(request:Long,data:JSONObject=JSONObject(),error:String?=null) {
        runtime.events.trySend(JSONObject().put("type","trello").put("result",JSONObject().put("request",request).apply { if(error==null)put("data",data) else put("error",error) }))
    }
}
