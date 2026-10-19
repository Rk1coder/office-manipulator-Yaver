const fs = require('node:fs');
const vm = require('node:vm');
const test = require('node:test');
const assert = require('node:assert/strict');
const html = fs.readFileSync(__dirname + '/../../main/resources/static/index.html', 'utf8');
const source = [...html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/g)].map(m => m[1]).find(s => s.includes('class Component extends DCLogic'));
function setup(fetch) {
  class Base { constructor() { this.props = {}; } setState(update) { Object.assign(this.state, typeof update === 'function' ? update(this.state) : update); } }
  const context = {DCLogic: Base, React: {createRef: () => ({current: null})}, console, Date, Blob, AbortController, setTimeout, clearTimeout, fetch,
    window: {innerWidth: 1200}, localStorage: {getItem: () => null, setItem: () => {}}};
  vm.createContext(context);
  vm.runInContext(source + '\nglobalThis.App = Component;', context);
  const app = new context.App();
  app.state.settingsOpen = true;
  app.state.settingsModelInput = 'claude-sonnet-4-6';
  app.state.settingsData = {claudeKeyConfigured: false, claudeKeyVerified: false, model: 'claude-sonnet-4-6', availableModels: []};
  return app;
}
test('a configured but unchecked key is not presented as active', () => {
  const app = setup();
  app.state.settingsData.claudeKeyConfigured = true;
  assert.match(app.renderVals().apiStatusLabel, /doğrulanmadı|Not verified/i);
});
test('failed verification keeps settings open and preserves the active configuration', async () => {
  const app = setup(async () => ({ok: false, json: async () => ({error: 'Anahtar geçersiz'})}));
  const original = app.state.settingsData;
  app.state.settingsClaudeKeyInput = 'bad-key';
  await app.verifySettings();
  assert.equal(app.state.settingsData, original);
  assert.equal(app.state.settingsOpen, true);
  assert.equal(app.state.settingsActivated, false);
  assert.equal(app.state.settingsVerifying, false);
  assert.match(app.state.settingsError, /geçersiz/);
});
test('only successful verification opens the activation confirmation and clears the input', async () => {
  let request;
  const app = setup(async (url, opts) => { request = {url, body: JSON.parse(opts.body)}; return {ok: true, json: async () => ({claudeKeyConfigured: true, claudeKeyVerified: true, model: 'claude-sonnet-4-6', availableModels: []})}; });
  app.state.settingsClaudeKeyInput = 'candidate';
  await app.verifySettings();
  assert.equal(request.url, '/api/settings/verify');
  assert.equal(request.body.claudeApiKey, 'candidate');
  assert.equal(app.state.settingsActivated, true);
  assert.equal(app.state.settingsClaudeKeyInput, '');
  assert.equal(app.state.settingsData.claudeKeyVerified, true);
});
test('saving optional settings does not submit or clear Claude credentials', async () => {
  let request;
  const app = setup(async (url, opts) => { request = JSON.parse(opts.body); return {ok: true, json: async () => ({claudeKeyConfigured: true, claudeKeyVerified: true, availableModels: []})}; });
  app.showToast = () => {};
  app.fetchOfficeCapabilities = () => {};
  app.state.settingsClaudeKeyInput = 'unverified-new-key';
  await app.saveSettings();
  assert.equal(request.claudeApiKey, undefined);
  assert.equal(request.model, undefined);
  assert.equal(app.state.settingsClaudeKeyInput, 'unverified-new-key');
});
test('network failure never displays activation and permits retry', async () => {
  const app = setup(async () => { throw new Error('offline'); });
  await app.verifySettings();
  assert.equal(app.state.settingsActivated, false);
  assert.equal(app.state.settingsVerifying, false);
  assert.equal(app.state.settingsError, 'offline');
});
test('an HTTP success without verified status cannot activate', async () => {
  const app = setup(async () => ({ok:true, json:async () => ({claudeKeyConfigured:true,claudeKeyVerified:false})}));
  await app.verifySettings();
  assert.equal(app.state.settingsActivated, false);
  assert.ok(app.state.settingsError);
});
test('duplicate clicks cannot start simultaneous verification requests', async () => {
  let calls = 0, finish;
  const app = setup(() => {calls++; return new Promise(resolve => {finish = resolve;});});
  const pending = app.verifySettings();
  await app.verifySettings();
  assert.equal(calls, 1);
  assert.equal(app.renderVals().settingsVerifyDisabled, true);
  finish({ok:false,json:async () => ({error:'Rejected'})});
  await pending;
  assert.equal(app.state.settingsVerifying, false);
});
