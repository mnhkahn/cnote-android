package com.cyeam.cnote

import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.content.MutableContextWrapper
import android.net.Uri
import android.os.Bundle
import android.print.PrintManager
import android.webkit.*
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.PopupMenu
import android.graphics.Typeface
import android.view.Gravity
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Native feature host for Trello and OneDrive. No note or cloud business logic lives here. */
class SharedActivity:ComponentActivity() {
    private val vm:SharedViewModel by viewModels()
    private val app get()=application as CNoteApp
    private var fileCallback:ValueCallback<Array<Uri>>?=null
    private var updateDialog:AlertDialog?=null
    private var printing=false
    private var workspace=4
    private lateinit var featureSelector:WorkspaceSelector
    private val picker=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result->
        val data=result.data
        val uris=if(result.resultCode==RESULT_OK) data?.clipData?.let { clip->Array(clip.itemCount){clip.getItemAt(it).uri} } ?: data?.data?.let { arrayOf(it) } else null
        fileCallback?.onReceiveValue(uris);fileCallback=null
    }
    private val exporter=registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri->
        val data=vm.export;val request=vm.exportRequest;vm.export=null;vm.exportRequest=null
        lifecycleScope.launch {
            val result=runCatching { if(uri!=null&&data!=null)withContext(Dispatchers.IO){contentResolver.openOutputStream(uri,"wt")!!.use { it.write(data.second) }} }
            if(request!=null)vm.reply(request,JSONObject().put("saved",uri!=null&&result.isSuccess),result.exceptionOrNull()?.message)
            if(result.isFailure)toast(getString(R.string.export_failed))
        }
    }
    private val imagePicker=registerForActivityResult(ActivityResultContracts.GetContent()) { uri->
        val target=vm.imageTarget;vm.imageTarget=null
        if(uri!=null)lifecycleScope.launch {
            try {
                val data=withContext(Dispatchers.IO){val mime=contentResolver.getType(uri).orEmpty();require(mime in setOf("image/png","image/jpeg","image/webp","image/gif","image/bmp")){getString(R.string.unsupported_image_format)};val bytes=contentResolver.openInputStream(uri)!!.use { it.readBytesBounded(8*1024*1024) };"data:$mime;base64,"+android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP)}
                if(target==vm.selected)js("cnoteMobile.insertImage(${JSONObject.quote(data)})")
            }catch(t:Exception){toast(t.message?:getString(R.string.image_read_failed))}
        }
    }
    private val installPermission=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val pending=vm.pendingInstall;vm.pendingInstall=false
        if(BuildConfig.SIDELOAD_UPDATES&&pending&&packageManager.canRequestPackageInstalls())js("cnote.action('install_update')")
    }
    @Suppress("SetJavaScriptEnabled") override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val dark=resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK==android.content.res.Configuration.UI_MODE_NIGHT_YES
        val background=android.graphics.Color.parseColor(if(dark)"#151A24" else "#F5F7FB")
        workspace=savedInstanceState?.getInt("workspace")?:intent.getIntExtra("workspace",4)
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(background) };setContentView(root)
        val top=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL;setPadding(dp(20),dp(8),dp(12),dp(8)) }
        featureSelector=WorkspaceSelector(this,workspace,26f) { selected ->
            if (selected == 0) {
                returnToNotes()
            } else {
                app.lastWorkspace = selected
                selectFeature(selected)
            }
        }
        top.addView(featureSelector,LinearLayout.LayoutParams(-2,dp(56)))
        root.addView(top)
        ViewCompat.setOnApplyWindowInsetsListener(root){view,insets->val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime());view.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets}
        val loader=WebViewAssetLoader.Builder().addPathHandler("/assets/",WebViewAssetLoader.AssetsPathHandler(applicationContext)).build()
        val existing=vm.web
        val web=existing?:WebView(MutableContextWrapper(this)).also { vm.web=it }
        (web.context as MutableContextWrapper).baseContext=this
        (web.parent as? android.view.ViewGroup)?.removeView(web);root.addView(web,LinearLayout.LayoutParams(-1,0,1f))
        web.settings.apply { javaScriptEnabled=true;domStorageEnabled=false;allowFileAccess=false;allowContentAccess=true;mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW;setSupportMultipleWindows(false);javaScriptCanOpenWindowsAutomatically=false;setGeolocationEnabled(false) }
        val model=vm
        web.addJavascriptInterface(object {
            @JavascriptInterface fun postMessage(raw:String) {
                model.bridge(raw)
            }
        },"cnoteHost")
        web.webViewClient=object:WebViewClient(){
            override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse? {
                if(request.url.scheme=="https"&&request.url.host=="appassets.androidplatform.net")return loader.shouldInterceptRequest(request.url)
                // Feature API traffic is native. Only HTTPS images may load from the document.
                return if(request.url.scheme=="https"&&!request.isForMainFrame)null else WebResourceResponse("text/plain","utf-8",403,"Forbidden",emptyMap(),null)
            }
            override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean { if(request.url.toString()==URL)return false;if(request.isForMainFrame)openLink(request.url.toString());return true }
        }
        web.webChromeClient=object:WebChromeClient(){
            override fun onShowFileChooser(view:WebView,callback:ValueCallback<Array<Uri>>,params:FileChooserParams):Boolean {
                fileCallback?.onReceiveValue(null);fileCallback=callback
                return try { picker.launch(params.createIntent().addCategory(Intent.CATEGORY_OPENABLE));true }catch(_:Exception){fileCallback=null;callback.onReceiveValue(null);true}
            }
        }
        if(existing==null)web.loadUrl(URL) else selectFeature(workspace)
        lifecycleScope.launch { vm.runtime.events.flow.collect { event->effect(event) } }
        if (BuildConfig.SIDELOAD_UPDATES) {
            lifecycleScope.launch { app.updater.state.collect { state->
                if(updateDialog?.isShowing==true)renderUpdate()
                if(state.stage==UpdateStage.READY&&vm.promptedUpdate!=state.id){vm.promptedUpdate=state.id;showUpdate()}
            } }
            app.updater.checkForUpdate()
        }
        onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true){override fun handleOnBackPressed(){returnToNotes()}})
    }
    private fun returnToNotes() {
        app.lastWorkspace = 0
        finish()
    }
    private fun dp(value:Int)=(value*resources.displayMetrics.density+.5f).toInt()
    private fun selectFeature(index:Int){workspace=index;featureSelector.updateValue(index);js("cnoteMobile.featureMode($index)")}
    override fun onSaveInstanceState(outState:Bundle){outState.putInt("workspace",workspace);super.onSaveInstanceState(outState)}
    private fun js(script:String){if(!vm.ready){vm.scripts.addLast(script);return};vm.web?.evaluateJavascript(script,null)}
    private fun receive(e:JSONObject)=js("cnoteMobile.receive($e)")
    private fun effect(e:JSONObject) {
        when(e.optString("type")) {
            "mobile_ready"->{
                vm.ready=true
                while(vm.scripts.isNotEmpty())js(vm.scripts.removeFirst())
                if (!BuildConfig.SIDELOAD_UPDATES) js("document.getElementById('mobile-update')?.remove()")
                selectFeature(workspace)
                app.analytics.screen(if(workspace==4)"trello" else "onedrive")
            }
            "preload","load","selected","saved","save_failed","close_failed","closed"->Unit
            "state"->{vm.selected=e.optString("selected").takeIf { it.isNotEmpty()&&it!="null" };receive(e)}
            "dialog"->if(e.optString("kind") in listOf("login","login_done"))receive(e)
            "trello_media"->media(e)
            "drive"->{receive(e);val result=e.optJSONObject("result")?.optJSONObject("data");result?.optJSONArray("paths")?.let { paths->
                lifecycleScope.launch {
                    try {
                        val files=withContext(Dispatchers.IO){
                            val root=File(filesDir,"shared-downloads").canonicalFile
                            val inputs=(0 until paths.length()).map { i->File(paths.getString(i)).canonicalFile.also { require(it.toPath().startsWith(root.toPath())) } }
                            if(inputs.none { it.isDirectory })inputs else {
                                val folder=File(cacheDir,"clipboard_images").apply { mkdirs() };val zip=File(folder,"OneDrive-${UUID.randomUUID()}.zip")
                                java.util.zip.ZipOutputStream(zip.outputStream().buffered()).use { output->
                                    inputs.forEach { input->input.walkTopDown().forEach { file->
                                        val entry=input.name+(if(file==input)"" else "/"+file.relativeTo(input).invariantSeparatorsPath)+(if(file.isDirectory)"/" else "")
                                        output.putNextEntry(java.util.zip.ZipEntry(entry));if(file.isFile)file.inputStream().use { it.copyTo(output) };output.closeEntry()
                                    } }
                                };listOf(zip)
                            }
                        }
                        if(result.optBoolean("open")&&files.size==1)openFile(files.first()) else share(files)
                    }catch(t:Exception){toast(t.message?:getString(R.string.download_open_failed))}
                }
            }}
            "link"->openLink(e.optString("url"))
            "image"->{vm.imageTarget=vm.selected;imagePicker.launch("image/*")}
            "image_clipboard"->lifecycleScope.launch { val result=runCatching { ImageClipboard.copy(applicationContext,e.getString("src")) };receive(JSONObject().put("type","image_clipboard_result").put("token",e.getLong("token")).put("ok",result.isSuccess).put("message",result.exceptionOrNull()?.message.orEmpty())) }
            "export"->{vm.export=(e.getString("title")+".html") to ("<!doctype html><meta charset=utf-8>"+e.getString("html")).toByteArray();vm.exportRequest=null;exporter.launch(vm.export!!.first)}
            "print"->if(!printing){printing=true;val view=vm.web;if(view!=null)(getSystemService(PRINT_SERVICE) as PrintManager).print("CNote",view.createPrintDocumentAdapter("CNote"),null);printing=false}
            "check_update"->if (BuildConfig.SIDELOAD_UPDATES) {app.updater.checkForUpdate(true);showUpdate()}
            "install_update"->if (BuildConfig.SIDELOAD_UPDATES) installUpdate()

            "close"->returnToNotes()
            "refresh_workspace"->vm.post("{\"type\":\"refresh\"}")
            "sidebar"->js("cnoteMobile.back()")
            "guard","tool_guard","workspace"->Unit
            "error"->{receive(e);if(!vm.ready)toast(e.optString("message"))}
            else->receive(e)
        }
    }
    private var downloadsPermission:kotlinx.coroutines.CompletableDeferred<Boolean>?=null
    private val requestDownloadsPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted->downloadsPermission?.complete(granted);downloadsPermission=null }
    private suspend fun ensureDownloadsPermission(){
        if(android.os.Build.VERSION.SDK_INT>=29||androidx.core.content.ContextCompat.checkSelfPermission(this,android.Manifest.permission.WRITE_EXTERNAL_STORAGE)==android.content.pm.PackageManager.PERMISSION_GRANTED)return
        val pending=kotlinx.coroutines.CompletableDeferred<Boolean>();downloadsPermission=pending
        requestDownloadsPermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if(!pending.await())throw SecurityException(getString(R.string.download_permission_missing))
    }
    private fun media(e:JSONObject) {
        val request=e.getLong("request");val op=e.getString("op")
        lifecycleScope.launch {
            try {
                val data=e.getJSONObject("data");val mime=data.getString("mime")
                val bytes=withContext(Dispatchers.IO){android.util.Base64.decode(data.getString("base64"),android.util.Base64.DEFAULT)}
                when(op) {
                    "copy_image"->{require(mime.startsWith("image/"));ImageClipboard.copy(applicationContext,"data:$mime;base64,"+data.getString("base64"));vm.reply(request)}
                    "download_homework_attachment"->{ensureDownloadsPermission();val path=withContext(Dispatchers.IO){HomeworkDownloads.save(applicationContext,e.getString("name"),mime,bytes)};vm.reply(request,JSONObject().put("saved",true).put("path",path))}
                    "download","download_audio"->{check(vm.export==null){getString(R.string.finish_file_save)};vm.export=(e.optString("name").takeIf { it.isNotBlank()&&it!="null" }?:"attachment") to bytes;vm.exportRequest=request;exporter.launch(vm.export!!.first)}
                    "share_image"->{require(mime.startsWith("image/"));val file=withContext(Dispatchers.IO){File(cacheDir,"clipboard_images").apply { mkdirs() }.let { File(it,UUID.randomUUID().toString()+"."+mime.substringAfter('/').replace(Regex("[^a-zA-Z0-9]"),"")).apply { writeBytes(bytes) } }};share(listOf(file),mime);vm.reply(request,JSONObject().put("shared",true))}
                    "recognize_image"->{require(mime.startsWith("image/"));val bitmap=withContext(Dispatchers.IO){
                        val bounds=android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds=true };android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                        require(bounds.outWidth>0&&bounds.outHeight>0){getString(R.string.invalid_image_file)};var sample=1;while(maxOf(bounds.outWidth,bounds.outHeight)/sample>4096)sample*=2
                        android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,android.graphics.BitmapFactory.Options().apply { inSampleSize=sample })
                    }?:error(getString(R.string.invalid_image_file));val recognizer=TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build());recognizer.process(InputImage.fromBitmap(bitmap,0)).addOnSuccessListener { text->vm.reply(request,JSONObject().put("text",text.text)) }.addOnFailureListener { t->vm.reply(request,error=t.message?:getString(R.string.recognition_failed)) }.addOnCompleteListener { recognizer.close();bitmap.recycle() }}
                }
            }catch(t:Exception){vm.reply(request,error=t.message?:getString(R.string.attachment_failed),code=if(op=="download_homework_attachment"&&t is SecurityException)"download_permission_denied" else null)}
        }
    }
    private fun openFile(file:File) {
        try {val uri=FileProvider.getUriForFile(this,"$packageName.images",file);val intent=Intent(Intent.ACTION_VIEW).setDataAndType(uri,contentResolver.getType(uri)?:"application/octet-stream").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(Intent.createChooser(intent,getString(R.string.open_file)))}
        catch(_:Exception){share(listOf(file))}
    }
    private fun share(files:List<File>,mime:String="*/*") {
        if(files.isEmpty()){toast(getString(R.string.no_files_to_share));return};if(files.size>500){toast(getString(R.string.share_file_limit));return}
        try {
            val uris=ArrayList(files.map { FileProvider.getUriForFile(this,"$packageName.images",it) })
            val intent=Intent(Intent.ACTION_SEND_MULTIPLE).setType(mime).putParcelableArrayListExtra(Intent.EXTRA_STREAM,uris).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData=ClipData.newUri(contentResolver,getString(R.string.cnote_files),uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
            startActivity(Intent.createChooser(intent,getString(R.string.save_or_open_file)))
        }catch(_:Exception){toast(getString(R.string.no_file_handler))}
    }
    private fun openLink(url:String){try{val uri=Uri.parse(url);require(uri.scheme in listOf("https","http","mailto"));startActivity(Intent(Intent.ACTION_VIEW,uri))}catch(_:Exception){toast(getString(R.string.open_link_failed))}}
    private fun renderUpdate() {
        val state=app.updater.state.value
        updateDialog?.setMessage(listOf(getString(R.string.current_version, BuildConfig.VERSION_NAME),state.version.takeIf { it.isNotBlank() }?.let { getString(R.string.new_version, it) },state.message.takeIf { it.isNotBlank() },state.notes.takeIf { it.isNotBlank() },if(state.stage==UpdateStage.DOWNLOADING)getString(R.string.download_percent, state.progress) else null).filterNotNull().joinToString("\n\n"))
        updateDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.text=if(state.stage==UpdateStage.READY)getString(R.string.install_update) else getString(R.string.check_again)
    }
    private fun showUpdate() {
        if(updateDialog?.isShowing==true){renderUpdate();return}
        updateDialog=AlertDialog.Builder(this).setTitle("CNote").setMessage(getString(R.string.checking_updates)).setNegativeButton(getString(R.string.later),null).setNeutralButton(if(app.analytics.enabled())getString(R.string.analytics_off) else getString(R.string.analytics_on)){_,_->app.analytics.enabled(!app.analytics.enabled())}.setPositiveButton(getString(R.string.check_again),null).create().apply {
            setOnDismissListener { updateDialog=null };show()
            getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if(app.updater.state.value.stage==UpdateStage.READY){dismiss();js("cnote.action('install_update')")}else app.updater.checkForUpdate(true)
            }
        }
        renderUpdate()
    }
    private fun installUpdate(){
        if(!packageManager.canRequestPackageInstalls()){vm.pendingInstall=true;installPermission.launch(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:$packageName")));return}
        lifecycleScope.launch { try { startActivity(app.updater.installIntent()) }catch(t:Exception){toast(t.message?:getString(R.string.install_update_failed))} }
    }
    private fun toast(text:String)=Toast.makeText(this,text,Toast.LENGTH_LONG).show()
    override fun onStop(){if(vm.ready)js("cnoteMobile.flush()");super.onStop()}
    override fun onDestroy(){fileCallback?.onReceiveValue(null);fileCallback=null;updateDialog?.dismiss();vm.web?.let { view->(view.parent as? android.view.ViewGroup)?.removeView(view);view.webChromeClient=null;view.webViewClient=WebViewClient();if(!isChangingConfigurations)view.removeJavascriptInterface("cnoteHost");(view.context as MutableContextWrapper).baseContext=applicationContext };super.onDestroy()}
    internal fun sharedWebForTest()=vm.web
    companion object { const val URL="https://appassets.androidplatform.net/assets/cnote/features.html" }
}
