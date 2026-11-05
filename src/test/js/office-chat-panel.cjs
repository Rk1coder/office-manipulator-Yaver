const test=require('node:test');const assert=require('node:assert/strict');const fs=require('node:fs');const vm=require('node:vm');
function harness(options={}){
 const elements=new Map();const el=key=>{if(!elements.has(key))elements.set(key,{value:'',innerHTML:'',classList:{toggle(){}},addEventListener(name,fn){this[name]=fn;}});return elements.get(key);};
 const mount={...el('mount'),querySelector:el};let selected='Original';const pasted=[];let requested=0;
 const ctx={};vm.createContext(ctx);vm.runInContext(fs.readFileSync('src/main/resources/static/office-chat-panel.js','utf8'),ctx);
 ctx.OfficeChatPanel.createDocumentChatPanel({mount,captureSelection:async()=>selected,requestProposal:async()=>{requested++;return {text:options.proposal||'<b>New</b>',formatting:options.formatting||null};},applyText:async text=>{pasted.push(text);if(options.failPaste)throw Error('timeout');}});
 const send=async()=>{el('.doc-chat-input').value='Kısalt';await el('.doc-chat-send').click();};
 const apply=async()=>el('.doc-chat-history').click({target:{closest:selector=>selector==='.doc-chat-apply'?{dataset:{id:'2'}}:null}});
 const toggle=async(kind,id)=>el('.doc-chat-history').click({target:{closest:selector=>selector==='[data-toggle]'?{dataset:{id:String(id),toggle:kind}}:null}});
 return{el,pasted,send,apply,toggle,select:s=>selected=s,requests:()=>requested};
}
test('proposal escapes markup and applies only once after explicit approval',async()=>{const h=harness();await h.send();assert.deepEqual(h.pasted,[]);assert.match(h.el('.doc-chat-history').innerHTML,/&lt;b&gt;New/);await h.apply();await h.apply();assert.deepEqual(h.pasted,['<b>New</b>']);});
test('changed selection cannot receive a stale proposal',async()=>{const h=harness();await h.send();h.select('Different');await h.apply();assert.deepEqual(h.pasted,[]);assert.match(h.el('.doc-chat-history').innerHTML,/seçim/i);});
test('uncertain paste is consumed and cannot be repeated',async()=>{const h=harness({failPaste:true});await h.send();await h.apply();await h.apply();assert.equal(h.pasted.length,1);});
test('oversized selection does not call the API',async()=>{const h=harness();h.select('x'.repeat(20001));await h.send();assert.equal(h.requests(),0);});
test('thread shows the proposal with a collapsed tool chip, timestamps and expandable diff',async()=>{const h=harness();await h.send();const html=()=>h.el('.doc-chat-history').innerHTML;
 assert.match(html(),/düzenleme önerisi/);assert.match(html(),/HAZIR/);assert.match(html(),/sn'de hazırladı/);assert.match(html(),/doc-chat-time">\d\d:\d\d</);
 assert.doesNotMatch(html(),/doc-chat-diff-old/,'diff starts collapsed');await h.toggle('tool',2);assert.match(html(),/doc-chat-diff-old[\s\S]*Original/);
 await h.toggle('thought',2);assert.match(html(),/Talimat<\/b> · Kısalt/);await h.apply();assert.match(html(),/UYGULANDI/);});
test('formatting-only proposals list formatting as numbered chips',async()=>{const h=harness({proposal:'Original',formatting:{bold:true,color:'1F6F50'}});await h.send();
 const html=h.el('.doc-chat-history').innerHTML;assert.match(html,/doc-chat-chip-num">1<\/span>kalın/);assert.match(html,/renk #1F6F50/);assert.match(html,/yalnızca biçim değişir|doc-chat-tool/);});
test('new chat clears the thread',async()=>{const h=harness();await h.send();await h.el('.doc-chat-reset').click();assert.match(h.el('.doc-chat-history').innerHTML,/Yaver belge asistanı/);});
