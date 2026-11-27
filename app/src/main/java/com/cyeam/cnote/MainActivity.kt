package com.cyeam.cnote

import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.print.PrintManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

open class MainActivity:ComponentActivity() {
    protected open val useSharedCore=true
    private val coreVm:CoreNotebookViewModel by viewModels()
    private val legacyVm:NotebookViewModel by viewModels()
    private val vm:NotebookSession get()=if(useSharedCore)coreVm else legacyVm
    private val app get()=application as CNoteApp
    private lateinit var root:FrameLayout
    private lateinit var listPane:LinearLayout
    private lateinit var editPane:LinearLayout
    private lateinit var subtitle:TextView
    private lateinit var syncLabel:TextView
    private lateinit var empty:TextView
    private lateinit var count:TextView
    private lateinit var updateBanner:TextView
    private var updateDialog:AlertDialog?=null
    private var updateMessage:TextView?=null
    private var updateProgress:ProgressBar?=null
    private lateinit var adapter:NotesAdapter
    private var web:WebView?=null
    private val ready get()=vm.editorReady
    private var editorVisible=false
    private var search=""
    private var lastNotes:List<Summary>?=null
    private val queued get()=vm.pendingScripts
    private var loginDialog:AlertDialog?=null
    private var exportData:Pair<String,String>?=null
    private var imageNote:String?=null
    private val dark get()=resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES
    private val ink get()=Color.parseColor(if(dark)"#DFE7F3" else "#253047")
    private val bg get()=Color.parseColor(if(dark)"#151A24" else "#F5F7FB")
    private val paper get()=Color.parseColor(if(dark)"#191E29" else "#FFFFFF")
    private val muted get()=Color.parseColor(if(dark)"#A4AFC0" else "#68788E")
    private val blue get()=Color.parseColor(if(dark)"#8DB7FF" else "#2870E8")
    private val dates=DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT)
    private val font by lazy { Typeface.createFromAsset(assets,"editor/bootstrap-icons.ttf") }
    private val installPermission=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val pending=vm.pendingInstall;vm.pendingInstall=false
        if(BuildConfig.SIDELOAD_UPDATES&&pending&&packageManager.canRequestPackageInstalls())action("install_update")
    }
    private val exportLauncher=registerForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri->
        val data=exportData;exportData=null
        if(uri!=null&&data!=null)lifecycleScope.launch(Dispatchers.IO) { try { contentResolver.openOutputStream(uri,"wt")!!.use { it.write(data.second.toByteArray()) };withContext(Dispatchers.Main){toast(getString(R.string.exported)) } }catch(t:Exception){withContext(Dispatchers.Main){toast(getString(R.string.export_failed))}} }
    }
    private val imageLauncher=registerForActivityResult(ActivityResultContracts.GetContent()) { uri->
        val target=imageNote;imageNote=null
        if(uri!=null)lifecycleScope.launch {
            try {
                val data=withContext(Dispatchers.IO) {
                    val mime=contentResolver.getType(uri).orEmpty();require(mime in setOf("image/png","image/jpeg","image/webp","image/gif","image/bmp")) { getString(R.string.supported_images) }
                    val bytes=contentResolver.openInputStream(uri)!!.use { it.readBytesBounded(8*1024*1024) }
                    "data:$mime;base64,"+android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP)
                }
                if(target==vm.state.value.selected)js("window.cnote.insertImage(${JSONObject.quote(data)})")
            }catch(t:Exception){toast(t.message?:getString(R.string.image_read_failed))}
        }
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER)) {
            openFeature(app.lastWorkspace)
        }
        androidx.core.view.WindowCompat.getInsetsController(window,window.decorView).isAppearanceLightNavigationBars=!dark
        root=FrameLayout(this).apply { setBackgroundColor(bg) };setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view,insets->val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime());view.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets }
        buildList();buildEditor();web=vm.editorView
        if(savedInstanceState?.getBoolean("editor_visible")==true)showEditor() else showList()
        if(vm.editorView==null)savedInstanceState?.getString("note_id")?.let { action("open",JSONObject().put("id",it)) }
        lifecycleScope.launch { vm.state.collect { state->
            subtitle.text=if(state.session.id.isEmpty())getString(R.string.local_notes) else "${state.session.name} · OneDrive"
            syncLabel.text=localizeNoteStatus(state.status)
            if(lastNotes!==state.notes){lastNotes=state.notes;filterNotes()}
        } }
        lifecycleScope.launch { repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) { for(event in vm.effects)effect(event) } }
        if (BuildConfig.SIDELOAD_UPDATES) lifecycleScope.launch { app.updater.state.collect { state->
            renderUpdate(state)
            if(state.stage==UpdateStage.READY&&vm.promptedUpdate!=state.id&&lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                vm.promptedUpdate=state.id;showUpdateDialog()
            }
        } }
        onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true){ override fun handleOnBackPressed(){
            val view=web
            if(editorVisible&&ready&&view!=null)view.evaluateJavascript("window.cnote.dismissOverlay()"){handled->if(handled!="true")showList()}
            else if(editorVisible)showList() else if(ready)action("close") else finish()
        } })
        // Let the native list draw first. The one local editor is retained for every note.
        root.post { initializeEditor();if(BuildConfig.SIDELOAD_UPDATES && !BuildConfig.DEBUG)app.updater.checkForUpdate() }
        vm.command("sync")
    }
    private fun dp(value:Int)=(value*resources.displayMetrics.density+.5f).toInt()
    private fun localizeNoteStatus(status: String): String = when (status) {
        "离线模式 · 笔记保存在本机" -> getString(R.string.offline_status)
        "已保存到本机" -> getString(R.string.saved_locally)
        "已处理云端删除，草稿已保存到本机" -> getString(R.string.cloud_deletion_saved)
        else -> status
    }
    private fun label(value:String,size:Float=16f,color:Int=ink)=TextView(this).apply { text=value;textSize=size;setTextColor(color);gravity=Gravity.CENTER_VERTICAL }
    private fun column()=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
    private fun row()=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL }
    private fun round(color:Int,radius:Int=16)=GradientDrawable().apply { setColor(color);cornerRadius=dp(radius).toFloat() }
    private fun icon(code:Int,description:String,click:()->Unit)=Button(this,null,android.R.attr.borderlessButtonStyle).apply {
        text=code.toChar().toString();typeface=font;textSize=21f;setTextColor(blue);contentDescription=description;minWidth=0;minimumWidth=0;setPadding(0,0,0,0);setOnClickListener { click() }
        layoutParams=LinearLayout.LayoutParams(dp(48),dp(48))
    }
    private fun workspaceTitle(size:Float)=WorkspaceSelector(this,0,size) { index->
        if(index==0)showList() else if(ready)action("switch_workspace",JSONObject().put("workspace",index))
        else openFeature(index)
    }.apply { layoutParams=LinearLayout.LayoutParams(-2,-2) }
    private fun openFeature(index: Int) {
        if (index != 4 && index != 5) return
        app.lastWorkspace = index
        startActivity(Intent(this, SharedActivity::class.java).putExtra("workspace", index))
    }
    private fun buildList() {
        listPane=column();root.addView(listPane,FrameLayout.LayoutParams(-1,-1))
        val top=row().apply { setPadding(dp(20),dp(12),dp(10),dp(2)) }
        val headings=column();headings.addView(workspaceTitle(32f));subtitle=label(getString(R.string.local_notes),12f,muted);headings.addView(subtitle)
        top.addView(headings,LinearLayout.LayoutParams(0,dp(76),1f));top.addView(icon(0xf64d,getString(R.string.new_note),::newNote));top.addView(menuButton(false));listPane.addView(top)
        val input=EditText(this).apply { hint=getString(R.string.search_notes);textSize=15f;setSingleLine();setTextColor(ink);setHintTextColor(muted);background=round(paper,12);setPadding(dp(16),0,dp(16),0);contentDescription=getString(R.string.search_notes) }
        input.addTextChangedListener(object:android.text.TextWatcher { override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit;override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){search=s?.toString().orEmpty();filterNotes()};override fun afterTextChanged(s:android.text.Editable?)=Unit })
        listPane.addView(input,LinearLayout.LayoutParams(-1,dp(46)).apply { setMargins(dp(20),dp(10),dp(20),dp(12)) })
        count=label("",12f,muted).apply { setPadding(dp(24),0,dp(24),0) };listPane.addView(count,LinearLayout.LayoutParams(-1,dp(30)))
        val area=FrameLayout(this);listPane.addView(area,LinearLayout.LayoutParams(-1,0,1f))
        adapter=NotesAdapter { item->open(item) }
        val list=RecyclerView(this).apply { layoutManager=LinearLayoutManager(this@MainActivity);adapter=this@MainActivity.adapter;setPadding(dp(16),0,dp(16),dp(20));clipToPadding=false;itemAnimator=null }
        area.addView(list,FrameLayout.LayoutParams(-1,-1))
        empty=label(getString(R.string.empty_notes),16f,muted).apply { gravity=Gravity.CENTER;setLineSpacing(dp(5).toFloat(),1f) };area.addView(empty,FrameLayout.LayoutParams(-1,-1))
        val footer=row().apply { setPadding(dp(20),dp(6),dp(12),dp(10)) }
        syncLabel=label(getString(R.string.storage_ready),11f,muted).apply { maxLines=2;setOnClickListener { reviewOrSync() } };footer.addView(syncLabel,LinearLayout.LayoutParams(0,dp(45),1f));footer.addView(icon(0xf130,getString(R.string.sync_onedrive)){vm.command("sync")});listPane.addView(footer)
        if (BuildConfig.SIDELOAD_UPDATES) {
            updateBanner=label("",13f,blue).apply { visibility=View.GONE;gravity=Gravity.CENTER;setOnClickListener { showUpdateDialog() } }
            listPane.addView(updateBanner,LinearLayout.LayoutParams(-1,dp(42)))
        }
    }
    private fun buildEditor() {
        editPane=column().apply { setBackgroundColor(paper);visibility=View.INVISIBLE };root.addView(editPane,FrameLayout.LayoutParams(-1,-1))
        val top=row().apply { setPadding(dp(4),0,dp(8),0) }
        top.addView(Button(this,null,android.R.attr.borderlessButtonStyle).apply { text=getString(R.string.back_to_notes);textSize=17f;setTextColor(blue);setOnClickListener{showList()} },LinearLayout.LayoutParams(dp(90),dp(52)))
        top.addView(label(getString(R.string.autosave),12f,muted),LinearLayout.LayoutParams(0,dp(52),1f));top.addView(icon(0xf42a,getString(R.string.insert_image)) { if(ready&&vm.state.value.selected!=null){js("window.cnote.focusEditor()");imageNote=vm.state.value.selected;imageLauncher.launch("image/*")} });top.addView(menuButton(true));editPane.addView(top)
    }
    @Suppress("SetJavaScriptEnabled") private fun initializeEditor() {
        vm.editorView?.let { retained->
            (retained.context as android.content.MutableContextWrapper).baseContext=this
            web=retained;retained.setBackgroundColor(paper)
            editPane.addView(retained,LinearLayout.LayoutParams(-1,0,1f));syncEditorTheme();return
        }
        val model=vm
        val loader=WebViewAssetLoader.Builder().addPathHandler("/assets/",WebViewAssetLoader.AssetsPathHandler(applicationContext)).build()
        val view=WebView(android.content.MutableContextWrapper(this));web=view;vm.editorView=view
        view.setBackgroundColor(paper);view.isFocusableInTouchMode=true
        view.settings.apply { javaScriptEnabled=true;domStorageEnabled=false;allowFileAccess=false;allowContentAccess=false;mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW;setSupportMultipleWindows(false);javaScriptCanOpenWindowsAutomatically=false;builtInZoomControls=false;setGeolocationEnabled(false);mediaPlaybackRequiresUserGesture=true }
        view.addJavascriptInterface(object {
            @JavascriptInterface
            fun editorLanguage(): String = view.context.getString(R.string.editor_language_tag)

            @JavascriptInterface fun postMessage(raw:String) {
                model.bridge(raw)
            }
        },"Android")
        view.webViewClient=object:WebViewClient() {
            override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest)=loader.shouldInterceptRequest(request.url)
            override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean { if(request.url.toString()==EDITOR_URL)return false;if(request.isForMainFrame)model.command("link",JSONObject().put("url",request.url.toString()));return true }
        }
        editPane.addView(view,LinearLayout.LayoutParams(-1,0,1f));view.loadUrl(EDITOR_URL)
    }
    private fun menuButton(editing:Boolean)=Button(this,null,android.R.attr.borderlessButtonStyle).apply { text="⋮";textSize=27f;setTextColor(ink);contentDescription=getString(R.string.more_options);layoutParams=LinearLayout.LayoutParams(dp(42),dp(48));setOnClickListener { anchor->
        val popup=PopupMenu(this@MainActivity,anchor)
        val choices=if(editing)listOf(getString(R.string.save_now),getString(R.string.resolve_sync),getString(R.string.export_html),getString(R.string.print_note),getString(R.string.delete_note),getString(R.string.check_updates),getString(R.string.paste_image)) else listOf(if(vm.state.value.session.id.isEmpty())getString(R.string.sign_in_onedrive) else getString(R.string.sign_out_onedrive), getString(R.string.sync_now),if(app.analytics.enabled())getString(R.string.analytics_off) else getString(R.string.analytics_on),getString(R.string.check_updates),getString(R.string.about_cnote))
        choices.forEachIndexed { index,title->popup.menu.add(0,index,index,title) }
        if (!BuildConfig.SIDELOAD_UPDATES) popup.menu.removeItem(if (editing) 5 else 3)
        if (!editing) popup.menu.add(0, 7, 7, getString(R.string.privacy_policy))
        popup.setOnMenuItemClickListener { item->
            if(editing)when(item.itemId){0->action("save");1->reviewOrSync();2->js("document.getElementById('export').click()");3->js("document.getElementById('print').click()");4->action("delete");5->checkForUpdate();6->pasteClipboardImage()}
            else when(item.itemId){0->if(vm.state.value.session.id.isEmpty())action("login") else action("logout");1->vm.command("sync");2->{app.analytics.enabled(!app.analytics.enabled());toast(if(app.analytics.enabled())getString(R.string.analytics_on_notice) else getString(R.string.analytics_off_notice))};3->checkForUpdate();4->AlertDialog.Builder(this@MainActivity).setTitle("CNote ${BuildConfig.VERSION_NAME}").setMessage(getString(R.string.about_description)).setPositiveButton(getString(R.string.ok),null).show();7->openLink("https://blog.cyeam.com/cnote/privacy/")}
            true
        };popup.show()
    } }
    private fun filterNotes() { if(!::adapter.isInitialized)return;val all=lastNotes.orEmpty();val matches=all.filter { search.isEmpty()||it.title.contains(search,true) };adapter.submitList(matches);empty.visibility=if(matches.isEmpty())View.VISIBLE else View.GONE;empty.text=if(search.isEmpty())getString(R.string.empty_notes) else getString(R.string.no_matching_notes);count.text=if(search.isEmpty())resources.getQuantityString(R.plurals.note_count,all.size,all.size) else resources.getQuantityString(R.plurals.match_count,matches.size,matches.size) }
    private fun newNote() { val input=EditText(this).apply { hint=getString(R.string.note_name);setSingleLine();setPadding(dp(24),dp(8),dp(24),dp(8)) };val dialog=AlertDialog.Builder(this).setTitle(getString(R.string.new_note)).setView(input).setNegativeButton(getString(R.string.cancel),null).setPositiveButton(getString(R.string.create),null).create();dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener { try { val title=Rules.title(input.text.toString());vm.command("new",JSONObject().put("title",title));dialog.dismiss();showEditor() }catch(t:Exception){input.error=t.message} } };dialog.show();input.requestFocus() }
    private fun open(item:Summary) { showEditor();action("open",JSONObject().put("id",item.id).put("title",item.title));app.analytics.screen("note") }
    private fun showEditor() { editorVisible=true;listPane.visibility=View.INVISIBLE;editPane.visibility=View.VISIBLE;(getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(root.windowToken,0) }
    private fun showList() { editorVisible=false;listPane.visibility=View.VISIBLE;editPane.visibility=View.INVISIBLE;if(ready)js("window.cnote.flushAll()");vm.command("list");(getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(root.windowToken,0);app.analytics.screen("notes") }
    private fun action(type:String,data:JSONObject=JSONObject())=js("window.cnote.action(${JSONObject.quote(type)},$data)")
    private fun js(script:String) { if(!ready){queued.addLast(script);return};web?.evaluateJavascript(script,null) }
    private fun syncEditorTheme() { web?.setBackgroundColor(paper);js("window.cnote.setTheme(${JSONObject.quote(if(dark)"dark" else "light")})") }
    private fun receive(event:JSONObject)=js("window.cnote.receive($event)")
    private fun reviewOrSync() { val issue=vm.state.value.notes.firstOrNull { it.id==vm.state.value.selected&&it.conflict }?:vm.state.value.notes.firstOrNull { it.conflict };if(issue==null)vm.command("sync") else { showEditor();action("review",JSONObject().put("id",issue.id)) } }
    private fun effect(event:JSONObject) {
        when(event.getString("type")) {
            "web_ready"->{while(queued.isNotEmpty())js(queued.removeFirst());syncEditorTheme()}
            "load"->{receive(event);if(!event.isNull("id"))showEditor()}
            "state","save_failed","error","invalidate","dialog","image_clipboard_result"->receive(event)
            "saved"->{receive(event);if(!editorVisible)vm.command("list")}
            "list","sidebar"->showList()
            "close"->finish()
            "install_update"->installUpdate()
            "switch_workspace"->openFeature(event.getInt("workspace"))
            "link"->openLink(event.getString("url"))
            "paste_image"->pasteClipboardImage()
            "image"->{imageNote=vm.state.value.selected;imageLauncher.launch("image/*")}
            "export"->{exportData=event.getString("title") to ("<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>CNote</title></head><body>"+event.getString("html")+"</body></html>");exportLauncher.launch(event.getString("title")+".html")}
            "print"->{val view=web?:return;(getSystemService(PRINT_SERVICE) as PrintManager).print("CNote",view.createPrintDocumentAdapter("CNote"),null)}
            "login_code"->{if(vm.state.value.session.authenticated)return;loginDialog?.dismiss();val code=event.getString("code");val url=event.getString("url");loginDialog=AlertDialog.Builder(this).setTitle(getString(R.string.sign_in_onedrive)).setMessage(getString(R.string.device_code_instructions, code)).setNegativeButton(getString(R.string.cancel_sign_in)){_,_->vm.command("cancel_login")}.setNeutralButton(getString(R.string.copy_device_code)){_,_->(getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText(getString(R.string.microsoft_code),code));showLoginAgain(code,url)}.setPositiveButton(getString(R.string.open_sign_in)){_,_->openLink(url);showLoginAgain(code,url)}.setOnCancelListener { vm.command("cancel_login") }.show() }
            "commit_login"->action("commit_login")
            "login_done"->{loginDialog?.dismiss();loginDialog=null}

        }
    }
    private fun pasteClipboardImage() {
        val target=vm.state.value.selected?:return
        lifecycleScope.launch {
            try {
                val manager=getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip=manager.primaryClip?:error(getString(R.string.clipboard_no_image))
                val uri=clip.getItemAt(0).uri?:error(getString(R.string.clipboard_no_image))
                require(uri.scheme=="content") { getString(R.string.invalid_image_uri) }
                val source=withContext(Dispatchers.IO) {
                    val mime=contentResolver.getType(uri).orEmpty();require(mime in setOf("image/png","image/jpeg","image/webp","image/gif","image/bmp")) { getString(R.string.clipboard_no_supported_image) }
                    val bytes=contentResolver.openInputStream(uri)!!.use { it.readBytesBounded(8*1024*1024) }
                    "data:$mime;base64,"+android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP)
                }
                if(target==vm.state.value.selected)js("window.cnote.insertImage(${JSONObject.quote(source)})")
            }catch(t:Exception) { toast(t.message?:getString(R.string.paste_failed)) }
        }
    }
    private fun renderUpdate(state:UpdateState) {
        updateBanner.visibility=if(state.stage in listOf(UpdateStage.DOWNLOADING,UpdateStage.READY))View.VISIBLE else View.GONE
        updateBanner.text=if(state.stage==UpdateStage.READY)getString(R.string.update_ready, state.version) else getString(R.string.update_progress, state.progress)
        updateMessage?.text=listOf(getString(R.string.current_version, BuildConfig.VERSION_NAME),state.version.takeIf { it.isNotEmpty() }?.let { getString(R.string.new_version, it) },state.notes.takeIf { it.isNotEmpty() },state.message.takeIf { it.isNotEmpty() }).filterNotNull().joinToString("\n\n")
        updateProgress?.apply { visibility=if(state.stage==UpdateStage.DOWNLOADING)View.VISIBLE else View.GONE;progress=state.progress }
        updateDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.text=when(state.stage){UpdateStage.READY->getString(R.string.install_update);UpdateStage.FAILED,UpdateStage.IDLE->getString(R.string.check_again);else->getString(R.string.continue_background)}
    }
    private fun showUpdateDialog() {
        if(updateDialog?.isShowing==true)return
        val contents=column().apply { setPadding(dp(24),dp(8),dp(24),dp(8)) }
        updateMessage=label("",14f).apply { gravity=Gravity.TOP };contents.addView(updateMessage)
        updateProgress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { max=100 };contents.addView(updateProgress,LinearLayout.LayoutParams(-1,dp(24)))
        updateDialog=AlertDialog.Builder(this).setTitle(getString(R.string.app_update)).setView(ScrollView(this).apply { addView(contents) }).setNegativeButton(getString(R.string.later),null).setPositiveButton(getString(R.string.check_updates),null).create().apply {
            setOnDismissListener { updateDialog=null;updateMessage=null;updateProgress=null }
            show();getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                when(app.updater.state.value.stage) {
                    UpdateStage.READY->{dismiss();action("install_update")}
                    UpdateStage.FAILED,UpdateStage.IDLE->app.updater.checkForUpdate(true)
                    else->dismiss()
                }
            }
        }
        renderUpdate(app.updater.state.value)
    }
    private fun installUpdate() {
        if (!BuildConfig.SIDELOAD_UPDATES) return
        if(app.updater.state.value.stage!=UpdateStage.READY){showUpdateDialog();return}
        if(!packageManager.canRequestPackageInstalls()) {
            vm.pendingInstall=true
            try { installPermission.launch(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:$packageName"))) }
            catch(_:Exception){vm.pendingInstall=false;toast(getString(R.string.update_permission_failed))}
            return
        }
        lifecycleScope.launch {
            try { val intent=app.updater.installIntent();startActivity(intent) }
            catch(_:Exception){toast(getString(R.string.install_update_failed_retry))}
        }
    }
    override fun onResume() {
        super.onResume()
        if(BuildConfig.SIDELOAD_UPDATES && ::updateBanner.isInitialized) {
            val state=app.updater.state.value
            if(state.stage==UpdateStage.READY&&vm.promptedUpdate!=state.id){vm.promptedUpdate=state.id;showUpdateDialog()}
        }
    }
    private fun checkForUpdate() {
        if (BuildConfig.SIDELOAD_UPDATES) {
            app.updater.checkForUpdate(true)
            showUpdateDialog()
        }
    }
    private fun showLoginAgain(code:String,url:String) { effect(JSONObject().put("type","login_code").put("code",code).put("url",url)) }
    private fun openLink(value:String) { try { val uri=Uri.parse(value);require(uri.scheme in listOf("https","http","mailto"));startActivity(Intent(Intent.ACTION_VIEW,uri)) }catch(t:Exception){toast(getString(R.string.no_link_handler))} }
    private fun toast(message:String)=Toast.makeText(this,message,Toast.LENGTH_LONG).show()
    override fun onStop() { if(ready)js("window.cnote.flushAll()");super.onStop() }
    override fun onSaveInstanceState(outState:Bundle) { outState.putBoolean("editor_visible",editorVisible);vm.state.value.selected?.let { outState.putString("note_id",it) };super.onSaveInstanceState(outState) }
    override fun onDestroy() {
        loginDialog?.dismiss();updateDialog?.dismiss()
        web?.let { view->
            (view.parent as? ViewGroup)?.removeView(view)
            if(isChangingConfigurations)(view.context as android.content.MutableContextWrapper).baseContext=applicationContext
            else { vm.editorView=null;vm.editorReady=false;queued.clear();view.removeJavascriptInterface("Android");view.destroy() }
        }
        super.onDestroy()
    }
    internal fun editorForTest()=web
    private inner class NotesAdapter(private val click:(Summary)->Unit):ListAdapter<Summary,NoteHolder>(object:DiffUtil.ItemCallback<Summary>(){override fun areItemsTheSame(a:Summary,b:Summary)=a.id==b.id;override fun areContentsTheSame(a:Summary,b:Summary)=a==b}) {
        init { setHasStableIds(true) }
        override fun getItemId(position:Int)=java.util.UUID.fromString(getItem(position).id).mostSignificantBits
        override fun onCreateViewHolder(parent:ViewGroup,viewType:Int):NoteHolder {
            val container=row().apply { background=round(paper);setPadding(dp(16),dp(16),dp(14),dp(16));layoutParams=RecyclerView.LayoutParams(-1,dp(92)).apply { bottomMargin=dp(8) } }
            container.addView(label(0xf444.toChar().toString(),25f,blue).apply { typeface=font },LinearLayout.LayoutParams(dp(44),-1))
            val texts=column();val title=label("",17f).apply { typeface=Typeface.DEFAULT_BOLD;maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END };val detail=label("",12f,muted).apply { maxLines=1 };texts.addView(title,LinearLayout.LayoutParams(-1,0,1f));texts.addView(detail,LinearLayout.LayoutParams(-1,0,1f));container.addView(texts,LinearLayout.LayoutParams(0,-1,1f));return NoteHolder(container,title,detail)
        }
        override fun onBindViewHolder(holder:NoteHolder,position:Int) { val n=getItem(position);holder.title.text=n.title;holder.detail.text=dates.format(Date(n.modified))+" · "+if(n.conflict)getString(R.string.sync_conflict) else if(n.dirty)getString(R.string.saved_locally) else getString(R.string.synced);holder.itemView.setOnClickListener { click(n) };holder.itemView.setOnLongClickListener { action("delete",JSONObject().put("id",n.id).put("title",n.title));true } }
    }
    private class NoteHolder(view:View,val title:TextView,val detail:TextView):RecyclerView.ViewHolder(view)
    companion object { const val EDITOR_URL="https://appassets.androidplatform.net/assets/editor/index.html" }
}
internal fun java.io.InputStream.readBytesBounded(max:Int):ByteArray { val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(16384);while(true){val count=read(buffer);if(count<0)break;require(output.size()+count<=max){"图片最大支持 ${max/1024/1024} MB"};output.write(buffer,0,count)};return output.toByteArray() }
