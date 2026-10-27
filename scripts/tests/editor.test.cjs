const {test,before,after}=require('node:test'),assert=require('node:assert/strict'),http=require('node:http'),fs=require('node:fs'),path=require('node:path');
const {chromium,webkit}=require('playwright'),{compareNote}=require('../editor/compare.cjs');
let server,url;
before(async()=>{server=http.createServer((req,res)=>{const name=path.basename(new URL(req.url,'http://localhost').pathname)||'index.html';const file=path.join(__dirname,'../../app/src/main/assets/editor',name);if(!fs.existsSync(file)){res.writeHead(404).end();return;}res.setHeader('Content-Type',({'.html':'text/html; charset=utf-8','.js':'text/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.ttf':'font/ttf'})[path.extname(file)]||'text/plain');res.end(fs.readFileSync(file));});await new Promise(r=>server.listen(0,'127.0.0.1',r));url=`http://127.0.0.1:${server.address().port}/index.html`;});
after(()=>new Promise(r=>server.close(r)));
test('HTML diff keeps exact versions including formatting and empty sides',()=>{for(const [left,right]of [['<p><b>旧</b></p><p>相同</p>','<p><i>新</i></p><p>相同</p>'],['','<p>new</p>'],['<table><tr><td>A</td></tr></table>','<table><tr><td>B</td></tr></table>']]){const parts=compareNote({left,right});assert.equal(parts.filter(p=>p.kind!=='added').map(p=>p.html).join(''),left);assert.equal(parts.filter(p=>p.kind!=='removed').map(p=>p.html).join(''),right);}});
for(const [name,type]of [['Chromium',chromium],['WebKit',webkit]])test(name+' mobile conflicts and images',async t=>{
 const browser=await type.launch({headless:true});try{
 const context=await browser.newContext({viewport:{width:390,height:844},hasTouch:true}),page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.addInitScript(()=>{window.messages=[];window.Android={postMessage:raw=>{const m=JSON.parse(raw);messages.push(m);if(m.type==='change')setTimeout(()=>cnote.receive({type:'saved',id:m.id,seq:m.seq}),0);if(m.type==='resolve')setTimeout(()=>cnote.receive({type:'dialog',kind:'conflict_result',data:{id:m.id,ok:true,html:m.html||'',pending_sync:m.choice!=='cloud'}}),0);}};});
 await page.goto(url);await page.waitForFunction(()=>!!window.cnote);assert.equal(await page.locator('#image-actions').isVisible(),false);
 const receive=e=>page.evaluate(e=>cnote.receive(e),e);
 await t.test('each diff is explicitly selected, previews rich content, and reports the saved result',async()=>{
  const local='<h1>共同</h1><p><b>本地</b></p><p>中间</p><p>本地尾部</p>',cloud='<h1>共同</h1><p><i>云端</i></p><p>中间</p><p>云端尾部</p>';
  await receive({type:'load',id:'conflict',title:'冲突',html:local});await receive({type:'dialog',kind:'conflict_view',data:{id:'conflict',title:'冲突',local,cloud,revision:3,etag:'v2'}});
  await page.waitForFunction(()=>document.querySelectorAll('.conflict-change').length===2);assert(await page.getByRole('button',{name:'保存合并结果',exact:true}).isDisabled());
  await page.locator('.conflict-change').first().getByRole('button',{name:'选择云端',exact:true}).tap();await page.locator('.conflict-change').nth(1).getByRole('button',{name:'选择本地',exact:true}).tap();
  const expected='<h1>共同</h1><p><i>云端</i></p><p>中间</p><p>本地尾部</p>';assert.equal(await page.locator('.conflict-preview').innerHTML(),expected);
  await page.getByRole('button',{name:'保存合并结果',exact:true}).tap();await page.waitForFunction(()=>document.getElementById('dialog-title').textContent==='已应用你的选择');
  const m=await page.evaluate(()=>messages.filter(m=>m.type==='resolve').at(-1));assert.equal(m.html,expected);assert.equal(m.revision,3);assert.equal(m.etag,'v2');assert((await page.locator('#dialog-body').textContent()).includes('差异 1：使用云端；差异 2：保留本地'));
  await page.getByRole('button',{name:'完成',exact:true}).tap();
 });
 await t.test('cancel preserves the conflict and cloud deletion has explicit choices',async()=>{
  const before=await page.evaluate(()=>messages.filter(m=>m.type==='resolve').length);
  await receive({type:'dialog',kind:'conflict_view',data:{id:'missing',title:'已删除',local:'<p>本地草稿</p>',cloud:'',revision:8,etag:'',missing:true}});
  await page.getByRole('button',{name:'稍后处理',exact:true}).tap();assert.equal(await page.evaluate(()=>messages.filter(m=>m.type==='resolve').length),before);
 });
 await t.test('touch selects images, zoom works, failed cut retains pixels and successful cut is undoable',async()=>{
  const src=await page.evaluate(()=>{const c=document.createElement('canvas');c.width=200;c.height=100;c.getContext('2d').fillRect(0,0,200,100);return c.toDataURL();});
  await receive({type:'load',id:'image',title:'图片',html:`<p>正文</p><p><img src="${src}" alt="测试图片"></p>`});await page.locator('#editor img').tap();await page.waitForSelector('#image-actions:visible');
  await page.locator('#image-actions').getByRole('button',{name:'放大查看',exact:true}).tap();assert(await page.locator('dialog.image-dialog').evaluate(el=>el.open));await page.getByRole('button',{name:'放大',exact:true}).tap();await page.getByRole('button',{name:'关闭',exact:true}).tap();
  await page.locator('#editor img').tap();await page.locator('#image-actions').getByRole('button',{name:'剪切图片',exact:true}).tap();let m=await page.evaluate(()=>messages.filter(m=>m.type==='image_clipboard').at(-1));assert.equal(m.src,src);assert.equal(await page.locator('#editor img').count(),1);
  await receive({type:'image_clipboard_result',token:m.token,ok:false,message:'clipboard unavailable'});assert.equal(await page.locator('#editor img').count(),1);
  await page.locator('#image-actions').getByRole('button',{name:'剪切图片',exact:true}).tap();m=await page.evaluate(()=>messages.filter(m=>m.type==='image_clipboard').at(-1));await receive({type:'image_clipboard_result',token:m.token,ok:true});assert.equal(await page.locator('#editor img').count(),0);
  await page.evaluate(()=>document.execCommand('undo'));assert.equal(await page.locator('#editor img').count(),1);await page.evaluate(()=>cnote.flush());await page.waitForTimeout(30);
  const html=await page.evaluate(()=>messages.filter(m=>m.type==='change'&&m.id==='image').at(-1).html);assert(html.includes(src));assert(!html.includes('image-actions'));
 });
 await t.test('saved body title is used by export and stale acknowledgements cannot replace it',async()=>{
  await receive({type:'load',id:'title',title:'文件名',html:'<h1>正文标题</h1>'});
  await page.locator('#editor').fill('修改标题');await page.evaluate(()=>cnote.flush());
  const change=await page.evaluate(()=>messages.filter(m=>m.type==='change'&&m.id==='title').at(-1));
  await receive({type:'saved',id:'title',seq:change.seq,title:'正文新标题'});
  await receive({type:'saved',id:'title',seq:change.seq-1,title:'过期标题'});
  await page.evaluate(()=>document.getElementById('export').click());await page.waitForFunction(()=>messages.some(m=>m.type==='export'&&m.id==='title'));
  assert.equal(await page.evaluate(()=>messages.filter(m=>m.type==='export'&&m.id==='title').at(-1).title),'正文新标题');
 });
 assert.deepEqual(errors,[]);
 }finally{await browser.close();}
});
