package com.cyeam.cnote

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import com.cyeam.cnote.sdk.NativeCore
import com.cyeam.cnote.sdk.SecureVault
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Process-scoped native session. Android owns platform services; Rust owns notes and cloud state. */
class SharedRuntime(private val app:CNoteApp) {
    class EventBus {
        private val mutable=MutableSharedFlow<JSONObject>(extraBufferCapacity=128)
        val flow=mutable.asSharedFlow()
        suspend fun send(event:JSONObject)=mutable.emit(event)
        fun trySend(event:JSONObject)=mutable.tryEmit(event)
    }
    val events=EventBus()
    private val syncLock=Mutex()
    private val syncStatuses=Channel<JSONObject>(Channel.CONFLATED)
    private val vault by lazy { SecureVault(app) }
    private val ready=CompletableDeferred<Unit>()
    private val commands=Channel<String>(Channel.UNLIMITED)
    init {
        app.scope.launch {
            try {
                app.getSystemService(JobScheduler::class.java).cancel(26001)
                val prefs=app.getSharedPreferences("shared-migration",Context.MODE_PRIVATE)
                if(!prefs.getBoolean("oauth-v1",false)) {
                    if(vault.get("onedrive-oauth")==null)TokenVault(app).read()?.let { tokens->
                        vault.set("onedrive-oauth",JSONObject().put("access_token",tokens.access).put("refresh_token",tokens.refresh).put("expires_at",tokens.expires/1000).toString())
                    }
                    check(prefs.edit().putBoolean("oauth-v1",true).commit()) { "登录状态迁移失败" }
                }
                val directory=File(app.filesDir,"shared-core").apply { mkdirs() }
                NativeCore.open(File(directory,"notes.db").path,app.getDatabasePath("notes.db").path,File(app.filesDir,"shared-downloads").path,vault)
                ready.complete(Unit)
                launch { for(raw in commands)try { NativeCore.dispatch(raw) }catch(t:Exception) { events.send(JSONObject().put("type","error").put("message",t.message)) } }
                while(isActive) {
                    val batch=JSONArray(NativeCore.poll())
                    for(i in 0 until batch.length()) {
                        val event=batch.getJSONObject(i)
                        when(event.optString("type")){"analytics"->analytics(event.getJSONObject("data"));"sync_status"->syncStatuses.send(event);else->events.send(event)}
                    }
                    delay(50)
                }
            } catch(t:Throwable) {
                ready.completeExceptionally(t)
                events.send(JSONObject().put("type","error").put("message","共享核心启动失败，原笔记已保留：${t.message}"))
            }
        }
    }
    private fun analytics(data:JSONObject) {
        when(data.optString("event")) {
            "note_created"->app.analytics.noteCreated()
            "note_deleted"->app.analytics.noteDeleted()
            "cloud"->app.analytics.sync(data.optString("operation"),data.optString("outcome"),data.optLong("duration_ms"))
        }
    }
    fun post(raw:String) { commands.trySend(raw);if(raw.contains("\"change\""))scheduleSync() }
    private fun scheduleSync() {
        val scheduler=app.getSystemService(JobScheduler::class.java)
        scheduler.schedule(JobInfo.Builder(0x434e,ComponentName(app,SharedSyncService::class.java)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setMinimumLatency(2000).setBackoffCriteria(30_000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())
    }
    suspend fun sync():Boolean=syncLock.withLock {
        ready.await();while(syncStatuses.tryReceive().isSuccess){};post("{\"type\":\"save\"}")
        withTimeoutOrNull(25_000) {
            while(true){post("{\"type\":\"sync_status\"}");val state=syncStatuses.receive();if(state.optBoolean("authenticated")&&!state.optBoolean("pending"))return@withTimeoutOrNull false;if(!state.optBoolean("authenticated")&&vault.get("onedrive-oauth")==null)return@withTimeoutOrNull false;delay(1000)}
            @Suppress("UNREACHABLE_CODE") true
        }?:true
    }
}
