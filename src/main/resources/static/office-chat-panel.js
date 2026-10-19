(function (root) {
  'use strict';
  const esc = s => String(s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));

  /**
   * A self-contained, modular right-side AI chat panel for editing a selected text fragment.
   * Works against ANY editor surface (ONLYOFFICE via a bridge, a plain <textarea>, …) through
   * three injected functions — this file has no ONLYOFFICE- or textarea-specific code at all.
   *
   * options:
   *   mount: HTMLElement to render the panel into
   *   captureSelection: () => Promise<string>   — read the currently selected text
   *   applyText: (text) => Promise<void>        — replace the selection with the given text
   *   applyFormatting: (formatting) => Promise<void> — optional; apply bold/color/font/etc. to
   *                                              the current selection (visual formatting)
   *   requestProposal: (text, instruction) => Promise<{text, formatting}> — ask the backend for
   *                                              a rewrite; formatting is null or a partial
   *                                              {bold, italic, underline, color, highlightColor,
   *                                              fontFamily, fontSize} object (null fields = no change)
   *   title, subtitle: optional header strings
   */
  function hasFormattingChange(formatting) {
    return !!formatting && Object.values(formatting).some(v => v !== null && v !== undefined);
  }

  const HIGHLIGHT_LABELS = { black:'siyah', blue:'mavi', cyan:'camgöbeği', darkBlue:'koyu mavi',
    darkCyan:'koyu camgöbeği', darkGray:'koyu gri', darkGreen:'koyu yeşil', darkMagenta:'koyu macenta',
    darkRed:'koyu kırmızı', darkYellow:'koyu sarı', green:'yeşil', lightGray:'açık gri', magenta:'macenta',
    none:'vurgu yok', red:'kırmızı', white:'beyaz', yellow:'sarı' };

  function describeFormatting(f) {
    const parts = [];
    if (f.bold === true) parts.push('kalın'); else if (f.bold === false) parts.push('kalın değil');
    if (f.italic === true) parts.push('eğik'); else if (f.italic === false) parts.push('eğik değil');
    if (f.underline === true) parts.push('altı çizili'); else if (f.underline === false) parts.push('altı çizili değil');
    if (f.color) parts.push('renk #' + f.color);
    if (f.highlightColor) parts.push('vurgu: ' + (HIGHLIGHT_LABELS[f.highlightColor] || f.highlightColor));
    if (f.fontFamily) parts.push('yazı tipi: ' + f.fontFamily);
    if (f.fontSize) parts.push(f.fontSize + 'pt');
    return 'Biçimlendirme: ' + (parts.join(', ') || '—');
  }

  function createDocumentChatPanel(options) {
    const { mount, captureSelection, applyText, applyFormatting, requestProposal } = options;
    const title = options.title || 'Yaver';
    const subtitle = options.subtitle || 'Metin seçin, talimat yazın, öneriyi uygulayın.';
    let messages = [];
    let busy = false;
    let open = true;
    let seq = 0;

    mount.className = 'doc-chat-panel';
    mount.innerHTML = `
      <div class="doc-chat-header">
        <div class="doc-chat-badge"><img src="/logo-hero.gif" alt="Yaver" class="doc-chat-badge-img"></div>
        <div class="doc-chat-heading">
          <div class="doc-chat-title">${esc(title)}</div>
          <div class="doc-chat-sub">${esc(subtitle)}</div>
        </div>
        <button type="button" class="doc-chat-close" aria-label="Paneli kapat">✕</button>
      </div>
      <div class="doc-chat-history" role="log" aria-live="polite"></div>
      <div class="doc-chat-busy" hidden>Öneri hazırlanıyor…</div>
      <div class="doc-chat-footer">
        <textarea class="doc-chat-input" placeholder="Belgede bir metin seçin, sonra ne değişsin yazın…" maxlength="10000"></textarea>
        <button type="button" class="doc-chat-send">Gönder</button>
      </div>`;

    const historyEl = mount.querySelector('.doc-chat-history');
    const busyEl = mount.querySelector('.doc-chat-busy');
    const inputEl = mount.querySelector('.doc-chat-input');
    const sendBtn = mount.querySelector('.doc-chat-send');
    const closeBtn = mount.querySelector('.doc-chat-close');

    function render() {
      mount.classList.toggle('doc-chat-closed', !open);
      busyEl.hidden = !busy;
      sendBtn.disabled = busy || !inputEl.value.trim();
      if (messages.length === 0) {
        historyEl.innerHTML = `<div class="doc-chat-empty">
          <img class="doc-chat-empty-logo" src="/logo-hero.gif" alt="Yaver" />
          <div class="doc-chat-empty-title">Yaver</div>
          <div>Belgede düzenlemek istediğiniz metni seçin, ardından aşağıya ne değişmesi gerektiğini yazıp gönderin.</div>
        </div>`;
        return;
      }
      historyEl.innerHTML = messages.map(m => {
        if (m.role === 'user') {
          return `<div class="doc-chat-msg doc-chat-user">
            <div class="doc-chat-selected">${esc(m.selected.slice(0, 400))}${m.selected.length > 400 ? '…' : ''}</div>
            <div class="doc-chat-text">${esc(m.text)}</div>
          </div>`;
        }
        if (m.role === 'error') {
          return `<div class="doc-chat-msg doc-chat-agent doc-chat-error">${esc(m.text)}</div>`;
        }
        const actions = m.status === 'pending'
          ? `<div class="doc-chat-actions">
               <button type="button" class="doc-chat-apply" data-id="${m.id}">Uygula</button>
               <button type="button" class="doc-chat-dismiss" data-id="${m.id}">Vazgeç</button>
             </div>`
          : m.status === 'applied' ? '<div class="doc-chat-status">Uygulandı</div>'
          : '<div class="doc-chat-status doc-chat-status-dismissed">Vazgeçildi</div>';
        const formattingLine = m.formatting ? `<div class="doc-chat-formatting">${esc(describeFormatting(m.formatting))}</div>` : '';
        return `<div class="doc-chat-msg doc-chat-agent">
          <div class="doc-chat-text">${esc(m.text)}</div>
          ${formattingLine}
          ${actions}
        </div>`;
      }).join('');
      historyEl.scrollTop = historyEl.scrollHeight;
    }

    function setBusy(value) { busy = value; render(); }

    async function send() {
      const instruction = inputEl.value.trim();
      if (!instruction || busy) return;
      if (instruction.length > 10000) {
        messages.push({role:'error',id:++seq,text:'Talimat en fazla 10000 karakter olabilir.'}); render(); return;
      }
      setBusy(true);
      let selected;
      try {
        selected = await captureSelection();
        if (!selected || !selected.trim()) throw new Error('Önce belgede düzenlenecek bir metin seçin.');
        if (selected.length > 20000) throw new Error('Seçim en fazla 20000 karakter olabilir.');
      } catch (error) {
        messages.push({ role: 'error', id: ++seq, text: error.message || 'Seçim alınamadı.' });
        setBusy(false);
        return;
      }
      inputEl.value = '';
      messages.push({ role: 'user', id: ++seq, text: instruction, selected });
      setBusy(true);
      try {
        const proposal = await requestProposal(selected, instruction);
        const formatting = hasFormattingChange(proposal.formatting) ? proposal.formatting : null;
        const textChanged = proposal.text.trim() !== selected.trim();
        if (!textChanged && !formatting) {
          messages.push({ role: 'error', id: ++seq, text: 'Bu talimattan bir değişiklik çıkarılamadı. Talimatı daha açık yazıp tekrar deneyin.' });
        } else {
          messages.push({ role: 'agent', id: ++seq, text: proposal.text, selected, formatting, status: 'pending' });
        }
      } catch (error) {
        messages.push({ role: 'error', id: ++seq, text: error.message || 'Öneri alınamadı.' });
      } finally {
        setBusy(false);
      }
    }

    historyEl.addEventListener('click', async event => {
      const applyBtn = event.target.closest('.doc-chat-apply');
      const dismissBtn = event.target.closest('.doc-chat-dismiss');
      if (dismissBtn) {
        const msg = messages.find(m => m.id === Number(dismissBtn.dataset.id));
        if (msg) { msg.status = 'dismissed'; render(); }
        return;
      }
      if (applyBtn) {
        const msg = messages.find(m => m.id === Number(applyBtn.dataset.id));
        if (!msg || msg.status !== 'pending' || busy) return;
        setBusy(true);
        try {
          msg.status = 'dismissed'; // Consume before an uncertain editor operation; never retry the same proposal.
          const current = await captureSelection();
          if (current !== msg.selected) throw new Error('Seçim değişti. İlgili metni yeniden seçip yeni bir öneri isteyin.');
          if (msg.text.trim() !== msg.selected.trim()) await applyText(msg.text);
          if (msg.formatting && applyFormatting) await applyFormatting(msg.formatting);
          msg.status = 'applied';
        } catch (error) {
          messages.push({ role: 'error', id: ++seq, text: error.message || 'Uygulanamadı.' });
        } finally {
          setBusy(false);
        }
      }
    });

    sendBtn.addEventListener('click', send);
    inputEl.addEventListener('input', render);
    inputEl.addEventListener('keydown', event => {
      if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); send(); }
    });
    closeBtn.addEventListener('click', () => { open = false; render(); });

    render();
    return {
      open() { open = true; render(); },
      close() { open = false; render(); },
      toggle() { open = !open; render(); }
    };
  }

  root.OfficeChatPanel = { createDocumentChatPanel };
})(globalThis);
