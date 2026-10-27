package com.cyeam.cnote

import android.content.ClipboardManager
import android.graphics.BitmapFactory
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ConflictImageTest {
    private fun js(scenario:ActivityScenario<MainActivity>,script:String):String {
        val latch=CountDownLatch(1);var result="null"
        scenario.onActivity { a->a.editorForTest()?.evaluateJavascript(script){result=it;latch.countDown()}?:latch.countDown() }
        assertTrue("WebView callback timed out",latch.await(10,TimeUnit.SECONDS));return result
    }
    private fun await(scenario:ActivityScenario<MainActivity>,condition:String) {
        val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(30)
        while(System.nanoTime()<until){if(js(scenario,condition)=="true")return;Thread.sleep(100)}
        fail("Editor condition timed out: $condition")
    }
    private fun screenshot(app:CNoteApp,name:String) {
        Thread.sleep(200)
        val bitmap=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(app.getExternalFilesDir(null),name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
    @Test fun realDiffRequiresChoicesAndRejectsStaleSnapshotBeforeSaving() {
        val app=ApplicationProvider.getApplicationContext<CNoteApp>();val repo=app.repository
        val local="<h1>共同</h1><p>本地</p><p>中间</p><p>本地尾部</p>"
        val cloud="<h1>共同</h1><p>云端</p><p>中间</p><p>云端尾部</p>"
        val note=repo.db.create("Diff Test","");repo.db.saveHtml(note.id,"",local);repo.db.conflict(note.id,cloud,"cloud-v2");repo.refreshList()
        try { ActivityScenario.launch(MainActivity::class.java).use { scenario->
            await(scenario,"!!window.cnote");js(scenario,"cnote.action('open',{id:'${note.id}',title:'Diff Test'})")
            await(scenario,"document.querySelectorAll('.conflict-change').length===2&&document.getElementById('dialog').open")
            assertEquals("true",js(scenario,"document.getElementById('dialog-actions').lastElementChild.disabled"));screenshot(app,"conflict-diff.png")
            js(scenario,"document.querySelectorAll('.conflict-change')[0].querySelectorAll('button')[1].click();document.querySelectorAll('.conflict-change')[1].querySelectorAll('button')[0].click()")
            assertEquals("true",js(scenario,"document.querySelector('.conflict-preview').innerHTML==='<h1>共同</h1><p>云端</p><p>中间</p><p>本地尾部</p>'"))
            repo.db.saveHtml(note.id,"","<p>更新后的本机草稿</p>")
            js(scenario,"document.getElementById('dialog-actions').lastElementChild.click()")
            await(scenario,"document.getElementById('dialog-body').textContent.includes('本地笔记已更新')")
            assertTrue(repo.db.get(note.id)!!.conflict);assertEquals("<p>更新后的本机草稿</p>",repo.db.get(note.id)!!.html)
            js(scenario,"document.getElementById('dialog-actions').firstElementChild.click()")
            await(scenario,"!document.getElementById('dialog').open")
            js(scenario,"cnote.action('review',{id:'${note.id}'})")
            await(scenario,"document.querySelectorAll('.conflict-change').length===1&&document.getElementById('dialog').open")
            js(scenario,"document.querySelector('.conflict-change button').click();document.getElementById('dialog-actions').lastElementChild.click()")
            await(scenario,"document.getElementById('dialog-title').textContent==='已应用你的选择'")
            val resolved=repo.db.get(note.id)!!;assertFalse(resolved.conflict);assertTrue(resolved.dirty);assertEquals("cloud-v2",resolved.etag);assertEquals(cloud,resolved.baseline);assertEquals("<p>更新后的本机草稿</p>",resolved.html)
            assertEquals("true",js(scenario,"document.getElementById('dialog-body').textContent.includes('差异 1：保留本地')"))
            js(scenario,"document.getElementById('dialog-actions').firstElementChild.click()")
        } }finally { repo.db.remove(note.id);repo.refreshList() }
    }
    @Test fun imageCopyUsesSystemUriAndCutWaitsForSuccessAndSupportsUndoAndPaste() {
        val app=ApplicationProvider.getApplicationContext<CNoteApp>();val repo=app.repository
        val note=repo.db.create("Image Test","");repo.refreshList();var previous:android.content.ClipData?=null
        try { ActivityScenario.launch(MainActivity::class.java).use { scenario->
            await(scenario,"!!window.cnote");js(scenario,"cnote.action('open',{id:'${note.id}',title:'Image Test'})")
            await(scenario,"document.getElementById('editor')?.contentEditable==='true'")
            scenario.onActivity { previous=it.getSystemService(ClipboardManager::class.java).primaryClip }
            js(scenario,"(()=>{const c=document.createElement('canvas');c.width=120;c.height=80;c.getContext('2d').fillRect(0,0,120,80);window.imageData=c.toDataURL();const e=document.getElementById('editor');e.innerHTML='<p>正文</p><p><img alt=\"图片\" src=\"'+imageData+'\"></p>';e.dispatchEvent(new Event('input',{bubbles:true}));e.querySelector('img').dispatchEvent(new PointerEvent('pointerdown',{bubbles:true,cancelable:true,pointerType:'touch'}));})()")
            await(scenario,"!document.getElementById('image-actions').hidden")
            js(scenario,"document.querySelector('[data-image-action=view]').click()")
            assertEquals("true",js(scenario,"document.querySelector('dialog.image-dialog').open"));screenshot(app,"image-preview.png")
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() };await(scenario,"!document.getElementById('dialog').open")
            js(scenario,"document.querySelector('[data-image-action=copy]').click()")
            await(scenario,"document.getElementById('status').textContent.includes('图片已复制')")
            scenario.onActivity { activity->
                val clip=activity.getSystemService(ClipboardManager::class.java).primaryClip!!
                val uri=clip.getItemAt(0).uri!!;assertEquals("content",uri.scheme);assertEquals("image/png",activity.contentResolver.getType(uri))
                val bitmap=activity.contentResolver.openInputStream(uri)!!.use(BitmapFactory::decodeStream)!!
                assertEquals(120,bitmap.width);assertEquals(80,bitmap.height);bitmap.recycle()
            }
            js(scenario,"document.querySelector('[data-image-action=cut]').click()")
            await(scenario,"document.querySelectorAll('#editor img').length===0")
            js(scenario,"document.execCommand('undo')");assertEquals("true",js(scenario,"document.querySelectorAll('#editor img').length===1"))
            js(scenario,"(()=>{const e=document.getElementById('editor');e.focus();const r=document.createRange();r.selectNodeContents(e);r.collapse(false);getSelection().removeAllRanges();getSelection().addRange(r);cnote.action('paste_image');})()")
            await(scenario,"document.querySelectorAll('#editor img').length===2")
            js(scenario,"cnote.flushAll()")
            val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
            while(System.nanoTime()<until&&repo.db.get(note.id)!!.html.split("<img").size<3)Thread.sleep(50)
            assertTrue(repo.db.get(note.id)!!.html.split("<img").size>=3)
            assertFalse(repo.db.get(note.id)!!.html.contains("image-actions"))
            scenario.onActivity { activity->val clipboard=activity.getSystemService(ClipboardManager::class.java);if(previous!=null)clipboard.setPrimaryClip(previous!!) else if(Build.VERSION.SDK_INT>=28)clipboard.clearPrimaryClip() }
        } }finally { repo.db.remove(note.id);repo.refreshList() }
    }
}
