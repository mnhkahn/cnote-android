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
            clickMenuText("Trello")
            val feature=instrumentation.waitForMonitorWithTimeout(monitor,10_000) as? SharedActivity?:error("Feature activity did not open")
            awaitFeature(feature,"typeof cnoteMobile==='object' && cnoteTools.workspace===4")
            assertEquals("true",featureScript(feature,"getComputedStyle(document.getElementById('mobile-tabs')).display==='none' && getComputedStyle(document.querySelector('header')).display==='none'"))
            instrumentation.runOnMainSync { val selector=feature.window.decorView.findViewWithTag<WorkspaceSelector>("workspace-switcher");assertEquals("Trello",selector.text.toString());assertEquals(4,selector.value);selector.performClick() };clickMenuText("OneDrive")
            awaitFeature(feature,"cnoteTools.workspace===5 && !document.getElementById('drive-tool').hidden")
            instrumentation.runOnMainSync { val selector=feature.window.decorView.findViewWithTag<WorkspaceSelector>("workspace-switcher");assertEquals("OneDrive",selector.text.toString());assertEquals(5,selector.value);selector.performClick() };clickMenuText("CNote")
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertSame(original,it.editorForTest());assertTrue(it.window.decorView.findViewWithTag<TextView>("workspace-switcher").isShown) }
            instrumentation.removeMonitor(monitor)
        }
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
