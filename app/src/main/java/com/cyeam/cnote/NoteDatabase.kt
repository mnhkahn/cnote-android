package com.cyeam.cnote

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class NoteDatabase(context:Context,name:String="notes.db"):SQLiteOpenHelper(context,name,null,1) {
    init { setWriteAheadLoggingEnabled(true) }
    override fun onConfigure(db:SQLiteDatabase) { db.execSQL("PRAGMA synchronous=FULL");db.rawQuery("PRAGMA busy_timeout=5000",null).use { it.moveToFirst() } }
    override fun onCreate(db:SQLiteDatabase) {
        db.execSQL("""CREATE TABLE notes(id TEXT PRIMARY KEY,account TEXT NOT NULL DEFAULT '',remote_id TEXT,title TEXT NOT NULL,html TEXT NOT NULL,baseline TEXT NOT NULL,etag TEXT,revision INTEGER NOT NULL,dirty INTEGER NOT NULL,deleted INTEGER NOT NULL,conflict INTEGER NOT NULL,modified INTEGER NOT NULL,loaded INTEGER NOT NULL,approved_revision INTEGER NOT NULL DEFAULT -1,needs_approval INTEGER NOT NULL DEFAULT 0,conflict_html TEXT,conflict_etag TEXT,remote_missing INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("CREATE UNIQUE INDEX remote_note ON notes(account,remote_id)")
        db.execSQL("CREATE INDEX note_list ON notes(account,deleted,modified DESC)")
        db.execSQL("CREATE TABLE settings(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
    }
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int)=Unit
    private fun Cursor.string(name:String)=getString(getColumnIndexOrThrow(name))
    private fun Cursor.long(name:String)=getLong(getColumnIndexOrThrow(name))
    private fun Cursor.flag(name:String)=long(name)!=0L
    // Large HTML/images bypass CursorWindow's per-row limit on Android 8/9 too.
    private fun body(id:String,column:String):String?=readableDatabase.compileStatement("SELECT $column FROM notes WHERE id=?").use { it.bindString(1,id);it.simpleQueryForString() }
    private val metadata="id,account,remote_id,title,etag,revision,dirty,deleted,conflict,modified,loaded,approved_revision,needs_approval,conflict_etag,remote_missing"
    private fun Cursor.note()=Note(string("id"),string("account"),string("remote_id"),string("title"),body(string("id"),"html")!!,body(string("id"),"baseline")!!,string("etag"),long("revision"),flag("dirty"),flag("deleted"),flag("conflict"),long("modified"),flag("loaded"),long("approved_revision"),flag("needs_approval"),body(string("id"),"conflict_html"),string("conflict_etag"),flag("remote_missing"))
    @Synchronized fun get(id:String):Note?=readableDatabase.rawQuery("SELECT $metadata FROM notes WHERE id=?",arrayOf(id)).use { if(it.moveToFirst())it.note() else null }
    @Synchronized fun validCached(id:String,account:String):Boolean=readableDatabase.rawQuery("SELECT loaded,deleted,account FROM notes WHERE id=?",arrayOf(id)).use { it.moveToFirst()&&it.getInt(0)==1&&it.getInt(1)==0&&(it.getString(2).isEmpty()||it.getString(2)==account) }
    @Synchronized fun findRemote(account:String,remote:String):Note?=readableDatabase.rawQuery("SELECT $metadata FROM notes WHERE account=? AND remote_id=?",arrayOf(account,remote)).use { if(it.moveToFirst())it.note() else null }
    @Synchronized fun put(n:Note) {
        val v=ContentValues().apply {
            put("id",n.id);put("account",n.account);put("remote_id",n.remoteId);put("title",n.title);put("html",n.html);put("baseline",n.baseline);put("etag",n.etag)
            put("revision",n.revision);put("dirty",n.dirty);put("deleted",n.deleted);put("conflict",n.conflict);put("modified",n.modified);put("loaded",n.loaded)
            put("approved_revision",n.approvedRevision);put("needs_approval",n.needsApproval);put("conflict_html",n.conflictHtml);put("conflict_etag",n.conflictEtag);put("remote_missing",n.remoteMissing)
        }
        check(writableDatabase.insertWithOnConflict("notes",null,v,SQLiteDatabase.CONFLICT_REPLACE)!=-1L) { "本地写入失败" }
    }
    @Synchronized fun create(title:String,account:String)=Note(account=account,title=Rules.title(title)).also(::put)
    @Synchronized fun saveHtml(id:String,account:String,html:String) {
        require(html.toByteArray().size<=Rules.MAX_HTML) { "笔记超过 20 MB，请缩小图片或分篇保存" }
        writableDatabase.compileStatement("""UPDATE notes SET revision=revision+CASE WHEN html<>?1 THEN 1 ELSE 0 END,dirty=CASE WHEN html<>?1 THEN 1 ELSE dirty END,modified=CASE WHEN html<>?1 THEN ?2 ELSE modified END,html=?1 WHERE id=?3 AND deleted=0 AND loaded=1 AND (account='' OR account=?4)""").use {
            it.bindString(1,html);it.bindLong(2,System.currentTimeMillis());it.bindString(3,id);it.bindString(4,account)
            check(it.executeUpdateDelete()==1) { "笔记已删除、账号已切换或内容尚未加载；草稿仍保留在内存中" }
        }
    }
    @Synchronized fun list(account:String):List<Summary> =readableDatabase.rawQuery("SELECT id,title,modified,dirty,conflict,needs_approval FROM notes WHERE deleted=0 AND (account='' OR account=?) ORDER BY modified DESC,id",arrayOf(account)).use { c->buildList { while(c.moveToNext())add(Summary(c.string("id"),c.string("title"),c.long("modified"),c.flag("dirty"),c.flag("conflict"),c.flag("needs_approval"))) } }
    @Synchronized fun pendingIds(account:String):List<String> =readableDatabase.rawQuery("SELECT id FROM notes WHERE dirty=1 AND conflict=0 AND (account='' OR account=?)",arrayOf(account)).use { c->buildList { while(c.moveToNext())add(c.getString(0)) } }
    @Synchronized fun pending(account:String):List<Note> =readableDatabase.rawQuery("SELECT $metadata FROM notes WHERE dirty=1 AND conflict=0 AND (account='' OR account=?)",arrayOf(account)).use { c->buildList { while(c.moveToNext())add(c.note()) } }
    @Synchronized fun remoteNotes(account:String):List<Note> =readableDatabase.rawQuery("SELECT $metadata FROM notes WHERE account=? AND remote_id IS NOT NULL",arrayOf(account)).use { c->buildList { while(c.moveToNext())add(c.note()) } }
    @Synchronized fun remove(id:String) { writableDatabase.delete("notes","id=?",arrayOf(id)) }
    @Synchronized fun setting(key:String):String? =readableDatabase.rawQuery("SELECT value FROM settings WHERE key=?",arrayOf(key)).use { if(it.moveToFirst())it.getString(0) else null }
    @Synchronized fun setting(key:String,value:String) { writableDatabase.insertWithOnConflict("settings",null,ContentValues().apply { put("key",key);put("value",value) },SQLiteDatabase.CONFLICT_REPLACE) }
    @Synchronized fun uploaded(sent:Note,remote:String,etag:String,account:String) { get(sent.id)?.let { put(Rules.acknowledge(it,sent,remote,etag,account)) } }
    @Synchronized fun claim(id:String,account:String):Note? =get(id)?.also { require(it.account.isEmpty()||it.account==account) { "账号不匹配" } }?.let { n->if(n.account==account)n else n.copy(account=account).also(::put) }
    @Synchronized fun markDeleted(id:String,account:String) {
        val n=get(id)?:error("笔记不存在");require(n.account.isEmpty()||n.account==account) { "账号不匹配" }
        if(n.remoteId==null)remove(id) else put(n.copy(deleted=true,dirty=true,needsApproval=false))
    }
    @Synchronized fun approval(id:String,revision:Long,account:String) { get(id)?.takeIf { it.account==account&&it.revision==revision }?.let { put(it.copy(approvedRevision=revision,needsApproval=false)) } }
    @Synchronized fun needApproval(id:String,revision:Long) { get(id)?.takeIf { it.revision==revision }?.let { put(it.copy(needsApproval=true)) } }
    @Synchronized fun conflict(id:String,html:String?,etag:String?,missing:Boolean=false) { get(id)?.let { put(it.copy(deleted=false,dirty=true,conflict=true,needsApproval=false,conflictHtml=html,conflictEtag=etag,remoteMissing=missing)) } }
    @Synchronized fun resolve(id:String,choice:String,account:String) {
        val n=get(id)?:error("笔记不存在");require(n.account.isEmpty()||n.account==account) { "账号已切换" };require(n.conflict)
        if(n.remoteMissing) { if(choice=="cloud")remove(id) else put(n.copy(remoteId=null,etag=null,baseline="",deleted=false,conflict=false,remoteMissing=false,dirty=true,approvedRevision=n.revision,conflictHtml=null,conflictEtag=null)) }
        else { val html=n.conflictHtml?:error("缺少云端内容");val keep=choice!="cloud";put(n.copy(html=if(keep)n.html else html,baseline=html,etag=n.conflictEtag,conflict=false,dirty=keep,loaded=true,deleted=false,revision=if(keep)n.revision else n.revision+1,approvedRevision=n.revision,conflictHtml=null,conflictEtag=null)) }
    }
    @Synchronized fun heads(account:String):List<RemoteHead> =readableDatabase.rawQuery("SELECT id,remote_id,etag,dirty,deleted,loaded FROM notes WHERE account=? AND remote_id IS NOT NULL",arrayOf(account)).use { c->buildList { while(c.moveToNext())add(RemoteHead(c.string("id"),c.string("remote_id"),c.string("etag"),c.flag("dirty"),c.flag("deleted"),c.flag("loaded"))) } }
    @Synchronized fun mergeRemote(account:String,item:RemoteItem):String {
        val local=readableDatabase.rawQuery("SELECT id,dirty FROM notes WHERE account=? AND remote_id=?",arrayOf(account,item.id)).use { if(it.moveToFirst())it.getString(0) to (it.getInt(1)!=0) else null }
        if(local==null) { val n=Note(account=account,remoteId=item.id,title=Rules.displayTitle(item.name),html="",etag=item.etag,dirty=false,loaded=false,modified=item.modified);put(n);return n.id }
        val values=ContentValues().apply { if(!local.second){put("title",Rules.displayTitle(item.name));put("modified",item.modified)} };if(values.size()==0)return local.first
        writableDatabase.update("notes",values,"id=?",arrayOf(local.first));return local.first
    }
    /** Atomically reconcile a download with edits that may have arrived during the HTTP request. */
    @Synchronized fun downloaded(id:String,html:String,etag:String,editing:Boolean):Boolean {
        val n=get(id)?:return false;if(n.deleted)return false
        if((n.dirty||editing)&&n.html!=html&&(n.etag!=etag||n.conflict)) { conflict(id,html,etag);return false }
        if((!n.dirty&&!editing)||n.html==html) { val changed=n.html!=html||!n.loaded;put(n.copy(html=html,baseline=html,etag=etag,loaded=true,dirty=false,conflict=false,needsApproval=false));return changed }
        return false
    }
}

/** Small cloud-list records never load HTML or image data. */
data class RemoteHead(val id:String,val remoteId:String,val etag:String?,val dirty:Boolean,val deleted:Boolean,val loaded:Boolean)
