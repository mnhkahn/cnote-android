package com.cyeam.cnote

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageTest {
    private val context=ApplicationProvider.getApplicationContext<CNoteApp>()
    private val name="test-${System.nanoTime()}.db"
    private var db=NoteDatabase(context,name)
    @After fun cleanup() { db.close();context.deleteDatabase(name) }
    @Test fun durableSaveSurvivesReopenAndAccountsStayIsolated() {
        val n=db.create("离线笔记","");db.saveHtml(n.id,"","<p>离线内容 ✓</p>")
        db.close();db=NoteDatabase(context,name)
        assertEquals("<p>离线内容 ✓</p>",db.get(n.id)!!.html)
        db.claim(n.id,"a");assertTrue(db.list("b").isEmpty());assertEquals(1,db.list("a").size)
        assertThrows(IllegalStateException::class.java) { db.saveHtml(n.id,"b","wrong account") }
        assertFalse(db.validCached(n.id,"b"));assertTrue(db.validCached(n.id,"a"))
    }
    @Test fun inFlightUploadKeepsConcurrentEditAndTombstone() {
        val n=db.create("Race","a");db.saveHtml(n.id,"a","before");val sent=db.get(n.id)!!
        db.saveHtml(n.id,"a","after");db.uploaded(sent,"r","e1","a")
        assertEquals("after",db.get(n.id)!!.html);assertTrue(db.get(n.id)!!.dirty)
        val second=db.get(n.id)!!;db.markDeleted(n.id,"a");db.uploaded(second,"r","e2","a")
        assertTrue(db.get(n.id)!!.deleted);assertTrue(db.list("a").isEmpty());assertEquals(1,db.pending("a").size)
    }
    @Test fun remoteChangesCannotReplaceUnsavedOrDirtyEditor() {
        val n=Note(account="a",title="冲突",remoteId="r",html="base",baseline="base",etag="v1",dirty=false);db.put(n)
        assertFalse(db.downloaded(n.id,"remote","v2",true));assertEquals("base",db.get(n.id)!!.html);assertTrue(db.get(n.id)!!.conflict)
        db.saveHtml(n.id,"a","latest local");db.resolve(n.id,"mine","a")
        assertEquals("latest local",db.get(n.id)!!.html);assertEquals("remote",db.get(n.id)!!.baseline);assertEquals("v2",db.get(n.id)!!.etag)
        db.conflict(n.id,"newer remote","v3");db.resolve(n.id,"cloud","a")
        assertEquals("newer remote",db.get(n.id)!!.html);assertFalse(db.get(n.id)!!.dirty)
    }
    @Test fun deletedCloudNoteCanBeRestoredWithoutReusingMissingId() {
        val n=Note(account="a",title="restore",remoteId="gone",etag="v1");db.put(n);db.conflict(n.id,null,null,true);db.resolve(n.id,"mine","a")
        assertNull(db.get(n.id)!!.remoteId);assertTrue(db.get(n.id)!!.dirty);assertFalse(db.get(n.id)!!.conflict)
    }
    @Test fun cloudPlaceholdersAreReadOnlyAndListDoesNotLoadBodies() {
        val id=db.mergeRemote("a",RemoteItem("remote","标题--12345678.html","v1",100))
        assertEquals("标题",db.list("a").single().title);assertFalse(db.get(id)!!.loaded)
        assertThrows(IllegalStateException::class.java) { db.saveHtml(id,"a","empty overwrite") }
        assertTrue(db.downloaded(id,"<p>downloaded</p>","v1",false));assertTrue(db.validCached(id,"a"))
    }
    @Test fun legacyApprovalFlagsAreClearedOnOpenAndLargeEditsRemainPending() {
        val n=Note(account="a",title="legacy",html="old".repeat(500),needsApproval=true,approvedRevision=7)
        db.put(n);db.close();db=NoteDatabase(context,name)
        assertFalse(db.get(n.id)!!.needsApproval);assertEquals(-1,db.get(n.id)!!.approvedRevision)
        db.saveHtml(n.id,"a","");assertEquals(n.id,db.pendingIds("a").single());assertFalse(db.get(n.id)!!.needsApproval)
    }
    @Test fun mergedConflictChecksReviewedRevisionAndEtagAndPersistsBothVersionsOnFailure() {
        val n=Note(account="a",title="merge",remoteId="r",html="local",baseline="old",etag="v1");db.put(n);db.conflict(n.id,"remote","v2")
        assertThrows(IllegalArgumentException::class.java){db.resolve(n.id,"merge","a",99,"v2","wrong")}
        assertThrows(IllegalArgumentException::class.java){db.resolve(n.id,"cloud","a",0,"stale")}
        assertEquals("local",db.get(n.id)!!.html);assertEquals("remote",db.get(n.id)!!.conflictHtml);assertTrue(db.get(n.id)!!.conflict)
        db.resolve(n.id,"merge","a",0,"v2","merged")
        val resolved=db.get(n.id)!!;assertEquals("merged",resolved.html);assertEquals("remote",resolved.baseline);assertEquals("v2",resolved.etag);assertTrue(resolved.dirty);assertFalse(resolved.conflict);assertEquals(1,resolved.revision)
        db.close();db=NoteDatabase(context,name);assertEquals("merged",db.get(n.id)!!.html)
    }
    @Test fun inlineImagesLargerThanCursorWindowSurviveReopen() {
        val html="<p>large image</p><img src=\"data:image/png;base64,"+"a".repeat(3*1024*1024)+"\">"
        val n=db.create("图片","a");db.saveHtml(n.id,"a",html);db.close();db=NoteDatabase(context,name)
        assertEquals(html,db.get(n.id)!!.html);assertEquals(html,db.pending("a").single().html)
    }
    @Test fun bodyTitleSurvivesEditingSyncConflictAndReopenWithoutRenamingTheFile() {
        val id=db.mergeRemote("a",RemoteItem("remote","文件名称--12345678.html","v1",100))
        db.downloaded(id,"<h1>正文标题</h1><p>正文</p>","v1",false)
        assertEquals("正文标题",db.list("a").single().title)
        assertEquals("文件名称",db.get(id)!!.title)
        db.mergeRemote("a",RemoteItem("remote","文件名称--12345678.html","v1",100))
        assertEquals("正文标题",db.displayTitle(id))
        db.saveHtml(id,"a","<h2>修改 / 标题 &amp; 更多</h2>")
        assertEquals("修改 / 标题 & 更多",db.list("a").single().title)
        db.conflict(id,"<h1>云端标题</h1>","v2");db.resolve(id,"cloud","a")
        assertEquals("云端标题",db.displayTitle(id))
        db.close();db=NoteDatabase(context,name)
        assertEquals("云端标题",db.list("a").single().title)
        db.saveHtml(id,"a","<p>移除标题</p>")
        assertEquals("文件名称",db.displayTitle(id))
    }
    @Test fun versionOneDatabaseBackfillsTitlesWithoutChangingContentOrSyncState() {
        db.close();context.deleteDatabase(name)
        val legacy=android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name),null)
        // Reproduce the previous schema, then open it with the new helper.
        db.onCreate(legacy)
        val schema=legacy.rawQuery("SELECT sql FROM sqlite_master WHERE name='notes'",null).use { it.moveToFirst();it.getString(0) }.replace("heading TEXT NOT NULL DEFAULT '',", "")
        legacy.execSQL("DROP TABLE notes");legacy.execSQL(schema)
        legacy.execSQL("INSERT INTO notes(id,account,title,html,baseline,revision,dirty,deleted,conflict,modified,loaded) VALUES('legacy','a','旧文件名','<h1>已有正文标题</h1>','baseline',7,0,0,0,100,1)")
        legacy.version=1;legacy.close();db=NoteDatabase(context,name)
        assertEquals("已有正文标题",db.list("a").single().title)
        val note=db.get("legacy")!!
        assertEquals("旧文件名",note.title);assertEquals(7L,note.revision);assertFalse(note.dirty);assertEquals("baseline",note.baseline)
    }
    @Test fun sessionIsAuthenticatedEncryptionAndCanBeRestored() {
        val prefsName="vault-test-${System.nanoTime()}";val vault=TokenVault(context,prefsName)
        val tokens=Tokens("test-access-only","test-refresh-only",12345)
        try {
            vault.write(tokens);assertEquals(tokens,TokenVault(context,prefsName).read())
            val disk=context.getSharedPreferences(prefsName,0).getString("session",null)!!
            assertFalse(disk.contains(tokens.access));assertFalse(disk.contains(tokens.refresh))
            val bytes=android.util.Base64.decode(disk,0);bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte()
            context.getSharedPreferences(prefsName,0).edit().putString("session",android.util.Base64.encodeToString(bytes,2)).commit()
            assertThrows(Exception::class.java) { vault.read() }
        }finally { vault.clear();java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null);deleteEntry("com.cyeam.cnote.$prefsName") };context.deleteSharedPreferences(prefsName) }
    }
}
