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
    @Test fun approvalOnlyAppliesToExactRevision() {
        val n=db.create("approval","a");db.needApproval(n.id,0);db.saveHtml(n.id,"a","new");db.approval(n.id,0,"a");assertEquals(-1,db.get(n.id)!!.approvedRevision)
        db.approval(n.id,1,"a");assertEquals(1,db.get(n.id)!!.approvedRevision);assertFalse(db.get(n.id)!!.needsApproval)
    }
    @Test fun inlineImagesLargerThanCursorWindowSurviveReopen() {
        val html="<p>large image</p><img src=\"data:image/png;base64,"+"a".repeat(3*1024*1024)+"\">"
        val n=db.create("图片","a");db.saveHtml(n.id,"a",html);db.close();db=NoteDatabase(context,name)
        assertEquals(html,db.get(n.id)!!.html);assertEquals(html,db.pending("a").single().html)
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
