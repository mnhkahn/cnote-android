'use strict';
(() => {
 let editor=document.getElementById('editor');
 const editorTemplate=editor.cloneNode(false), main=document.querySelector('main');
 editorTemplate.classList.add('note-editor');editorTemplate.removeAttribute('id');
 const stack=document.createElement('div');stack.className='editor-stack';editor.replaceWith(stack);
 const dialog=document.getElementById('dialog'), body=document.getElementById('dialog-body'), actions=document.getElementById('dialog-actions');
 let id='',active=null,serial=0,intent=0,selection=null,contextCell=null,selectedImage=null,imageRequest=0,imagePending=null,conflictNote=null;
 const notes=new Map(),pendingWrites=new Set();
 let countTimer=null,pruneTimer=null,scrollPosition=0;
 main.addEventListener('scroll',()=>{scrollPosition=main.scrollTop;if(active)active.scroll=scrollPosition;positionImageActions();},{passive:true});
 let composing=false,pendingLoad=null,dialogKind='',dialogQueue=[],pendingAction=null,pendingConflictResult=null;
 const post=data=>window.Android.postMessage(JSON.stringify(data));
 const text=(tag,value)=>{const el=document.createElement(tag);el.textContent=value;return el;};
 const safeURL=u=>{try{return ['https:','http:','mailto:'].includes(new URL(u).protocol);}catch{return false;}};
 function sanitize(html){
  const doc=new DOMParser().parseFromString(html,'text/html');
  const allowed=new Set('P DIV SPAN BR H1 H2 H3 H4 H5 H6 STRONG B EM I U S STRIKE CODE PRE BLOCKQUOTE UL OL LI TABLE THEAD TBODY TFOOT TR TD TH INPUT LABEL A IMG HR SUB SUP'.split(' '));
  const forbidden=new Set(['SCRIPT','STYLE','IFRAME','OBJECT','EMBED','FORM','LINK','META','BASE','SVG','MATH','TEMPLATE']);
  for(const el of [...doc.body.querySelectorAll('*')]){
   if(forbidden.has(el.tagName)){el.remove();continue;}
   if(!allowed.has(el.tagName)){el.replaceWith(...el.childNodes);continue;}
   for(const a of [...el.attributes]){
    const k=a.name.toLowerCase();
    if(!['href','src','alt','title','colspan','rowspan','type','checked','class','style'].includes(k))el.removeAttribute(a.name);
    else if(k==='href'&&!safeURL(a.value))el.removeAttribute(k);
    else if(k==='src'&&!/^https:\/\//i.test(a.value)&&!/^data:image\/(png|jpeg|webp|gif|bmp);base64,/i.test(a.value))el.removeAttribute(k);
    else if(k==='style'&&/url\s*\(|expression|position\s*:|behavior|@import/i.test(a.value))el.removeAttribute(k);
   }
   if(el.tagName==='INPUT'){if(el.getAttribute('type')!=='checkbox')el.remove();else el.removeAttribute('style');}
  }
  return doc.body.innerHTML;
 }
 // Keep the actual editing trees alive: switching a cached note never serializes or reparses HTML.
 function record(noteId,noteTitle='',html=null){
  const node=editorTemplate.cloneNode(false);
  const pane=document.createElement('div');pane.className='editor-pane';pane.setAttribute('aria-hidden','true');pane.append(node);
  const n={id:noteId,title:noteTitle,node,pane,height:0,source:html,seq:0,saved:0,sent:0,timer:null,loaded:html!==null,scroll:0,selection:null,count:'',bytes:html?.length||0};
  if(n.loaded)node.innerHTML=sanitize(html);
  node.contentEditable=n.loaded?'true':'false';stack.append(pane);
  n.observer=new ResizeObserver(entries=>{n.height=entries[0].contentRect.height;if(active===n)stack.style.height=n.height+'px';});n.observer.observe(node);
  notes.set(noteId,n);return n;
 }
 function pending(n){return n.seq>n.saved;}
 function updateGuard(){post({type:pendingWrites.size?'dirty':'clean'});}
 function count(){
  clearTimeout(countTimer);if(!active)return;
  active.count=`${editor.textContent.length.toLocaleString()} 字`;
  document.getElementById('count').textContent=active.count;
 }
 function deferCount(){clearTimeout(countTimer);countTimer=setTimeout(count,300);}
 function dispose(n){clearTimeout(n.timer);n.observer.disconnect();n.pane.remove();}
 function prune(){
  clearTimeout(pruneTimer);pruneTimer=setTimeout(()=>{
   let clean=[...notes.values()].filter(n=>n!==active&&!pending(n));
   let bytes=clean.reduce((sum,n)=>sum+n.bytes*2,0);
   while(clean.length>8||bytes>16*1024*1024){const n=clean.shift();if(!n)break;dispose(n);notes.delete(n.id);bytes-=n.bytes*2;}
  },500);
 }
 function activate(n){
  if(active===n)return;
  remember();clearImageSelection();if(active){active.scroll=scrollPosition;active.selection=selection;active.pane.classList.remove('active');active.pane.setAttribute('aria-hidden','true');}
  editor.removeAttribute('id');editor=n.node;editor.id='editor';active=n;id=n.id;selection=n.selection;
  n.pane.classList.add('active');n.pane.removeAttribute('aria-hidden');n.height=n.height||n.node.offsetHeight;stack.style.height=n.height+'px';
  notes.delete(id);notes.set(id,n);
  document.getElementById('document').hidden=false;document.getElementById('empty').style.display='none';
  document.getElementById('count').textContent=n.count;
  if(scrollPosition!==n.scroll){main.scrollTop=n.scroll;scrollPosition=n.scroll;}deferCount();prune();
 }
 function changed(){
  if(!active?.loaded)return;
  const wasPending=pendingWrites.size>0,wasNotePending=pending(active);if(active.seq===active.sent)active.due=performance.now()+1000;active.seq=++serial;pendingWrites.add(id);
  if(!wasNotePending)post({type:'editing',id});
  if(!wasPending)post({type:'dirty'});
  schedule(active);deferCount();
 }
 function schedule(n,delay=200){clearTimeout(n.timer);const wait=delay>200?delay:Math.min(delay,Math.max(0,(n.due||performance.now()+1000)-performance.now()));n.timer=setTimeout(()=>flush(n),wait);}
 function flush(n=active,retry=false){
  if(!n)return;clearTimeout(n.timer);
  if(n.seq>n.sent||(retry&&pending(n))){
   // This runs after the interaction task, including for detached, inactive editors.
   const html=n.node.innerHTML;n.bytes=html.length;n.source=html;n.sent=n.seq;
   post({type:'change',id:n.id,html,seq:n.seq});
  }
 }
 function flushAll(){for(const n of notes.values())if(pending(n))flush(n,true);}
 function barrier(fn){if(pendingWrites.size){pendingAction=fn;flushAll();}else fn();}
 function navigate(type,data={}){
  intent++;post({type:'navigation',intent,id:type==='open'?data.id:null});
  const cached=type==='open'&&notes.get(data.id)?.loaded===true&&!notes.get(data.id)?.stale;
  if(type==='open')activate(notes.get(data.id)||record(data.id,data.title||'正在打开…'));
  post({type,...data,intent,...(type==='open'?{cached}:{})});
 }
 function onEditor(type,fn){document.getElementById('document').addEventListener(type,e=>{
  if(e.target===editor||editor.contains(e.target))fn(e);
 },type==='blur'||type==='load');}
 function remember(){const s=getSelection();if(s.rangeCount&&editor.contains(s.anchorNode))selection=s.getRangeAt(0).cloneRange();syncImageSelection();}
 function restore(){editor.focus();if(selection&&editor.contains(selection.startContainer)){const s=getSelection();s.removeAllRanges();s.addRange(selection);}}
 const imageOutline=document.getElementById('image-selection'),imageActions=document.getElementById('image-actions');
 function clearImageSelection(){selectedImage=null;imageOutline.hidden=true;imageActions.hidden=true;}
 function syncImageSelection(){
  const s=getSelection(),r=s?.rangeCount?s.getRangeAt(0):null;
  const node=r&&r.startContainer===r.endContainer&&r.endOffset===r.startOffset+1?r.startContainer.childNodes[r.startOffset]:null;
  selectedImage=node?.nodeName==='IMG'&&editor.contains(node)?node:null;positionImageActions();
 }
 function positionImageActions(){
  if(!selectedImage?.isConnected||!editor.contains(selectedImage)||dialog.open){imageOutline.hidden=true;imageActions.hidden=true;return;}
  const r=selectedImage.getBoundingClientRect(),bounds=main.getBoundingClientRect();
  const top=Math.max(r.top,bounds.top),bottom=Math.min(r.bottom,bounds.bottom),left=Math.max(r.left,0),right=Math.min(r.right,innerWidth);
  if(bottom<=top||right<=left){imageOutline.hidden=true;imageActions.hidden=true;return;}
  imageOutline.hidden=false;imageActions.hidden=false;
  Object.assign(imageOutline.style,{left:left+'px',top:top+'px',width:(right-left)+'px',height:(bottom-top)+'px'});
  imageActions.style.left=Math.max(8,Math.min(left,innerWidth-imageActions.offsetWidth-8))+'px';
  imageActions.style.top=Math.max(bounds.top+6,top-imageActions.offsetHeight-8)+'px';
  for(const button of imageActions.querySelectorAll('button'))button.disabled=!!imagePending;
 }
 function selectImage(image,focus=true){
  if(focus)editor.focus();const range=document.createRange();range.selectNode(image);const s=getSelection();s.removeAllRanges();s.addRange(range);remember();
 }
 function imageStatus(message,error=false){const status=document.getElementById('status');status.textContent=message;status.style.color=error?'#ca5262':'';}
 function imageClipboard(cut=false){
  if(!selectedImage||imagePending)return;
  const image=selectedImage,src=image.getAttribute('src');if(!src){imageStatus('图片地址无效，无法复制。',true);return;}
  imagePending={token:++imageRequest,id,image,src,cut};positionImageActions();
  imageStatus(cut?'正在复制图片，成功后从笔记移除…':'正在复制图片…');
  post({type:'image_clipboard',token:imageRequest,src,html:image.outerHTML});
 }
 function removeImage(image=selectedImage){
  if(!image||!editor.contains(image))return false;
  selectImage(image);const removed=document.execCommand('delete');remember();
  if(removed){changed();clearImageSelection();}return removed;
 }
 function viewImage(image=selectedImage){
  if(!image)return;
  const frame=document.createElement('div'),full=document.createElement('img'),info=text('p','滚动查看图片 · 返回键关闭');
  frame.className='image-view-frame';full.src=image.src;full.alt=image.alt||'笔记图片';frame.append(full);
  let zoom=1;
  const scale=value=>{zoom=Math.max(.001,Math.min(4,value));if(full.naturalWidth)full.style.width=Math.round(full.naturalWidth*zoom)+'px';info.textContent=`${full.naturalWidth} × ${full.naturalHeight} · ${Math.round(zoom*100)}% · 返回键关闭`;};
  const fit=()=>scale(Math.min(1,(frame.clientWidth-16)/full.naturalWidth,(frame.clientHeight-16)/full.naturalHeight));
  full.onload=fit;full.onerror=()=>{info.textContent='图片加载失败，请检查网络后重试。';};
  modal('image','查看图片',[info,frame],[['缩小',()=>{scale(zoom/1.25);return false;}],['放大',()=>{scale(zoom*1.25);return false;}],['原始尺寸',()=>{scale(1);return false;}],['适应窗口',()=>{fit();return false;}],['关闭',()=>{},'primary']]);
  positionImageActions();requestAnimationFrame(()=>{if(full.complete&&full.naturalWidth)fit();});
 }
 imageActions.addEventListener('mousedown',e=>e.preventDefault());
 for(const button of imageActions.querySelectorAll('button'))button.onclick=()=>{
  const action=button.dataset.imageAction;if(action==='view')viewImage();else if(action==='delete')removeImage();else imageClipboard(action==='cut');
 };
 window.addEventListener('resize',positionImageActions);
 onEditor('load',positionImageActions);
 onEditor('pointerdown',e=>{if(e.pointerType==='touch'&&e.target.tagName==='IMG'){e.preventDefault();selectImage(e.target,false);}});
 onEditor('mousedown',e=>{if(e.button===0&&e.target.tagName==='IMG'){e.preventDefault();selectImage(e.target);}});
 onEditor('dblclick',e=>{if(e.target.tagName==='IMG'){e.preventDefault();selectImage(e.target);viewImage(e.target);}});
 for(const type of ['copy','cut'])onEditor(type,e=>{syncImageSelection();if(selectedImage){e.preventDefault();imageClipboard(type==='cut');}});
 function command(cmd,value){if(!id)return;restore();document.execCommand(cmd,false,value??null);remember();changed();}
 function insert(html){command('insertHTML',html);}
 function task(){insert('<div><input type="checkbox"><label>待办事项</label></div><p><br></p>');}
 function modal(kind,heading,content,buttons){
  if(dialog.open){if(dialogKind===kind&&kind==='login')dialog.close();else{dialogQueue.push(()=>modal(kind,heading,content,buttons));return;}}
  dialogKind=kind;dialog.classList.toggle('conflict-dialog',kind==='conflict');dialog.classList.toggle('image-dialog',kind==='image');document.getElementById('dialog-title').textContent=heading;body.replaceChildren(...content);actions.replaceChildren();
  for(const [label,handler,cls] of buttons){const b=text('button',label);b.type='button';if(cls)b.className=cls;b.onclick=()=>{if(handler?.()!==false)dialog.close();};actions.append(b);}
  dialog.showModal();
 }
 dialog.addEventListener('cancel',e=>{if(pendingConflictResult)e.preventDefault();});
 dialog.addEventListener('close',()=>{const old=dialogKind;dialogKind='';if(old==='conflict')conflictNote=null;if(old==='login'&&!window.loginFinished)post({type:'logout'});queueMicrotask(()=>{positionImageActions();dialogQueue.shift()?.();});});
 const cancel=['取消',()=>{}];
 function compareNote(data){
  return new Promise((resolve,reject)=>{
   const worker=new Worker('compare.js');const timer=setTimeout(()=>{worker.terminate();reject(Error('对比超时，可稍后重试或选择整篇版本'));},6000);
   const finish=()=>{clearTimeout(timer);worker.terminate();};
   worker.onmessage=e=>{finish();e.data.error?reject(Error(e.data.error)):resolve(e.data.parts);};
   worker.onerror=()=>{finish();reject(Error('无法计算差异，可选择整篇版本'));};worker.postMessage(data);
  });
 }
 function showMissingConflict(d){
  const preview=document.createElement('div');preview.className='note-editor conflict-preview';preview.innerHTML=sanitize(d.local);
  const resolve=choice=>{
   for(const button of actions.querySelectorAll('button'))button.disabled=true;
   pendingConflictResult={id:d.id,receive:result=>{
    pendingConflictResult=null;document.getElementById('dialog-title').textContent=result.ok?'已应用你的选择':'未能保存选择';
    const message=result.ok?(result.removed?'已移除本机副本。':'已保留本机内容，等待重新上传 OneDrive。'):`${result.message}。本机草稿仍保留，请重新打开对比。`;
    body.replaceChildren(text('p',message));if(!result.removed)body.append(preview);
    const done=text('button','完成');done.type='button';done.onclick=()=>dialog.close();actions.replaceChildren(done);
   }};
   barrier(()=>post({type:'resolve',id:d.id,choice,revision:d.revision,etag:d.etag}));return false;
  };
  modal('conflict','云端笔记已删除',[text('p',`“${d.title}”已从 OneDrive 删除。本机草稿仍保留，由你选择重新上传或移除本机副本。`),preview],[['稍后处理',()=>{}],['移除本机副本',()=>resolve('cloud')],['重新上传本机笔记',()=>resolve('mine'),'primary']]);
 }
 function conflictDialog(d){
  if(conflictNote===d.id)return;
  if(dialog.open){dialogQueue.push(()=>conflictDialog(d));return;}
  conflictNote=d.id;
  // A conflict may arrive before an editor's debounced write has reached storage.
  barrier(()=>post({type:'conflict_view',id:d.id}));
 }
 function showConflict(d){
  if(dialog.open&&conflictNote===d.id)return;
  if(dialog.open){dialogQueue.push(()=>showConflict(d));return;}
  conflictNote=d.id;if(d.missing){showMissingConflict(d);return;}
  // The repository snapshot follows the save barrier and is the version validated on apply.
  const left=d.local;
  const intro=text('p',`“${d.title}”有两个版本。每处差异都由你选择本地或云端内容，再检查合并预览。红色为本地差异，绿色为云端差异。`);
  const summary=text('p','正在计算差异…'),changes=document.createElement('div'),preview=document.createElement('div');
  changes.className='conflict-changes';preview.className='note-editor conflict-preview';preview.setAttribute('aria-label','合并结果预览');
  preview.addEventListener('click',e=>e.preventDefault());changes.addEventListener('click',e=>{if(e.target.closest('a,input'))e.preventDefault();});
  const label=text('h3','合并预览'),sourceToggle=text('button','查看 HTML 差异');sourceToggle.type='button';
  let source=false,groups=[],ready=false,completed=false,save;
  const render=()=>{
   const fragment=document.createDocumentFragment();let count=0;
   for(const group of groups){
    if(group.same!==undefined)continue;
    const section=document.createElement('section');section.className='conflict-change';
    section.append(text('h3',`差异 ${++count} · ${group.choice==='mine'?'保留本地':group.choice==='cloud'?'使用云端':'待你选择'}`));
    const columns=document.createElement('div');columns.className='conflict-columns';
    for(const [choice,heading,html] of [['mine','本地',group.mine],['cloud','云端',group.cloud]]){
     const column=document.createElement('div');column.className='conflict-version '+choice;
     const button=text('button',group.choice===choice?`✓ 已选${heading}`:`选择${heading}`);button.type='button';button.setAttribute('aria-pressed',String(group.choice===choice));
     button.onclick=()=>{group.choice=choice;render();};
     const content=document.createElement(source?'pre':'div');content.className='conflict-content';
     if(source)content.textContent=html||'（此处无内容）';else if(html)content.innerHTML=sanitize(html);else content.textContent='（此处无内容）';
     column.append(button,content);columns.append(column);
    }
    section.append(columns);fragment.append(section);
   }
   changes.replaceChildren(fragment);
   const differences=groups.filter(g=>g.same===undefined),unselected=differences.filter(g=>!g.choice).length;
   const mine=differences.filter(g=>g.choice==='mine').length,cloud=differences.filter(g=>g.choice==='cloud').length;
   preview.innerHTML=unselected?'':sanitize(groups.map(g=>g.same??g[g.choice]).join(''));
   if(unselected)preview.textContent=`还有 ${unselected} 处差异待选择，全部选择后显示完整结果。`;
   summary.textContent=count?`共 ${count} 处差异 · 本地 ${mine} 处 · 云端 ${cloud} 处 · 待选择 ${unselected} 处`:'内容一致（包含格式）';
   save.disabled=!ready||unselected>0;
  };
  sourceToggle.onclick=()=>{source=!source;sourceToggle.textContent=source?'查看内容差异':'查看 HTML 差异';render();};
  const resolve=(choice,html)=>{
   const selection=choice==='cloud'?'整篇使用云端版本':choice==='mine'?'整篇保留本地版本':groups.filter(g=>g.same===undefined).map((g,i)=>`差异 ${i+1}：${g.choice==='mine'?'保留本地':'使用云端'}`).join('；');
   summary.textContent='正在保存你的选择…';for(const button of dialog.querySelectorAll('button'))button.disabled=true;
   pendingConflictResult={id:d.id,receive:result=>{
    pendingConflictResult=null;
    if(!result.ok){
     for(const button of dialog.querySelectorAll('button'))button.disabled=false;
     summary.textContent=`未能保存选择：${result.message}。原有草稿仍保留，请稍后重新打开对比。`;
     save.disabled=!ready||groups.some(g=>g.same===undefined&&!g.choice);return;
    }
    completed=true;document.getElementById('dialog-title').textContent='已应用你的选择';
    const resultPreview=document.createElement('div');resultPreview.className='note-editor conflict-preview';resultPreview.setAttribute('aria-label','已保存的笔记内容');resultPreview.innerHTML=sanitize(result.html);resultPreview.addEventListener('click',e=>e.preventDefault());
    body.replaceChildren(text('p',selection),text('p',result.pending_sync?'已保存到本机，等待同步到 OneDrive。':'已使用云端版本并保存到本机。'),text('h3','最终保存内容'),resultPreview);
    const done=text('button','完成');done.type='button';done.className='primary';done.onclick=()=>dialog.close();actions.replaceChildren(done);done.focus();
   }};
   barrier(()=>post({type:'resolve',id:d.id,choice,html,revision:d.revision,etag:d.etag}));
   return false;
  };
  modal('conflict','对比并处理笔记冲突',[intro,summary,sourceToggle,changes,label,preview],[['稍后处理',()=>{}],['使用云端版本',()=>resolve('cloud')],['保留本地版本',()=>resolve('mine')],['保存合并结果',()=>{if(!ready||groups.some(g=>g.same===undefined&&!g.choice))return false;return resolve('merge',groups.map(g=>g.same??g[g.choice]).join(''));},'primary']]);
  save=actions.lastElementChild;save.disabled=true;
  compareNote({left:left??'',right:d.cloud}).then(parts=>{
   for(const part of parts){
    if(part.kind==='same')groups.push({same:part.html});
    else {let group=groups.at(-1);if(!group||group.same!==undefined){group={mine:'',cloud:'',choice:null};groups.push(group);}group[part.kind==='removed'?'mine':'cloud']+=part.html;}
   }
   ready=true;if(completed||pendingConflictResult?.id===d.id)return;render();
  }).catch(error=>{if(!completed&&pendingConflictResult?.id!==d.id)summary.textContent=`无法计算差异：${error.message}。仍可选择整篇版本。`;});
 }
 function askNew(){remember();const input=document.createElement('input');input.placeholder='给笔记起个名字';input.maxLength=80;input.required=true;
  const submit=()=>{const name=input.value.trim();if(name)navigate('new',{title:name});};
  modal('new','新建笔记',[input],[cancel,['创建',submit,'primary']]);
  input.onkeydown=e=>{if(e.key==='Enter'&&!e.isComposing){e.preventDefault();if(input.value.trim()){dialog.close();submit();}}};setTimeout(()=>input.focus(),0);
 }
 function deleteNote(targetId=id,targetTitle=active?.title){if(!targetId)return;modal('delete','删除笔记',[text('p',`“${targetTitle}”将从本机和 OneDrive 移除。此操作无法在应用内撤销。`)],[cancel,['确认删除',()=>barrier(()=>{const n=notes.get(targetId);if(n)dispose(n);notes.delete(targetId);navigate('delete',{id:targetId});}),'danger']]);}
 const tools=[['type-bold','加粗','bold'],['type-italic','斜体','italic'],['type-underline','下划线','underline'],['type-strikethrough','删除线','strikeThrough'],['code-slash','代码',()=>{const s=getSelection()?.toString()||'代码';const code=text('code',s);insert(code.outerHTML);}],null,
  [null,'一级标题',()=>command('formatBlock','h1'),'H1'],[null,'二级标题',()=>command('formatBlock','h2'),'H2'],[null,'三级标题',()=>command('formatBlock','h3'),'H3'],null,
  ['list-ul','无序列表','insertUnorderedList'],['list-ol','有序列表','insertOrderedList'],['check2-square','待办事项',task],['table','插入表格',()=>{
   remember();const rows=document.createElement('input'),cols=document.createElement('input');rows.type=cols.type='number';rows.min=cols.min='1';rows.max=cols.max='30';rows.value='3';cols.value='3';
   modal('table','插入表格',[text('p','行数 / 列数（1–30）'),rows,cols],[cancel,['插入',()=>{const r=Math.min(30,Math.max(1,Number(rows.value)||3)),c=Math.min(30,Math.max(1,Number(cols.value)||3));let h='<table><tbody>';for(let i=0;i<r;i++){h+='<tr>';for(let j=0;j<c;j++)h+=i===0?'<th>标题</th>':'<td><br></td>';h+='</tr>';}insert(h+'</tbody></table><p><br></p>');},'primary']]);
  }],null,['unindent','减少缩进','outdent'],['indent','增加缩进','indent'],['arrow-counterclockwise','撤销','undo'],['arrow-clockwise','重做','redo'],['eraser','清除格式','removeFormat']];
 const toolbar=document.getElementById('toolbar');
 for(const tool of tools){if(!tool){const s=document.createElement('span');s.className='separator';toolbar.append(s);continue;}const [icon,label,cmd,caption]=tool,b=document.createElement('button');b.title=label;b.setAttribute('aria-label',label);if(icon){const i=document.createElement('i');i.className=`bi bi-${icon}`;b.append(i);}else b.textContent=caption;b.onmousedown=e=>{e.preventDefault();remember();};b.onclick=()=>typeof cmd==='function'?cmd():command(cmd);toolbar.append(b);}
 document.getElementById('save').onclick=()=>action('save');
 document.getElementById('print').onclick=()=>{if(id)post({type:'print'});};
 document.getElementById('export').onclick=()=>{const n=active;if(n)setTimeout(()=>post({type:'export',id:n.id,title:n.title,html:n.node.innerHTML}),0);};
 onEditor('input',changed);
 onEditor('compositionstart',()=>{composing=true;});
 onEditor('compositionend',()=>{composing=false;changed();if(pendingLoad){const e=pendingLoad;pendingLoad=null;receive(e);}});
 onEditor('blur',()=>{remember();if(active&&pending(active))schedule(active,0);});
 document.addEventListener('selectionchange',remember);
 onEditor('change',e=>{if(e.target.matches('input[type=checkbox]')){e.target.toggleAttribute('checked',e.target.checked);changed();}});
 onEditor('click',e=>{if(e.target.tagName==='IMG'){e.preventDefault();return;}const a=e.target.closest('a');if(a){e.preventDefault();if(safeURL(a.href))post({type:'link',url:a.href});}});
 onEditor('keydown',e=>{
  if(e.isComposing)return;
  syncImageSelection();
  if(selectedImage&&e.key==='Enter'){e.preventDefault();viewImage();return;}
  if(selectedImage&&['Backspace','Delete'].includes(e.key)){e.preventDefault();if(!imagePending)removeImage();return;}
  if(e.key==='Escape'&&selectedImage){e.preventDefault();getSelection().collapseToEnd();remember();clearImageSelection();return;}
  if(e.key==='Tab'){const node=getSelection()?.anchorNode;const el=node?.nodeType===1?node:node?.parentElement;if(el?.closest('li')){e.preventDefault();command(e.shiftKey?'outdent':'indent');}return;}
  if(e.key!==' ')return;
  const s=getSelection();if(!s?.isCollapsed||!s.rangeCount||s.anchorNode.nodeType!==3)return;
  const node=s.anchorNode,offset=s.anchorOffset,prefix=node.textContent.slice(0,offset);
  const patterns={'#':['formatBlock','h1'],'##':['formatBlock','h2'],'###':['formatBlock','h3'],'-':['insertUnorderedList'],'*':['insertUnorderedList'],'1.':['insertOrderedList'],'>':['formatBlock','blockquote']};
  if(!patterns[prefix]&&!['[]','[ ]','- [ ]'].includes(prefix))return;
  e.preventDefault();const r=s.getRangeAt(0);r.setStart(node,0);r.setEnd(node,offset);s.removeAllRanges();s.addRange(r);document.execCommand('delete');selection=null;remember();if(patterns[prefix])command(...patterns[prefix]);else task();
 });
 onEditor('paste',e=>{
  const clipboard=e.clipboardData;if(!clipboard)return;e.preventDefault();remember();
  const image=[...clipboard.items].find(i=>i.type.startsWith('image/'));
  if(image){const file=image.getAsFile();if(!file||file.size>8*1024*1024){receive({type:'error',message:'图片最大支持 8 MB，请缩小后重试。'});return;}const target=id;const reader=new FileReader();reader.onload=()=>{if(target!==id)return;const img=document.createElement('img');img.src=reader.result;img.alt='粘贴的图片';insert(img.outerHTML);};reader.readAsDataURL(file);return;}
  const html=clipboard.getData('text/html'),plain=clipboard.getData('text/plain');if(html)insert(sanitize(html));else if(safeURL(plain.trim())){const a=text('a',plain.trim());a.href=plain.trim();insert(a.outerHTML);}else command('insertText',plain);
 });
 const menu=document.getElementById('context');
 const tableActions=[['在上方插入行','row-before'],['在下方插入行','row-after'],['在左侧插入列','col-before'],['在右侧插入列','col-after'],['删除行','delete-row'],['删除列','delete-col']];
 function tableAction(op){const cell=contextCell;if(!cell)return;const row=cell.parentElement,table=cell.closest('table'),index=cell.cellIndex;
  if(op.startsWith('row')){const next=document.createElement('tr');for(let i=0;i<row.cells.length;i++){const td=document.createElement('td');td.innerHTML='<br>';next.append(td);}if(op==='row-before')row.before(next);else row.after(next);}
  else if(op==='delete-row')row.remove();else for(const r of [...table.rows]){if(op==='delete-col')r.cells[index]?.remove();else{const td=document.createElement(r.parentElement.tagName==='THEAD'?'th':'td');td.innerHTML='<br>';r.insertBefore(td,r.cells[index+(op==='col-after'?1:0)]||null);}}
  if(!table.rows.length||![...table.rows].some(r=>r.cells.length))table.remove();menu.hidden=true;changed();
 }
 onEditor('contextmenu',e=>{
  const image=e.target.closest('img'),cell=e.target.closest('td,th');if(!image&&!cell)return;e.preventDefault();menu.replaceChildren();
  const entries=image?[['放大查看',()=>viewImage(image)],['复制图片',()=>imageClipboard()],['剪切图片',()=>imageClipboard(true)],['删除图片',()=>removeImage(image)]]:tableActions.map(([label,op])=>[label,()=>tableAction(op)]);
  if(image)selectImage(image);else contextCell=cell;
  for(const [label,action]of entries){const b=text('button',label);b.type='button';b.onmousedown=event=>event.preventDefault();b.onclick=()=>{menu.hidden=true;action();};menu.append(b);}
  menu.hidden=false;menu.style.left=Math.max(0,Math.min(e.clientX,innerWidth-menu.offsetWidth-8))+'px';menu.style.top=Math.max(0,Math.min(e.clientY,innerHeight-menu.offsetHeight-8))+'px';
 });
 document.addEventListener('mousedown',e=>{if(!menu.contains(e.target))menu.hidden=true;});
 document.addEventListener('keydown',e=>{if((e.metaKey||e.ctrlKey)&&!e.isComposing){if(e.key.toLowerCase()==='s'){e.preventDefault();action('save');}if(e.key.toLowerCase()==='n'){e.preventDefault();action('new');}if(e.key.toLowerCase()==='q'){e.preventDefault();action('close');}if(e.key==='\\'){e.preventDefault();post({type:'sidebar'});}}});
 function action(type,data={}){
  if(composing){editor.blur();composing=false;}
  switch(type){
   case 'open': navigate(type,data);break;
   case 'review':if(!dialog.open||dialogKind!=='conflict')barrier(()=>post({type:'conflict_view',id:data.id||id}));break;
   case 'new':askNew();break;
   case 'delete':deleteNote(data.id,data.title);break;
   case 'focus':document.body.classList.toggle('focus');break;
   case 'save':{const n=active;setTimeout(()=>{flush(n,true);post({type:'save',id:n?.id||''});},0);break;}
   case 'install_update':barrier(()=>post({type:'install_update'}));break;
   case 'close':case 'login':case 'logout':case 'commit_login':barrier(()=>{
    if(type==='logout'||type==='commit_login'){clearImageSelection();for(const n of notes.values())dispose(n);notes.clear();active=null;id='';selection=null;}
    navigate(type,data);
   });break;
   default:post({type,id,...data});
  }
 }
 function receive(e){
  switch(e.type){
   case 'image_clipboard_result':{
    const request=imagePending;if(!request||request.token!==e.token)break;
    imagePending=null;positionImageActions();
    if(!e.ok){imageStatus(`图片复制失败：${e.message||'系统剪贴板不可用'}。原图已保留。`,true);break;}
    if(request.cut){
     if(id!==request.id||!editor.contains(request.image)||request.image.getAttribute('src')!==request.src){imageStatus('图片已复制；笔记或图片已变化，未移除原图。');break;}
     imageStatus(removeImage(request.image)?'图片已剪切 · ⌘Z 可撤销':'图片已复制，未能移除原图，请重试剪切。');
    }else imageStatus('图片已复制，可粘贴到笔记或其他应用。');break;
   }
   case 'invalidate':{const n=notes.get(e.id);if(n&&!pending(n)){n.stale=true;n.node.contentEditable='false';}break;}
   case 'load':{
    if(e.intent!==undefined&&e.intent<intent)return;if(e.intent!==undefined)intent=e.intent;
    if(composing){pendingLoad=e;return;}
    if(!e.id){
     clearImageSelection();
     if(active){active.pane.classList.remove('active');active.pane.setAttribute('aria-hidden','true');}active=null;id='';selection=null;editor.removeAttribute('id');
     document.getElementById('document').hidden=true;document.getElementById('empty').style.display='flex';break;
    }
    let n=notes.get(e.id);
    if(n&&pending(n)){activate(n);return;}
    if(!n)n=record(e.id,e.title,e.html);
    else if(!n.loaded||n.source!==e.html){
     clearImageSelection();n.node.innerHTML=sanitize(e.html);n.source=e.html;n.bytes=e.html.length;n.loaded=true;n.node.contentEditable='true';n.selection=null;
    }
    n.stale=false;n.node.contentEditable='true';n.title=e.title;activate(n);deferCount();break;
   }
   case 'saved':{
    const n=notes.get(e.id);if(!n)break;
    n.saved=Math.max(n.saved,Math.min(n.seq,e.seq));
    if(e.title!==undefined&&e.seq===n.seq)n.title=e.title;
    if(!pending(n)){pendingWrites.delete(n.id);post({type:'settled',id:n.id});prune();}
    if(!pendingWrites.size){updateGuard();if(pendingAction){const next=pendingAction;pendingAction=null;queueMicrotask(next);}}
    if(n===active){document.getElementById('status').textContent='已保存到本机';document.getElementById('status').style.color='';}
    break;
   }
   case 'save_failed':{
    if(pendingConflictResult)pendingConflictResult.receive({ok:false,message:'本机草稿保存失败：'+e.message});
    const n=notes.get(e.id);if(n&&e.seq>n.saved){n.sent=n.saved;pendingAction=null;schedule(n,3000);}
    document.getElementById('status').textContent=`${n?.title||'笔记'}：保存失败，草稿保留在内存中，将自动重试。${e.message}`;
    document.getElementById('status').style.color='#ca5262';break;
   }
   case 'state':if(e.intent===undefined||e.intent>=intent){document.getElementById('status').textContent=e.status;document.getElementById('status').style.color='';}break;
   case 'error':document.getElementById('status').textContent=e.message;document.getElementById('status').style.color='#ca5262';break;
   case 'dialog':{
    const d=e.data;
    if(e.kind==='conflict_result'){if(pendingConflictResult?.id===d.id)pendingConflictResult.receive(d);return;}
    if(e.kind==='conflict')conflictDialog(d);
    if(e.kind==='conflict_view')showConflict(d);
    if(e.kind==='login_done'){window.loginFinished=true;if(dialogKind==='login')dialog.close();return;}
    if(e.kind==='login'){window.loginFinished=false;const code=text('div',d.code);code.className='login-code';modal('login','连接 OneDrive',[text('p','在浏览器中登录 Microsoft 账号，并输入以下设备码。'),code],[['取消登录',()=>post({type:'logout'})],['打开 Microsoft 登录',()=>{post({type:'link',url:d.url});return false;},'primary']]);post({type:'link',url:d.url});}
    break;
   }
  }
 }
 window.cnote={action,receive,flush,flushAll,sanitize,focusEditor:restore,
  setTheme(theme){document.documentElement.dataset.theme=theme;},
  insertImage(src){if(id&&/^data:image\/(png|jpeg|webp|gif|bmp);base64,/i.test(src)){const img=document.createElement('img');img.src=src;img.alt='图片';insert(img.outerHTML);}},
  dismissOverlay(){
   if(dialog.open){if(!pendingConflictResult)dialog.close();return true;}
   if(!menu.hidden){menu.hidden=true;return true;}
   if(selectedImage){getSelection().collapseToEnd();remember();clearImageSelection();return true;}
   return false;
  },
  review(noteId){barrier(()=>post({type:'conflict_view',id:noteId}));}
 };post({type:'ready'});
})();
