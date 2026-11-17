package com.cyeam.cnote

import android.webkit.WebView
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/** The existing Android note UI depends only on this host contract. */
interface NotebookSession {
    val state:StateFlow<UiState>
    val effects:Channel<JSONObject>
    var promptedUpdate:Long
    var pendingInstall:Boolean
    var editorView:WebView?
    var editorReady:Boolean
    val pendingScripts:ArrayDeque<String>
    fun bridge(raw:String)
    fun command(type:String,data:JSONObject=JSONObject())
}
