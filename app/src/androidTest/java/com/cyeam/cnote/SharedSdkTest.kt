package com.cyeam.cnote

import android.app.Activity
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class SharedSdkTest {
    private fun evaluate(activity:Activity,web:android.webkit.WebView,code:String):String {
        val result=AtomicReference<String>();val done=CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { web.evaluateJavascript(code){value->result.set(value);done.countDown()} }
        assertTrue("JavaScript callback",done.await(10,TimeUnit.SECONDS));return result.get()
    }
    private fun script(scenario:ActivityScenario<MainActivity>,code:String):String {
        var activity:MainActivity?=null;scenario.onActivity { activity=it };return evaluate(activity!!,activity!!.editorForTest()!!,code)
    }
    private fun await(scenario:ActivityScenario<MainActivity>,expression:String) {
        val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20)
        while(System.nanoTime()<until){if(script(scenario,expression)=="true")return;Thread.sleep(100)}
        fail("Timed out: $expression")
    }
    private fun featureScript(activity:SharedActivity,code:String)=evaluate(activity,activity.sharedWebForTest()!!,code)
    private fun awaitFeature(activity:SharedActivity,expression:String) {
        val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20)
        while(System.nanoTime()<until){if(featureScript(activity,expression)=="true")return;Thread.sleep(100)}
        fail("Timed out: $expression")
    }
    private fun clickMenuText(text:String) {
        fun find(node:AccessibilityNodeInfo?):AccessibilityNodeInfo? {
            if(node==null)return null;if(node.text?.toString()==text)return node
            for(i in 0 until node.childCount)find(node.getChild(i))?.let { return it };return null
        }
        val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
        while(System.nanoTime()<until){
            var node=find(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)
            if(node!=null){while(!node!!.isClickable&&node.parent!=null)node=node.parent;assertTrue(node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK));return};Thread.sleep(100)
        };fail("Menu item missing: $text")
    }
    @Test fun nativeNotesUseSharedCoreAndKeepTheirEditorThroughRecreation() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val marker="SDK-"+System.nanoTime()
        ActivityScenario.launch(MainActivity::class.java).use { scenario->
            await(scenario,"typeof cnote==='object' && document.readyState==='complete'")
            assertEquals("true",script(scenario,"!window.cnoteMobile && !document.getElementById('mobile-tabs')"))
            script(scenario,"cnote.action('new')")
            script(scenario,"document.querySelector('#dialog input').value=${JSONObject.quote(marker)};document.querySelector('#dialog-actions button.primary').click()")
            await(scenario,"document.getElementById('document').hidden===false && document.getElementById('editor')?.isContentEditable===true")
            script(scenario,"(()=>{const original=cnote.receive;cnote.receive=e=>{if(e.type==='saved')window.nativeSavedId=e.id;if(e.type==='load')window.lastNativeLoad=e;original(e)}})()")
            script(scenario,"(()=>{const e=document.getElementById('editor');e.innerHTML='<h2>SDK 共享笔记</h2><p>$marker</p>';e.dispatchEvent(new Event('input',{bubbles:true}));cnote.flushAll()})()")
            await(scenario,"!!window.nativeSavedId")
            (context.applicationContext as CNoteApp).shared.post(JSONObject().put("type","open").put("id",script(scenario,"window.nativeSavedId").trim('"')).put("cached",false).toString())
            await(scenario,"window.lastNativeLoad?.html.includes('$marker')===true")
            assertTrue(File(context.filesDir,"shared-core/notes.db").isFile)
            scenario.recreate()
            await(scenario,"document.getElementById('editor')?.textContent.includes('$marker')===true")
        }
    }
    @Test fun cnoteDropdownSwitchesFeaturesAndReturnsToTheNativeNotes() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario->
            await(scenario,"typeof cnote==='object' && document.readyState==='complete'")
            var original:android.webkit.WebView?=null
            scenario.onActivity { activity->original=activity.editorForTest();val title=activity.window.decorView.findViewWithTag<TextView>("workspace-switcher");assertEquals("CNote",title.text.toString());title.performClick() }
            val monitor=instrumentation.addMonitor(SharedActivity::class.java.name,null,false)
            clickMenuText("作业")
            val feature=instrumentation.waitForMonitorWithTimeout(monitor,10_000) as? SharedActivity?:error("Feature activity did not open")
            awaitFeature(feature,"typeof cnoteMobile==='object' && cnoteTools.workspace===4")
            assertEquals("true",featureScript(feature,"getComputedStyle(document.getElementById('mobile-tabs')).display==='none' && getComputedStyle(document.querySelector('header')).display==='none'"))
            instrumentation.runOnMainSync { val selector=feature.window.decorView.findViewWithTag<WorkspaceSelector>("workspace-switcher");assertEquals("作业",selector.text.toString());assertEquals(4,selector.value);selector.performClick() };clickMenuText("OneDrive")
            awaitFeature(feature,"cnoteTools.workspace===5 && !document.getElementById('drive-tool').hidden")
            instrumentation.runOnMainSync { val selector=feature.window.decorView.findViewWithTag<WorkspaceSelector>("workspace-switcher");assertEquals("OneDrive",selector.text.toString());assertEquals(5,selector.value);selector.performClick() };clickMenuText("CNote")
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertSame(original,it.editorForTest());assertTrue(it.window.decorView.findViewWithTag<TextView>("workspace-switcher").isShown) }
            instrumentation.removeMonitor(monitor)
        }
    }
    @Test fun homeworkAttachmentsSaveInPublicDownloadsWithoutOverwriting() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        if(android.os.Build.VERSION.SDK_INT<29)return
        val name="2026-11-18_作业下载测试_${System.nanoTime()}_图一.png"
        val created=mutableListOf<android.net.Uri>()
        try {
            val first=android.net.Uri.parse(HomeworkDownloads.save(context,name,"image/png",byteArrayOf(1,2,3)));created+=first
            val second=android.net.Uri.parse(HomeworkDownloads.save(context,name,"image/png",byteArrayOf(4,5,6)));created+=second
            assertNotEquals(first,second)
            assertArrayEquals(byteArrayOf(1,2,3),context.contentResolver.openInputStream(first)!!.use { it.readBytes() })
            assertArrayEquals(byteArrayOf(4,5,6),context.contentResolver.openInputStream(second)!!.use { it.readBytes() })
            context.contentResolver.query(first,arrayOf(android.provider.MediaStore.Downloads.RELATIVE_PATH,android.provider.MediaStore.Downloads.DISPLAY_NAME,android.provider.MediaStore.Downloads.IS_PENDING),null,null,null)!!.use { cursor->
                assertTrue(cursor.moveToFirst());assertEquals("Download/CNote/",cursor.getString(0));assertEquals(name,cursor.getString(1));assertEquals(0,cursor.getInt(2))
            }
        }finally {created.forEach { context.contentResolver.delete(it,null,null) }}
    }
    @Test fun homeworkAudioDownloadUsesTheHostAndPublicDownloads() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as CNoteApp
        if(android.os.Build.VERSION.SDK_INT<29)return
        var saved:android.net.Uri?=null
        try {
            ActivityScenario.launch(SharedActivity::class.java).use { scenario->
                var activity:SharedActivity?=null;scenario.onActivity {activity=it};val feature=activity!!
                awaitFeature(feature,"typeof cnoteMobile==='object' && !!document.getElementById('trello-download-homework')")
                featureScript(feature,"(()=>{const original=cnoteMobile.receive;cnoteMobile.receive=e=>{if(e.type==='trello'&&e.result.request===98766)window.homeworkDownloadResult=e.result;original(e)}})()")
                val name="2026-11-18_英语朗读测试_${System.nanoTime()}_音频一.m4a"
                val bytes=InstrumentationRegistry.getInstrumentation().context.assets.open("homework-silence.m4a").use { it.readBytes() }
                context.shared.events.trySend(JSONObject().put("type","trello_media").put("request",98766).put("op","download_homework_attachment").put("name",name).put("data",JSONObject().put("mime","audio/mp4").put("base64",android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP))))
                awaitFeature(feature,"!!window.homeworkDownloadResult")
                assertEquals("true",featureScript(feature,"window.homeworkDownloadResult.data?.saved===true"))
                saved=android.net.Uri.parse(featureScript(feature,"window.homeworkDownloadResult.data.path").trim('"'))
                context.contentResolver.query(saved!!,arrayOf(android.provider.MediaStore.Downloads.RELATIVE_PATH,android.provider.MediaStore.Downloads.DISPLAY_NAME,android.provider.MediaStore.Downloads.MIME_TYPE),null,null,null)!!.use { cursor->
                    assertTrue(cursor.moveToFirst());assertEquals("Download/CNote/",cursor.getString(0));assertEquals(name,cursor.getString(1));assertEquals("audio/mp4",cursor.getString(2))
                }
                assertArrayEquals(bytes,context.contentResolver.openInputStream(saved!!)!!.use { it.readBytes() })
            }
        }finally {saved?.let { context.contentResolver.delete(it,null,null) }}
    }
    @Test fun sharedVaultRejectsTamperingAndRestoresAcrossInstances() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val vault=com.cyeam.cnote.sdk.SecureVault(context);val key="instrumentation-test-secret"
        try {
            vault.set(key,"test secret");assertEquals("test secret",com.cyeam.cnote.sdk.SecureVault(context).get(key))
            val prefs=context.getSharedPreferences("shared-core-vault",android.content.Context.MODE_PRIVATE)
            val bytes=android.util.Base64.decode(prefs.getString(key,null)!!,android.util.Base64.NO_WRAP);bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte()
            assertTrue(prefs.edit().putString(key,android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP)).commit());assertThrows(Exception::class.java){vault.get(key)}
        }finally {vault.set(key,null)}
    }
    @Test fun trelloImageRecognitionUsesTheAndroidHost() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as CNoteApp
        ActivityScenario.launch(SharedActivity::class.java).use { scenario->
            var activity:SharedActivity?=null;scenario.onActivity {activity=it};val feature=activity!!
            awaitFeature(feature,"typeof cnoteMobile==='object' && cnoteTools.workspace===4")
            featureScript(feature,"(()=>{const original=cnoteMobile.receive;cnoteMobile.receive=e=>{if(e.type==='trello'&&e.result.request===98765)window.ocrResult=e.result;original(e)}})()")
            val bitmap=android.graphics.Bitmap.createBitmap(1200,240,android.graphics.Bitmap.Config.ARGB_8888)
            val canvas=android.graphics.Canvas(bitmap);canvas.drawColor(android.graphics.Color.WHITE)
            canvas.drawText("CNote 12345",30f,150f,android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color=android.graphics.Color.BLACK;textSize=100f })
            val bytes=java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }.toByteArray();bitmap.recycle()
            context.shared.events.trySend(JSONObject().put("type","trello_media").put("request",98765).put("op","recognize_image").put("data",JSONObject().put("mime","image/png").put("base64",android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP))))
            awaitFeature(feature,"!!window.ocrResult");assertEquals("true",featureScript(feature,"!!window.ocrResult.data?.text.includes('12345')"))
        }
    }
}
