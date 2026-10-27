const {diffArrays}=require('diff');
function compareNote({left,right}) {
 if(left.length+right.length>40*1024*1024)throw Error('内容超过对比上限，请先导出草稿');
 const tokens=html=>html.split(/(?<=<\/(?:p|div|h[1-6]|li|tr|table|ul|ol|blockquote|pre)>|<br>|<hr>|\n)/i);
 const parts=diffArrays(tokens(left),tokens(right),{timeout:1500,maxEditLength:10000});
 if(!parts)throw Error('差异过多，可选择整篇版本或先导出处理');
 return parts.map(part=>({kind:part.added?'added':part.removed?'removed':'same',html:part.value.join('')}));
}
if(typeof self!=='undefined')self.onmessage=event=>{try{self.postMessage({parts:compareNote(event.data)});}catch(error){self.postMessage({error:error.message});}};
module.exports={compareNote};
