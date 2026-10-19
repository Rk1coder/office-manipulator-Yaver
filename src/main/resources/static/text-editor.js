(function () {
  'use strict';
  const $ = id => document.getElementById(id);
  const request = OfficeCore.requestJson;
  const jobId = new URLSearchParams(location.search).get('jobId');
  const textarea = $('text-editor-area');
  let currentJobId = jobId, dirty = false, saving = false;

  function notifyParent(data) { if (window.parent !== window) window.parent.postMessage({ ...data, jobId }, location.origin); }
  function status(text, error = false) { $('status').textContent = text; $('status').classList.toggle('error', error); }

  async function load() {
    const doc = await request('/api/office/text/' + encodeURIComponent(currentJobId));
    textarea.value = doc.content;
    textarea.disabled = false;
    $('save').disabled = false;
    status('Belge hazır. Düzenlemelerinizi bitirdiğinizde Yaver’e kaydedin.');
  }

  $('save').addEventListener('click', async () => {
    if (saving) return;
    saving = true; $('save').disabled = true;
    status('Kaydediliyor…');
    try {
      const doc = await request('/api/office/text/' + encodeURIComponent(currentJobId), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ content: textarea.value })
      });
      currentJobId = doc.jobId;
      dirty = false;
      $('download').href = '/api/files/' + encodeURIComponent(doc.jobId) + '/download';
      $('download').hidden = false;
      $('revision').textContent = 'Yaver’de kayıtlı en güncel sürüm.';
      notifyParent({ type: 'yaver-office-saved', savedJobId: doc.jobId, revision: 1 });
      status('Kaydedildi. Yeni sürümü indirebilirsiniz.');
    } catch (error) { status(error.message, true); }
    finally { saving = false; $('save').disabled = false; }
  });

  $('close').addEventListener('click', () => {
    if (dirty && !window.confirm('Yaver’e kaydedilmemiş değişiklikler olabilir. Yine de önizlemeye dönmek istiyor musunuz?')) return;
    if (window.parent !== window) notifyParent({ type: 'yaver-office-close' });
    else location.href = '/';
  });

  textarea.addEventListener('input', () => { dirty = true; });
  window.addEventListener('beforeunload', event => { if (dirty) { event.preventDefault(); event.returnValue = ''; } });

  (async function init() {
    try {
      if (!jobId || !/^[0-9a-f-]{36}$/i.test(jobId)) throw new Error('Geçerli bir belge seçilmedi. Yaver’e dönüp bir belge açın.');
      await load();
      OfficeChatPanel.createDocumentChatPanel({
        mount: $('chat-panel'),
        captureSelection: () => Promise.resolve(textarea.value.slice(textarea.selectionStart, textarea.selectionEnd)),
        applyText: text => {
          const start = textarea.selectionStart, end = textarea.selectionEnd;
          textarea.setRangeText(text, start, end, 'end');
          dirty = true;
          return Promise.resolve();
        },
        requestProposal: (text, instruction) => request('/api/jobs/' + encodeURIComponent(currentJobId) + '/edit-fragment', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ fragment: { text }, instruction })
        }, 120000).then(result => result.fragment.text)
      });
    } catch (error) { status(error.message, true); }
  })();
})();
