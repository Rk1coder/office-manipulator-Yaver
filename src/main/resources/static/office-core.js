(function (root) {
  'use strict';
  const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
  async function requestJson(url, options, timeoutMs = 30000) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
      const response = await fetch(url, { ...options, signal: controller.signal, cache: 'no-store' });
      const body = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(body.error || body.message || 'İstek başarısız (' + response.status + ').');
      return body;
    } catch (error) {
      if (error.name === 'AbortError') throw new Error('İstek zaman aşımına uğradı. Bağlantıyı kontrol edip yeniden deneyin.');
      throw error;
    } finally { clearTimeout(timer); }
  }
  async function saveAndWait(request, sessionId, options = {}) {
    const url = '/api/office/sessions/' + encodeURIComponent(sessionId);
    const before = await request(url);
    const revision = Number(before.revision) || 0;
    const response = await request(url + '/save', {method: 'POST'});
    if (response.unchanged === true && response.requested === false) return {...before, unchanged:true};
    for (let attempt = 0; attempt < (options.attempts || 60); attempt++) {
      await (options.delay || delay)(1500);
      const status = await request(url);
      if (status.error) throw new Error(status.error);
      const acknowledged = response.requestId
        ? status.acknowledgedRequest === response.requestId
        : Number(status.revision) > revision;
      if (acknowledged && status.savedJobId) return status;
    }
    throw new Error('Kaydetme onayı zaman aşımına uğradı. Belgeyi kapatmayın; durumu kontrol edip yeniden deneyin.');
  }
  root.OfficeCore = {requestJson, saveAndWait};
})(globalThis);
