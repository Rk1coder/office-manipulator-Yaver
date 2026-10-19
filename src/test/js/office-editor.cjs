const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = path.join(__dirname, '../../main/resources/static/office-core.js');
const ctx = { setTimeout, clearTimeout, AbortController };
vm.createContext(ctx);
if (fs.existsSync(source)) vm.runInContext(fs.readFileSync(source, 'utf8'), ctx);
const api = ctx.OfficeCore || {};

test('save request is not a saved revision; poll until callback advances revision', async () => {
  const events = [];
  const statuses = [{revision: 4}, {revision: 4, savedJobId:'old'}, {revision:5,savedJobId:'new'}];
  const result = await api.saveAndWait(async (url, options) => {
    events.push(options?.method || 'GET');
    return options?.method === 'POST' ? {requested:true} : statuses.shift();
  }, 'session', {delay: async()=>{}, attempts:3});
  assert.equal(result.savedJobId, 'new');
  assert.deepEqual(events, ['GET','POST','GET','GET']);
});
test('save times out without claiming old saved job as new', async () => {
  await assert.rejects(() => api.saveAndWait(async (_, options) => options?.method === 'POST' ? {requested:true} : {revision:4,savedJobId:'old'}, 'session', {delay:async()=>{}, attempts:2}), /zaman|onay/i);
});
test('callback error is surfaced instead of declaring success', async () => {
  let calls=0;
  await assert.rejects(() => api.saveAndWait(async()=> ++calls === 1 ? {revision:0} : calls === 2 ? {requested:true} : {revision:0,error:'storage failed'},'session',{delay:async()=>{}}), /storage failed/);
});
test('HTTP errors and request timeouts surface actionable failures',async()=>{
  ctx.fetch=async()=>({ok:false,status:503,json:async()=>({error:'Office unavailable'})});
  await assert.rejects(()=>api.requestJson('/test'),/Office unavailable/);
  ctx.fetch=(_,options)=>new Promise((_,reject)=>options.signal.addEventListener('abort',()=>{const e=new Error();e.name='AbortError';reject(e);}));
  await assert.rejects(()=>api.requestJson('/test',{},1),/zaman aşımı/);
});
function editorHarness() {
  const elements = new Map(), listeners = {};
  function element(id) {if (!elements.has(id)) elements.set(id,{textContent:'',hidden:true,disabled:true,classList:{toggle(){}},addEventListener(name,fn){this[name]=fn;}});return elements.get(id);}
  let finishSave, config;
  const context = {URLSearchParams,Date,encodeURIComponent,setTimeout,clearTimeout,
    location:{search:'?jobId=11111111-1111-1111-1111-111111111111'},
    OfficeCore:{requestJson:async()=>({sessionId:'s',apiScriptUrl:'/sdk.js',config:{}}),saveAndWait:()=>new Promise(resolve=>finishSave=resolve)},
    document:{getElementById:element,createElement:()=>({}),head:{appendChild:script=>script.onload?.()}},
    window:{addEventListener:(name,fn)=>listeners[name]=fn},
    OfficeChatPanel:{createDocumentChatPanel:()=>({toggle(){}})},
    DocsAPI:{DocEditor:function(id,settings){config=settings;settings.events.onDocumentReady();this.destroyEditor=()=>{};}}
  };
  context.window.DocsAPI=context.DocsAPI;
  context.window.parent=context.window;
  vm.createContext(context);
  vm.runInContext(fs.readFileSync(path.join(__dirname,'../../main/resources/static/office-editor.js'),'utf8'),context);
  return {element,listeners,config:()=>config,finish:(result={revision:1,savedJobId:'saved',savedAt:'2026-01-01T00:00:00Z'})=>finishSave(result)};
}
test('changes made while saving remain unsaved after the earlier callback',async()=>{
  const h=editorHarness();await new Promise(resolve=>setImmediate(resolve));
  h.config().events.onDocumentStateChange({data:true});
  const saving=h.element('save').click();
  h.config().events.onDocumentStateChange({data:true});
  h.finish();await saving;
  let prevented=false;h.listeners.beforeunload({preventDefault(){prevented=true;}});
  assert.equal(prevented,true,'new edits must still trigger unload protection');
  assert.match(h.element('status').textContent,/yeni|sonraki/i);
});
test('save UI displays confirmed download only after callback completion',async()=>{
  const h=editorHarness();await new Promise(resolve=>setImmediate(resolve));
  h.config().events.onDocumentStateChange({data:true});
  const saving=h.element('save').click();
  assert.equal(h.element('download').hidden,true);
  assert.equal(h.element('save').disabled,true);
  h.finish();await saving;
  assert.equal(h.element('download').hidden,false);
  assert.equal(h.element('download').href,'/api/files/saved/download');
  let prevented=false;h.listeners.beforeunload({preventDefault(){prevented=true;}});
  assert.equal(prevented,false);
});

test('unchanged save response exits without polling for a nonexistent revision',async()=>{
  let calls=0;
  const result=await api.saveAndWait(async()=>++calls===1?{revision:0,savedJobId:null}:{requested:false,unchanged:true},'session',{delay:async()=>assert.fail('must not poll')});
  assert.equal(result.unchanged,true);assert.equal(calls,2);
});
// Selection proposal behavior lives in office-chat-panel.cjs; bridge transport is tested separately.

test('duplicate-content save completes when its request is acknowledged without revision advance',async()=>{
  const statuses=[{revision:4,savedJobId:'existing',acknowledgedRequest:'older-request'},
    {revision:4,savedJobId:'existing',acknowledgedRequest:'older-request'},
    {revision:4,savedJobId:'existing',acknowledgedRequest:'this-request'}];
  const result=await api.saveAndWait(async(_,options)=>options?.method==='POST'?{requested:true,requestId:'this-request'}:statuses.shift(),'session',{delay:async()=>{},attempts:2});
  assert.equal(result.revision,4);
  assert.equal(result.savedJobId,'existing');
  assert.equal(result.acknowledgedRequest,'this-request');
});
test('an unrelated callback advancing revision does not acknowledge the requested save',async()=>{
  let reads=0;
  await assert.rejects(()=>api.saveAndWait(async(_,options)=>options?.method==='POST'?{requested:true,requestId:'this-request'}:
    ++reads===1?{revision:4,savedJobId:'existing'}:{revision:5,savedJobId:'other',acknowledgedRequest:'other-request'},'session',{delay:async()=>{},attempts:2}),/onay|zaman/i);
});

test('unchanged editor response keeps unsaved edits and does not publish an older saved version',async()=>{
  const h=editorHarness();await new Promise(resolve=>setImmediate(resolve));
  h.config().events.onDocumentStateChange({data:true});
  const saving=h.element('save').click();
  h.finish({revision:7,savedJobId:'older',unchanged:true});await saving;
  let prevented=false;h.listeners.beforeunload({preventDefault(){prevented=true;}});
  assert.equal(prevented,true);
  assert.equal(h.element('download').hidden,true);
  assert.match(h.element('status').textContent,/birkaç saniye sonra/i);
});
