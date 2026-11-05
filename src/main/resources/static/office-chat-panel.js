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

  // Inline lucide icons (no icon font: they must render offline and inside ONLYOFFICE's page).
  const ICON = {
    sparkles: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M9.937 15.5A2 2 0 0 0 8.5 14.063l-6.135-1.582a.5.5 0 0 1 0-.962L8.5 9.936A2 2 0 0 0 9.937 8.5l1.582-6.135a.5.5 0 0 1 .963 0L14.063 8.5A2 2 0 0 0 15.5 9.937l6.135 1.581a.5.5 0 0 1 0 .964L15.5 14.063a2 2 0 0 0-1.437 1.437l-1.582 6.135a.5.5 0 0 1-.963 0z"/></svg>',
    user: '<svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="8" r="4"/><path d="M6 21v-2a4 4 0 0 1 4-4h4a4 4 0 0 1 4 4v2"/></svg>',
    reset: '<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"/><path d="M3 3v5h5"/></svg>',
    chevron: '<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m9 18 6-6-6-6"/></svg>',
    send: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m5 12 7-7 7 7"/><path d="M12 19V5"/></svg>'
  };
  const STATUS = { pending: ['HAZIR', 'ready'], applied: ['UYGULANDI', 'done'], dismissed: ['VAZGEÇİLDİ', 'muted'] };
  const clock = date => date.toLocaleTimeString('tr-TR', { hour: '2-digit', minute: '2-digit' });
  const seconds = ms => (ms / 1000).toLocaleString('tr-TR', { maximumFractionDigits: 1 }) + ' sn';
  const clip = (text, n) => text.length > n ? text.slice(0, n) + '…' : text;

  function formattingChips(f) {
    return describeFormatting(f).replace(/^Biçimlendirme: /, '').split(', ').filter(x => x && x !== '—');
  }

  function createDocumentChatPanel(options) {
    const { mount, captureSelection, applyText, applyFormatting, requestProposal } = options;
    const title = options.title || 'Belge asistanı';
    const subtitle = options.subtitle || 'Yaver';
    let messages = [];
    let busy = false;
    let open = true;
    let seq = 0;

    mount.className = 'doc-chat-panel';
    mount.innerHTML = `
      <div class="doc-chat-header">
        <div class="doc-chat-crumb"><span>${esc(title)}</span><span class="doc-chat-crumb-sep">·</span><span>${esc(subtitle)}</span></div>
        <button type="button" class="doc-chat-reset" aria-label="Yeni sohbet">${ICON.reset}<span>Yeni sohbet</span></button>
        <button type="button" class="doc-chat-close" aria-label="Paneli kapat">✕</button>
      </div>
      <div class="doc-chat-history" role="log" aria-live="polite"></div>
      <div class="doc-chat-footer">
        <div class="doc-chat-composer">
          <textarea class="doc-chat-input" rows="1" placeholder="Belgede bir metin seçin, sonra ne değişsin yazın…" maxlength="10000" aria-label="Talimat"></textarea>
          <button type="button" class="doc-chat-send" aria-label="Gönder">${ICON.send}</button>
        </div>
        <div class="doc-chat-hint">Enter gönderir · Shift+Enter yeni satır</div>
      </div>`;

    const historyEl = mount.querySelector('.doc-chat-history');
    const inputEl = mount.querySelector('.doc-chat-input');
    const sendBtn = mount.querySelector('.doc-chat-send');
    const closeBtn = mount.querySelector('.doc-chat-close');
    const resetBtn = mount.querySelector('.doc-chat-reset');

    function userRow(m) {
      return `<div class="doc-chat-row doc-chat-row-user">
        <div class="doc-chat-col doc-chat-col-user">
          <div class="doc-chat-bubble">
            <div class="doc-chat-selected">${esc(clip(m.selected, 400))}</div>
            <div class="doc-chat-text">${esc(m.text)}</div>
          </div>
          <time class="doc-chat-time">${clock(m.at)}</time>
        </div>
        <div class="doc-chat-avatar doc-chat-avatar-user" aria-hidden="true">${ICON.user}</div>
      </div>`;
    }

    function agentShell(inner, at) {
      return `<div class="doc-chat-row doc-chat-row-agent">
        <div class="doc-chat-avatar doc-chat-avatar-agent" aria-hidden="true">${ICON.sparkles}</div>
        <div class="doc-chat-col">${inner}${at ? `<time class="doc-chat-time">${clock(at)}</time>` : ''}</div>
      </div>`;
    }

    function agentRow(m) {
      const [label, tone] = STATUS[m.status];
      const thought = `<button type="button" class="doc-chat-thought" data-toggle="thought" data-id="${m.id}" aria-expanded="${m.thoughtOpen}">
          <span class="doc-chat-chevron${m.thoughtOpen ? ' open' : ''}">${ICON.chevron}</span>${seconds(m.elapsed)}'de hazırladı</button>
        ${m.thoughtOpen ? `<div class="doc-chat-thought-body"><div><b>Seçili metin</b> · ${m.selected.length} karakter</div><div class="doc-chat-quote">${esc(clip(m.selected, 600))}</div><div><b>Talimat</b> · ${esc(m.instruction)}</div></div>` : ''}`;
      const textChanged = m.text.trim() !== m.selected.trim();
      const tool = `<div class="doc-chat-tool">
          <button type="button" class="doc-chat-tool-head" data-toggle="tool" data-id="${m.id}" aria-expanded="${m.toolOpen}">
            <span class="doc-chat-dot doc-chat-dot-${tone}"></span><span class="doc-chat-tool-name">düzenleme önerisi</span>
            <span class="doc-chat-tool-status">${label}</span><span class="doc-chat-chevron doc-chat-chevron-down${m.toolOpen ? ' open' : ''}">${ICON.chevron}</span>
          </button>
          ${m.toolOpen ? `<div class="doc-chat-diff">
            ${textChanged ? `<div class="doc-chat-diff-old"><span>−</span><span>${esc(clip(m.selected, 1200))}</span></div>
            <div class="doc-chat-diff-new"><span>+</span><span>${esc(clip(m.text, 1200))}</span></div>` : '<div class="doc-chat-diff-same">Metin aynı kalır, yalnızca biçim değişir.</div>'}
          </div>` : ''}
        </div>`;
      const chips = m.formatting ? `<div class="doc-chat-chips"><span class="doc-chat-chips-label">Biçim</span>${formattingChips(m.formatting)
          .map((c, i) => `<span class="doc-chat-chip"><span class="doc-chat-chip-num">${i + 1}</span>${esc(c)}</span>`).join('')}</div>` : '';
      const actions = m.status === 'pending'
        ? `<div class="doc-chat-actions">
             <button type="button" class="doc-chat-apply" data-id="${m.id}">Uygula</button>
             <button type="button" class="doc-chat-dismiss secondary" data-id="${m.id}">Vazgeç</button>
           </div>` : '';
      return agentShell(thought + tool + (textChanged ? `<div class="doc-chat-text doc-chat-answer">${esc(m.text)}</div>` : '') + chips + actions, m.at);
    }

    function render() {
      mount.classList.toggle('doc-chat-closed', !open);
      sendBtn.disabled = busy || !inputEl.value.trim();
      resetBtn.disabled = busy || messages.length === 0;
      if (messages.length === 0 && !busy) {
        historyEl.innerHTML = `<div class="doc-chat-empty">
          <div class="doc-chat-avatar doc-chat-avatar-agent doc-chat-empty-icon" aria-hidden="true">${ICON.sparkles}</div>
          <div class="doc-chat-empty-title">Yaver belge asistanı</div>
          <div>Belgede düzenlemek istediğiniz metni seçin, ardından aşağıya ne değişmesi gerektiğini yazıp gönderin.</div>
          <div class="doc-chat-examples"><span>› Bu paragrafı kısalt</span><span>› Daha resmi bir dille yaz</span><span>› Kalın ve koyu yeşil yap</span></div>
        </div>`;
        return;
      }
      historyEl.innerHTML = messages.map(m => m.role === 'user' ? userRow(m)
          : m.role === 'error' ? agentShell(`<div class="doc-chat-error"><span class="doc-chat-dot doc-chat-dot-error"></span>${esc(m.text)}</div>`, m.at)
          : agentRow(m)).join('')
        + (busy ? agentShell('<div class="doc-chat-thinking"><span class="doc-chat-chevron">' + ICON.chevron + '</span>Öneri hazırlanıyor…</div>') : '');
      historyEl.scrollTop = historyEl.scrollHeight;
    }

    function setBusy(value) { busy = value; render(); }
    const fail = text => messages.push({ role: 'error', id: ++seq, text, at: new Date() });

    async function send() {
      const instruction = inputEl.value.trim();
      if (!instruction || busy) return;
      if (instruction.length > 10000) { fail('Talimat en fazla 10000 karakter olabilir.'); render(); return; }
      setBusy(true);
      let selected;
      try {
        selected = await captureSelection();
        if (!selected || !selected.trim()) throw new Error('Önce belgede düzenlenecek bir metin seçin.');
        if (selected.length > 20000) throw new Error('Seçim en fazla 20000 karakter olabilir.');
      } catch (error) {
        fail(error.message || 'Seçim alınamadı.');
        setBusy(false);
        return;
      }
      inputEl.value = '';
      messages.push({ role: 'user', id: ++seq, text: instruction, selected, at: new Date() });
      setBusy(true);
      const started = Date.now();
      try {
        const proposal = await requestProposal(selected, instruction);
        const formatting = hasFormattingChange(proposal.formatting) ? proposal.formatting : null;
        const textChanged = proposal.text.trim() !== selected.trim();
        if (!textChanged && !formatting) {
          fail('Bu talimattan bir değişiklik çıkarılamadı. Talimatı daha açık yazıp tekrar deneyin.');
        } else {
          messages.push({ role: 'agent', id: ++seq, text: proposal.text, selected, instruction, formatting, status: 'pending',
            elapsed: Date.now() - started, at: new Date(), thoughtOpen: false, toolOpen: false });
        }
      } catch (error) {
        fail(error.message || 'Öneri alınamadı.');
      } finally {
        setBusy(false);
      }
    }

    historyEl.addEventListener('click', async event => {
      const toggle = event.target.closest('[data-toggle]');
      if (toggle) {
        const msg = messages.find(m => m.id === Number(toggle.dataset.id));
        if (msg) { msg[toggle.dataset.toggle + 'Open'] = !msg[toggle.dataset.toggle + 'Open']; render(); }
        return;
      }
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
          fail(error.message || 'Uygulanamadı.');
        } finally {
          setBusy(false);
        }
      }
    });

    sendBtn.addEventListener('click', send);
    inputEl.addEventListener('input', () => {
      // Grow with the text up to the CSS max-height, like a chat composer.
      if (inputEl.style) { inputEl.style.height = 'auto'; inputEl.style.height = inputEl.scrollHeight + 'px'; }
      render();
    });
    inputEl.addEventListener('keydown', event => {
      if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); send(); }
    });
    closeBtn.addEventListener('click', () => { open = false; render(); });
    resetBtn.addEventListener('click', () => { if (!busy) { messages = []; render(); } });

    render();
    return {
      open() { open = true; render(); },
      close() { open = false; render(); },
      toggle() { open = !open; render(); }
    };
  }

  root.OfficeChatPanel = { createDocumentChatPanel };
})(globalThis);
