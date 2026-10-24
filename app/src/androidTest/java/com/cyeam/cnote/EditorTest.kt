package com.cyeam.cnote

import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class EditorTest {
    private fun js(scenario:ActivityScenario<MainActivity>,script:String):String {
        val latch=CountDownLatch(1);var result="null"
        scenario.onActivity { a->a.editorForTest()?.evaluateJavascript(script){result=it;latch.countDown()}?:latch.countDown() }
        assertTrue("WebView callback timed out",latch.await(10,TimeUnit.SECONDS));return result
    }
    private fun await(scenario:ActivityScenario<MainActivity>,script:String) {
        val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(30)
        while(System.nanoTime()<until){if(js(scenario,script)=="true")return;Thread.sleep(100)}
        fail("Editor condition timed out: $script")
    }
    private fun contextFile(app:CNoteApp,name:String)=java.io.File(app.getExternalFilesDir(null),name)
    private fun string(raw:String)=JSONArray("[$raw]").getString(0)
    @androidx.test.filters.SdkSuppress(minSdkVersion=31)
    @Test fun systemThemeChangesKeepEditableDraft() {
        val app=ApplicationProvider.getApplicationContext<CNoteApp>()
        val modes=app.getSystemService(android.app.UiModeManager::class.java)
        val automation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity("android.permission.MODIFY_DAY_NIGHT_MODE")
        val original=modes.nightMode
        val note=app.repository.db.create("主题切换","")
        try {
            modes.setNightMode(android.app.UiModeManager.MODE_NIGHT_NO)
            ActivityScenario.launch(MainActivity::class.java).use { scenario->
                await(scenario,"!!window.cnote&&document.documentElement.dataset.theme==='light'")
                js(scenario,"cnote.action('open',{id:'${note.id}',title:'主题切换'})")
                await(scenario,"document.getElementById('editor')?.contentEditable==='true'")
                js(scenario,"window.themeDraft=document.getElementById('editor');themeDraft.innerHTML='<h1>我写的标题</h1><p>切换时保留草稿</p>';themeDraft.dispatchEvent(new Event('input',{bubbles:true}))")
                for((mode,theme,background) in listOf(
                    Triple(android.app.UiModeManager.MODE_NIGHT_YES,"dark","rgb(25, 30, 41)"),
                    Triple(android.app.UiModeManager.MODE_NIGHT_NO,"light","rgb(255, 255, 255)")
                )) {
                    modes.setNightMode(mode)
                    await(scenario,"document.documentElement.dataset.theme==='$theme'&&getComputedStyle(document.body).backgroundColor==='$background'")
                    assertEquals("true",js(scenario,"themeDraft===document.getElementById('editor')&&themeDraft.contentEditable==='true'&&themeDraft.querySelector('h1').textContent==='我写的标题'"))
                    scenario.onActivity { activity->
                        val night=activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
                        assertEquals(if(theme=="dark")android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO,night)
                    }
                }
            }
        } finally {
            modes.setNightMode(original)
            automation.dropShellPermissionIdentity()
            app.repository.db.remove(note.id);app.repository.refreshList()
        }
    }
    @Test fun localEditorAutosavesSwitchesWithoutSerializationAndRestoresAfterRecreation() {
        val app=ApplicationProvider.getApplicationContext<CNoteApp>();val repo=app.repository
        val a=repo.db.create("测试笔记 A","");val b=repo.db.create("测试笔记 B","")
        try { ActivityScenario.launch(MainActivity::class.java).use { scenario->
            await(scenario,"!!window.cnote")
            js(scenario,"cnote.action('open',{id:'${a.id}',title:'测试笔记 A'})")
            await(scenario,"document.getElementById('editor')?.contentEditable==='true'")
            js(scenario,"document.getElementById('editor').innerHTML='<p>离线自动保存 ✓</p>';document.getElementById('editor').dispatchEvent(new Event('input',{bubbles:true}))")
            val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
            while(System.nanoTime()<until&&repo.db.get(a.id)!!.html!="<p>离线自动保存 ✓</p>")Thread.sleep(50)
            assertEquals("<p>离线自动保存 ✓</p>",repo.db.get(a.id)!!.html)
            js(scenario,"window.retained=document.getElementById('editor');cnote.action('open',{id:'${b.id}',title:'测试笔记 B'})")
            await(scenario,"document.getElementById('editor')?.contentEditable==='true'&&document.getElementById('editor')!==retained")
            assertEquals("true",js(scenario,"(()=>{const t=performance.now();cnote.action('open',{id:'${a.id}'});window.cachedSwitchMs=performance.now()-t;return retained===document.getElementById('editor')})()"))
            Log.i("CNotePerf","cached_note_switch_ms="+js(scenario,"cachedSwitchMs"))
            assertEquals("离线自动保存 ✓",string(js(scenario,"document.getElementById('editor').textContent")))
            val clean=string(js(scenario,"cnote.sanitize('<script>evil()</script><img src=x onerror=evil()><a href=javascript:evil()>x</a><input type=checkbox checked><table><tr><td>A</td></tr></table>')"))
            assertFalse(clean.contains("onerror"));assertFalse(clean.contains("javascript:"));assertFalse(clean.contains("<script"));assertTrue(clean.contains("checked"));assertTrue(clean.contains("<td>A</td>"))
            // Retain edits in A while switching away before its 200 ms disk snapshot.
            assertEquals("true",js(scenario,"(()=>{const e=document.getElementById('editor');e.innerHTML='<p>快速切换后的草稿</p>';e.dispatchEvent(new Event('input',{bubbles:true}));cnote.action('open',{id:'${b.id}'});cnote.action('open',{id:'${a.id}'});return e===document.getElementById('editor')&&e.textContent==='快速切换后的草稿'})()"))
            await(scenario,"document.getElementById('status').textContent==='已保存到本机'")
            val persisted=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
            while(System.nanoTime()<persisted&&repo.db.get(a.id)!!.html!="<p>快速切换后的草稿</p>")Thread.sleep(50)
            assertEquals("<p>快速切换后的草稿</p>",repo.db.get(a.id)!!.html)
            // Measure warm switches separately from startup, IPC callbacks and disk/network time.
            val timings=js(scenario,"(()=>{const times=[];for(let i=0;i<40;i++){const t=performance.now();cnote.action('open',{id:i%2?'${a.id}':'${b.id}'});times.push(performance.now()-t);}times.sort((a,b)=>a-b);return {p50:times[20],p95:times[38]}})()")
            Log.i("CNotePerf","warm_note_switch_ms=$timings")
            js(scenario,"cnote.receive({type:'load',id:'${a.id}',title:'随手记下来',html:'<p>让记录跟上想法。</p><h2>今天的小计划</h2><div><input type=checkbox checked><label>把灵感记下来</label></div><div><input type=checkbox><label>留一点时间阅读</label></div><p>本地自动保存，联网后同步到 OneDrive。</p>'})")
            Thread.sleep(300)
            val bitmap=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            contextFile(app,"editor.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
            js(scenario,"document.getElementById('editor').innerHTML='<p>重建前尚未保存的输入</p>';document.getElementById('editor').dispatchEvent(new Event('input',{bubbles:true}))")
            scenario.recreate();await(scenario,"!!window.cnote")
            assertEquals("true",js(scenario,"retained===document.getElementById('editor')"))
            js(scenario,"cnote.action('open',{id:'${a.id}',title:'测试笔记 A'})")
            await(scenario,"document.getElementById('editor')?.textContent==='重建前尚未保存的输入'")
            val restored=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
            while(System.nanoTime()<restored&&repo.db.get(a.id)!!.html!="<p>重建前尚未保存的输入</p>")Thread.sleep(50)
            assertEquals("<p>重建前尚未保存的输入</p>",repo.db.get(a.id)!!.html)
            assertEquals(MainActivity.EDITOR_URL,string(js(scenario,"location.href")))
        } }finally { repo.db.remove(a.id);repo.db.remove(b.id);repo.refreshList() }
    }
    @Test fun changedInactiveCacheReloadsAndPendingDraftIsProtected() {
        val app=ApplicationProvider.getApplicationContext<CNoteApp>();val repo=app.repository
        val a=repo.db.create("缓存 A","");val b=repo.db.create("缓存 B","")
        try { ActivityScenario.launch(MainActivity::class.java).use { scenario->
            await(scenario,"!!window.cnote")
            js(scenario,"cnote.action('open',{id:'${a.id}',title:'缓存 A'})")
            await(scenario,"document.getElementById('editor')?.contentEditable==='true'")
            js(scenario,"window.cachedA=document.getElementById('editor');cnote.action('open',{id:'${b.id}',title:'缓存 B'})")
            await(scenario,"document.getElementById('editor')!==cachedA&&document.getElementById('editor')?.contentEditable==='true'")
            js(scenario,"window.cachedB=document.getElementById('editor')")
            repo.db.saveHtml(a.id,"","<p>后台更新的正文</p>")
            js(scenario,"cnote.receive({type:'invalidate',id:'${a.id}'})")
            assertEquals("true",js(scenario,"document.getElementById('editor')===cachedB"))
            js(scenario,"cnote.action('open',{id:'${a.id}',title:'缓存 A'})")
            await(scenario,"document.getElementById('editor')?.textContent==='后台更新的正文'&&document.getElementById('editor').contentEditable==='true'")
            assertEquals("true",js(scenario,"(()=>{const e=document.getElementById('editor');e.innerHTML='<p>本机尚未保存的新输入</p>';e.dispatchEvent(new Event('input',{bubbles:true}));cnote.receive({type:'invalidate',id:'${a.id}'});return e.contentEditable==='true'&&e.textContent==='本机尚未保存的新输入'})()"))
            val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
            while(System.nanoTime()<until&&repo.db.get(a.id)!!.html!="<p>本机尚未保存的新输入</p>")Thread.sleep(50)
            assertEquals("<p>本机尚未保存的新输入</p>",repo.db.get(a.id)!!.html)
        } }finally { repo.db.remove(a.id);repo.db.remove(b.id);repo.refreshList() }
    }
}
