(function () {
  'use strict';
  const $ = id => document.getElementById(id);
  const request = OfficeCore.requestJson;
  let session, editor, panel, saving = false, ready = false, hasLocalChanges = false, persistedRevision = 0, changeEpoch = 0;
  const jobId = new URLSearchParams(location.search).get('jobId');
  function notifyParent(data) {if (window.parent !== window) window.parent.postMessage({...data,jobId}, location.origin);}

  // Bridges GetSelectedText/PasteText to the headless ONLYOFFICE plugin (office-plugin/bridge.js).
  // The plugin iframe is created by ONLYOFFICE itself, so we never hold a direct reference to it —
  // instead it announces itself once on load, and we remember its window via event.source.
  const bridge = (() => {
    let bridgeWindow = null;
    let nextId = 1;
    const pending = new Map();
    const readyWaiters = [];
    const BRIDGE_READY_TIMEOUT_MS = 25000;
    window.addEventListener('message', event => {
      if (event.origin !== location.origin || !event.data || typeof event.data !== 'object') return;
      if (event.data.type === 'yaver-bridge-ready') {
        bridgeWindow = event.source;
        readyWaiters.splice(0).forEach(w => w.resolve());
        return;
      }
      if (event.data.type === 'yaver-bridge-toggle-panel') { if (panel) panel.toggle(); return; }
      const waiter = pending.get(event.data.requestId);
      if (!waiter) return;
      pending.delete(event.data.requestId);
      if (event.data.type === 'yaver-bridge-selection-result') waiter.resolve(event.data.text);
      else if (event.data.type === 'yaver-bridge-paste-done') waiter.resolve();
      else if (event.data.type === 'yaver-bridge-formatting-done') waiter.resolve();
      else if (event.data.type === 'yaver-bridge-error') waiter.reject(new Error(event.data.message || 'Köprü eklentisi hata verdi.'));
    });
    // The ONLYOFFICE plugin sandbox (bridge.js) announces itself once it's loaded, which can take
    // a few seconds after the chat panel is already visible. Instead of failing instantly, wait
    // quietly for that handshake (usually sub-second once the user has had the editor open a bit)
    // and only report a real, non-retryable error if it never completes.
    function waitForReady() {
      if (bridgeWindow) return Promise.resolve();
      return new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
          const idx = readyWaiters.findIndex(w => w.resolve === resolve);
          if (idx !== -1) readyWaiters.splice(idx, 1);
          reject(new Error('Yaver eklentisi başlatılamadı. Sayfayı yenileyin; sorun devam ederse yöneticinizle iletişime geçin.'));
        }, BRIDGE_READY_TIMEOUT_MS);
        readyWaiters.push({ resolve: () => { clearTimeout(timer); resolve(); } });
      });
    }
    async function call(type, extra) {
      await waitForReady();
      return new Promise((resolve, reject) => {
        const requestId = nextId++;
        const timer = setTimeout(() => { pending.delete(requestId); reject(new Error('Düzenleyici yanıt vermedi.')); }, 15000);
        pending.set(requestId, { resolve: v => { clearTimeout(timer); resolve(v); }, reject: e => { clearTimeout(timer); reject(e); } });
        bridgeWindow.postMessage({ type, requestId, ...extra }, location.origin);
      });
    }
    return {
      captureSelection: () => call('yaver-bridge-get-selection'),
      applyText: text => call('yaver-bridge-paste-text', { text }),
      applyFormatting: formatting => call('yaver-bridge-apply-formatting', { formatting })
    };
  })();
  function status(text, error = false) { $('status').textContent = text; $('status').classList.toggle('error', error); }
  function esc(s) { return String(s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c])); }
  async function loadVersions() {
    if (!session) return;
    try {
      const list = await request('/api/office/sessions/' + encodeURIComponent(session.sessionId) + '/versions');
      $('versions-list').innerHTML = list.map(v => `<li class="${v.current ? 'current' : ''}">
          <span>Sürüm ${v.index}${v.savedAt ? ' · ' + new Date(v.savedAt).toLocaleString('tr-TR') : ''}${v.current ? ' (güncel)' : ''}</span>
          ${v.current ? '' : `<button type="button" data-job="${esc(v.jobId)}">Geri yükle</button>`}
        </li>`).join('') || '<li>Henüz kaydedilmiş sürüm yok.</li>';
    } catch (error) { $('versions-list').innerHTML = '<li>' + esc(error.message) + '</li>'; }
  }
  $('versions-list').addEventListener('click', async event => {
    const button = event.target.closest('button[data-job]');
    if (!button || !session) return;
    if (!window.confirm('Bu sürüme geri dönülsün mü? Mevcut açık düzenleyici kapanıp bu sürümle yeniden açılacak.')) return;
    button.disabled = true;
    try {
      await request('/api/office/sessions/' + encodeURIComponent(session.sessionId) + '/versions/' + encodeURIComponent(button.dataset.job) + '/restore', {method:'POST'});
      location.reload();
    } catch (error) { status(error.message, true); button.disabled = false; }
  });
  $('toggle-versions').addEventListener('click', () => { const open = $('versions-panel').hidden; $('versions-panel').hidden = !open; if (open) loadVersions(); });
  $('close-versions').addEventListener('click', () => { $('versions-panel').hidden = true; });
  function saved(result) {
    if (result.error) throw new Error(result.error);
    const revision = Number(result.revision) || 0;
    if (!result.savedJobId || revision < 1) return false;
    persistedRevision = Math.max(persistedRevision, revision);
    const timestamp = result.savedAt ? new Date(result.savedAt).toLocaleString('tr-TR') : '';
    $('revision').textContent = 'Yaver’de kayıtlı sürüm: ' + revision + (timestamp ? ' · ' + timestamp : '');
    $('download').href = '/api/files/' + encodeURIComponent(result.savedJobId) + '/download';
    $('download').hidden = false;
    notifyParent({type:'yaver-office-saved',savedJobId:result.savedJobId,revision});
    if (!$('versions-panel').hidden) loadVersions();
    return true;
  }
  function loadApi(url) {
    return new Promise((resolve, reject) => {
      const script = document.createElement('script');
      const timer = setTimeout(() => reject(new Error('ONLYOFFICE yüklenemedi. Belge sunucusunun erişilebilir olduğunu kontrol edin.')), 30000);
      script.src = url;
      script.onload = () => {clearTimeout(timer); resolve();};
      script.onerror = () => {clearTimeout(timer); reject(new Error('ONLYOFFICE betiği yüklenemedi. Belge sunucusunu kontrol edin.'));};
      document.head.appendChild(script);
    });
  }
  $('save').addEventListener('click', async () => {
    if (!ready || saving) return;
    const savingEpoch = changeEpoch;
    saving = true; $('save').disabled = true; $('refresh').disabled = true;
    status('Kaydetme istendi. Yaver’in yeni sürümü aldığını doğrulaması bekleniyor…');
    try {
      const result = await OfficeCore.saveAndWait(request, session.sessionId);
      if (result.unchanged) {
        status('Düzenleyici henüz kaydedilecek değişiklik bildirmedi; birkaç saniye sonra yeniden deneyin.');
        return;
      }
      saved(result);
      hasLocalChanges = changeEpoch !== savingEpoch;
      if (hasLocalChanges) status('Kayıt işlemi tamamlandı. Kaydetme sırasında yeni değişiklikler yapıldı; yeniden Yaver’e kaydedin.');
      else status('Sürüm ' + result.revision + ' Yaver’e kaydedildi. Yeni sürümü indirebilirsiniz.');
    } catch (error) { status(error.message, true); }
    finally {saving = false; $('save').disabled = !ready; $('refresh').disabled = false;}
  });
  $('refresh').addEventListener('click', async () => {
    if (!session || saving) return;
    $('refresh').disabled = true;
    try {
      const result = await request('/api/office/sessions/' + encodeURIComponent(session.sessionId));
      const previous = persistedRevision;
      const found = saved(result);
      if (found && persistedRevision > previous) status('Yeni kayıt doğrulandı: sürüm ' + persistedRevision + '. Son düzenlemeler için yeniden kaydedebilirsiniz.');
      else status(found ? 'Kayıtlı sürüm: ' + persistedRevision + '. Daha yeni bir kaydetme onayı yok.' : 'Henüz Yaver’e kaydedilmiş yeni bir sürüm yok.');
    } catch (error) {status(error.message, true);}
    finally {$('refresh').disabled = false;}
  });
  $('close').addEventListener('click', () => {
    if ((saving || hasLocalChanges) && !window.confirm('Yaver’e kaydedilmemiş değişiklikler olabilir. Yine de önizlemeye dönmek istiyor musunuz?')) return;
    if (window.parent !== window) notifyParent({type:'yaver-office-close'});
    else location.href = '/';
  });
  window.addEventListener('beforeunload', event => {if (saving || hasLocalChanges) {event.preventDefault(); event.returnValue = '';}});
  window.addEventListener('pagehide', () => {if (editor) editor.destroyEditor();});
  (async function init() {
    try {
      if (!jobId || !/^[0-9a-f-]{36}$/i.test(jobId)) throw new Error('Geçerli bir belge seçilmedi. Yaver’e dönüp bir belge açın.');
      session = await request('/api/office/jobs/' + encodeURIComponent(jobId) + '/session', {method:'POST'});
      // Warm the browser cache for the plugin sandbox's scripts before ONLYOFFICE autostarts it,
      // so the yaver-bridge-ready handshake completes as fast as possible.
      ['/office-plugin/plugins.js', '/office-plugin/bridge.js'].forEach(href => {
        const link = document.createElement('link');
        link.rel = 'preload'; link.as = 'script'; link.href = href;
        document.head.appendChild(link);
      });
      await loadApi(session.apiScriptUrl);
      if (!window.DocsAPI) throw new Error('ONLYOFFICE başlatılamadı. Sayfayı yeniden açın.');
      const config = session.config;
      config.events = {...config.events,
        onDocumentReady: () => {ready = true; $('save').disabled = false; $('refresh').disabled = false; status('Belge hazır. Düzenlemelerinizi bitirdiğinizde Yaver’e kaydedin.');},
        onDocumentStateChange: event => {if (event.data) {changeEpoch++; hasLocalChanges = true; if (!saving) status('Belgede değişiklik var. Yaver’e kaydetmeyi unutmayın.');}},
        onError: event => status('Düzenleyici hatası: ' + (event.data?.errorDescription || event.data?.errorCode || 'Bağlantıyı kontrol edin.'), true)
      };
      editor = new DocsAPI.DocEditor('office-editor', config);

      panel = OfficeChatPanel.createDocumentChatPanel({
        mount: $('chat-panel'),
        captureSelection: bridge.captureSelection,
        applyText: bridge.applyText,
        applyFormatting: bridge.applyFormatting,
        requestProposal: (text, instruction) => request('/api/office/sessions/' + encodeURIComponent(session.sessionId) + '/agent', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + session.agentToken },
          body: JSON.stringify({ text, instruction })
        }, 120000).then(result => ({ text: result.text, formatting: result.formatting || null }))
      });
      $('toggle-assistant').addEventListener('click', () => panel.toggle());
    } catch (error) { status(error.message, true); }
  })();
})();
