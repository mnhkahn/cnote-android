package com.cyeam.cnote

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Disk and network work live in the application scope, independently of any Activity. */
class Repository(private val context:Context,private val scope:CoroutineScope,private val analytics:Analytics) {
    val db=NoteDatabase(context)
    private val graph=GraphClient(TokenVault(context))
    private val mutable=MutableStateFlow(UiState())
    val state=mutable.asStateFlow()
    val events=Channel<JSONObject>(Channel.UNLIMITED)
    private val boot=CompletableDeferred<Unit>()
    private val syncLock=Mutex()
    private val accountLock=Any()
    private val editing=ConcurrentHashMap.newKeySet<String>()
    private var debounce:Job?=null
    private var loginJob:Job?=null
    @Volatile private var loggingIn=false
    @Volatile private var pendingSession:Pair<Long,Session>?=null
    @Volatile var intent=0L
    private fun emit(type:String,body:JSONObject=JSONObject()) { events.trySend(body.put("type",type)) }
    fun status(message:String,busy:Boolean=false) { mutable.update { it.copy(status=message,busy=busy) };emit("state",JSONObject().put("status",message)) }
    fun error(t:Throwable) { if(t !is CancellationException)status(t.message?:"操作失败，请重试") }
    suspend fun start() {
        try {
            val id=db.setting("account").orEmpty();val name=db.setting("account_name").orEmpty()
            mutable.update { it.copy(session=Session(id,name),notes=db.list(id)) };boot.complete(Unit)
            graph.load()
            if(graph.hasStoredSession()) {
                val epoch=graph.generation.get()
                try {
                    val account=graph.account(epoch)
                    if(account.id!=state.value.session.id) { loggingIn=true;pendingSession=epoch to account;emit("commit_login") }
                    else { synchronized(accountLock) { graph.check(epoch);setSession(account) };sync(true) }
                } catch(t:Exception) { if(graph.valid(epoch))status("离线可用 · 联网后自动重试 OneDrive") }
            }
        } catch(t:Exception) { boot.complete(Unit);error(t) }
    }
    suspend fun ready() { boot.await();refreshList() }
    private fun setSession(session:Session) {
        db.setting("account",session.id);db.setting("account_name",session.name)
        mutable.update { it.copy(session=session,selected=if(it.session.id==session.id)it.selected else null,notes=db.list(session.id)) }
    }
    fun refreshList() { mutable.update { it.copy(notes=db.list(it.session.id)) } }
    fun navigation(id:String?,value:Long) { intent=maxOf(intent,value);mutable.update { it.copy(selected=id) } }
    fun markEditing(id:String,value:Boolean) { if(value)editing.add(id) else editing.remove(id) }
    fun create(title:String) {
        val n=db.create(title,state.value.session.id);analytics.noteCreated();refreshList();navigation(n.id,intent+1);load(n);scheduleSync()
    }
    private fun owned(n:Note) { require(n.account.isEmpty()||n.account==state.value.session.id) { "账号已切换" };require(!n.deleted) { "笔记已删除" } }
    private fun load(n:Note) { if(n.loaded)emit("load",JSONObject().put("id",n.id).put("title",n.title).put("html",n.html).put("intent",intent)) }
    fun open(id:String,cached:Boolean) {
        if(cached) { require(db.validCached(id,state.value.session.id)) { "笔记已删除或账号已切换" };scope.launch { downloadSelected(id,false) };return }
        val n=db.get(id)?:error("笔记不存在");owned(n)
        if(!cached&&n.loaded)load(n)
        else if(!n.loaded)status("正在下载笔记，已下载的笔记可离线使用")
        if(n.remoteId!=null)scope.launch { downloadSelected(n.id,false) }
    }
    fun save(id:String,html:String,seq:Long) {
        try {
            db.saveHtml(id,state.value.session.id,html)
            emit("saved",JSONObject().put("id",id).put("seq",seq));status("已保存到本机");scheduleSync()
        } catch(t:Exception) { emit("save_failed",JSONObject().put("id",id).put("seq",seq).put("message",t.message.orEmpty())) }
    }
    fun delete(id:String) {
        db.markDeleted(id,state.value.session.id);editing.remove(id);analytics.noteDeleted();navigation(null,intent);refreshList()
        emit("load",JSONObject().put("id",JSONObject.NULL).put("intent",intent));emit("list");scheduleSync()
    }
    fun showConflict(id:String) {
        val n=db.get(id)?:return;owned(n)
        if(n.conflict||n.needsApproval)emit("review",JSONObject().put("id",id).put("title",n.title).put("missing",n.remoteMissing).put("revision",n.revision).put("approval",n.needsApproval&&!n.conflict))
    }
    fun resolve(id:String,choice:String) {
        db.resolve(id,choice,state.value.session.id);editing.remove(id);refreshList()
        db.get(id)?.let { if(state.value.selected==id)load(it) }?:run { navigation(null,intent);emit("load",JSONObject().put("id",JSONObject.NULL));emit("list") }
        scheduleSync()
    }
    fun approve(id:String,revision:Long) { db.approval(id,revision,state.value.session.id);refreshList();scheduleSync() }
    fun login() {
        if(loggingIn)return
        loggingIn=true
        val epoch=synchronized(accountLock) { graph.generation.incrementAndGet() }
        loginJob=scope.launch {
            status("正在连接 Microsoft…",true)
            try {
                val account=graph.login(epoch) { code,url->emit("login_code",JSONObject().put("code",code).put("url",url)) }
                graph.check(epoch);pendingSession=epoch to account;emit("commit_login")
            } catch(t:Exception) { if(graph.valid(epoch)){loggingIn=false;analytics.login(false);error(t);emit("login_done")} }
        }
    }
    fun commitLogin() {
        val pending=pendingSession?:return
        synchronized(accountLock) { graph.check(pending.first);setSession(pending.second);pendingSession=null;loggingIn=false;editing.clear() }
        emit("load",JSONObject().put("id",JSONObject.NULL));emit("list");emit("login_done");analytics.login(true);scope.launch { sync(true) }
    }
    fun cancelLogin() { synchronized(accountLock) { pendingSession=null;loggingIn=false;graph.logout();mutable.update { it.copy(session=it.session.copy(authenticated=false)) } };loginJob?.cancel();status("登录已取消 · 笔记保存在本机，请重新登录以同步") }
    fun logout() {
        synchronized(accountLock) { pendingSession=null;loggingIn=false;graph.logout();setSession(Session());editing.clear() }
        loginJob?.cancel();emit("load",JSONObject().put("id",JSONObject.NULL));emit("list");emit("login_done");status("已退出登录 · 本机笔记保留")
    }
    fun scheduleSync() {
        debounce?.cancel();debounce=scope.launch { delay(2000);scope.launch { sync(false) } }
        val scheduler=context.getSystemService(JobScheduler::class.java)
        scheduler.schedule(JobInfo.Builder(26001,ComponentName(context,SyncJobService::class.java)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setMinimumLatency(3000).setBackoffCriteria(30_000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())
    }
    /** Return true only when a transient error warrants a background retry. */
    suspend fun sync(refresh:Boolean):Boolean=syncLock.withLock {
        boot.await();val epoch=graph.generation.get();val session=state.value.session
        if(loggingIn||session.id.isEmpty()||!graph.hasStoredSession())return@withLock false
        val started=System.currentTimeMillis();status("正在同步 OneDrive…",true)
        try {
            if(!session.authenticated) {
                val restored=graph.account(epoch)
                synchronized(accountLock) { graph.check(epoch);require(restored.id==session.id);mutable.update { it.copy(session=restored) } }
            }
            for(id in db.pendingIds(session.id)) {
                graph.check(epoch)
                val sent=synchronized(accountLock) { graph.check(epoch);db.claim(id,session.id) }?:continue
                if(sent.conflict)continue
                if(sent.deleted) {
                    val conflict=graph.delete(sent,epoch)
                    synchronized(accountLock) { graph.check(epoch);if(conflict==null)db.remove(sent.id) else db.conflict(sent.id,conflict.html,conflict.etag) }
                } else {
                    if(sent.remoteId!=null&&sent.approvedRevision!=sent.revision&&Rules.largeChange(sent.baseline,sent.html)) { db.needApproval(sent.id,sent.revision);continue }
                    val result=graph.upload(sent,epoch)
                    synchronized(accountLock) {
                        graph.check(epoch)
                        when(result) {
                            is UploadResult.Uploaded->db.uploaded(sent,result.item.id,result.item.etag,session.id)
                            is UploadResult.Conflict->{db.get(sent.id)?.let { db.put(it.copy(remoteId=result.remoteId)) };db.conflict(sent.id,result.content.html,result.content.etag)}
                        }
                    }
                }
            }
            if(refresh) {
                val cloud=graph.list(epoch);val ids=cloud.map { it.id }.toHashSet()
                synchronized(accountLock) {
                    graph.check(epoch)
                    for(head in db.heads(session.id))if(head.remoteId !in ids) {
                        if((head.dirty&&!head.deleted)||editing.contains(head.id))db.conflict(head.id,null,null,true)
                        else { db.remove(head.id);if(state.value.selected==head.id){navigation(null,intent);emit("load",JSONObject().put("id",JSONObject.NULL));emit("list")} }
                    }
                    cloud.forEach { db.mergeRemote(session.id,it) }
                }
            }
            graph.check(epoch);refreshList()
            if(refresh)state.value.selected?.let { downloadSelected(it,true) }
            val blocked=state.value.notes.count { it.conflict||it.needsApproval }
            status(if(blocked>0)"$blocked 篇笔记需要确认 · 点击同步状态处理" else "已同步 OneDrive")
            analytics.sync(if(refresh)"refresh" else "upload","success",System.currentTimeMillis()-started)
            false
        } catch(t:Exception) {
            if(graph.valid(epoch)) { refreshList();status("已保存到本机 · ${t.message?:"OneDrive 暂不可用"}");analytics.sync("sync","failure",System.currentTimeMillis()-started) }
            t !is CancellationException
        }
    }
    private suspend fun downloadSelected(id:String,locked:Boolean) {
        suspend fun perform() {
            if(loggingIn||state.value.session.id.isEmpty()||!graph.hasStoredSession())return
            val epoch=graph.generation.get();val n=db.get(id)?:return
            if(n.account!=state.value.session.id||n.remoteId==null||n.deleted||!graph.hasStoredSession())return
            try {
                val content=graph.download(n.remoteId,epoch)
                synchronized(accountLock) {
                    graph.check(epoch)
                    val changed=db.downloaded(id,content.html,content.etag,editing.contains(id))
                    if(changed) { if(state.value.selected==id)db.get(id)?.let(::load) else emit("invalidate",JSONObject().put("id",id)) }
                    refreshList()
                }
            } catch(t:Exception) { if(graph.valid(epoch)) { if(t is GraphFailure&&t.status==404)db.conflict(id,null,null,true);error(t) } }
        }
        if(locked)perform() else syncLock.withLock { perform() }
    }
}
