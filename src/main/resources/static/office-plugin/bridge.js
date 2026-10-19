(function () {
  'use strict';
  const plugin = window.Asc && window.Asc.plugin;

  function execute(name, args) {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('Düzenleyici yanıt vermedi.')), 10000);
      try { plugin.executeMethod(name, args, value => { clearTimeout(timer); resolve(value); }); }
      catch (error) { clearTimeout(timer); reject(error); }
    });
  }

  // Applies visual formatting (bold/italic/underline/color/highlight/font) to the CURRENT
  // selection by running code inside the editor's own JS context via ONLYOFFICE's plugin
  // callCommand API — unlike executeMethod('PasteText', ...), this has access to the internal
  // document object model (Api.GetDocument().GetRangeBySelect()), which is the only way to set
  // real run-level formatting rather than just replacing plain text.
  //
  // callCommand serializes the given function's source (fn.toString()) and evaluates it on the
  // Document Server side, so it cannot close over variables from this scope — the `formatting`
  // value is instead baked in as a JSON literal before the source is turned into a function.
  const FORMATTING_COMMAND_BODY = `
    try {
      var oDocument = Api.GetDocument();
      var oRange = oDocument && oDocument.GetRangeBySelect ? oDocument.GetRangeBySelect() : null;
      if (!oRange) { return; }
      function hex2rgb(hex) {
        var h = String(hex).replace(/^#/, '');
        return [parseInt(h.substring(0, 2), 16), parseInt(h.substring(2, 4), 16), parseInt(h.substring(4, 6), 16)];
      }
      if (typeof formatting.bold === 'boolean') oRange.SetBold(formatting.bold);
      if (typeof formatting.italic === 'boolean') oRange.SetItalic(formatting.italic);
      if (typeof formatting.underline === 'boolean') oRange.SetUnderline(formatting.underline);
      if (formatting.color) { var c = hex2rgb(formatting.color); oRange.SetColor(c[0], c[1], c[2]); }
      if (formatting.highlightColor) oRange.SetHighlight(formatting.highlightColor);
      if (formatting.fontFamily) oRange.SetFontFamily(formatting.fontFamily);
      if (typeof formatting.fontSize === 'number') oRange.SetFontSize(formatting.fontSize);
    } catch (e) {}
  `;

  function applyFormatting(formatting) {
    return new Promise((resolve, reject) => {
      if (!plugin.callCommand) { reject(new Error('Bu ONLYOFFICE sürümü biçimlendirme komutlarını desteklemiyor.')); return; }
      try {
        const source = 'var formatting = ' + JSON.stringify(formatting || {}) + ';' + FORMATTING_COMMAND_BODY;
        // eslint-disable-next-line no-new-func -- required by ONLYOFFICE's callCommand(fn) API, see above
        const fn = new Function(source);
        plugin.callCommand(fn, false);
        resolve(); // callCommand has no completion callback — this is a best-effort fire.
      } catch (error) { reject(error); }
    });
  }

  // office-editor.html is itself embedded in an <iframe> inside the main app, and ONLYOFFICE
  // may also nest this plugin inside its own editor iframe — so window.top can overshoot past
  // office-editor.html straight to the main app. Broadcast to every ancestor frame instead;
  // only the one actually listening for these message types will act on it.
  function reply(message) {
    let w = window;
    while (w !== window.top) {
      w = w.parent;
      try { w.postMessage(message, location.origin); } catch (e) {}
    }
  }

  window.addEventListener('message', async event => {
    if (event.origin !== location.origin || !event.data || typeof event.data !== 'object') return;
    const { type, requestId } = event.data;
    try {
      if (type === 'yaver-bridge-get-selection') {
        const text = await execute('GetSelectedText', [{ Numbering: false, Math: false, TableCellSeparator: '\t', TableRowSeparator: '\n', ParaSeparator: '\n', TabSymbol: '\t', NewLineSeparator: '\n' }]);
        reply({ type: 'yaver-bridge-selection-result', requestId, text });
      } else if (type === 'yaver-bridge-paste-text') {
        await execute('PasteText', [event.data.text]);
        reply({ type: 'yaver-bridge-paste-done', requestId });
      } else if (type === 'yaver-bridge-apply-formatting') {
        await applyFormatting(event.data.formatting);
        reply({ type: 'yaver-bridge-formatting-done', requestId });
      }
    } catch (error) {
      reply({ type: 'yaver-bridge-error', requestId, message: error.message });
    }
  });

  if (!plugin) return;
  plugin.init = function () {
    reply({ type: 'yaver-bridge-ready' });
  };
  // This plugin has no visible UI of its own (isVisual:false) — ONLYOFFICE still shows its
  // ribbon entry, so clicking it toggles the app's own Yaver panel instead of doing nothing.
  plugin.button = function () { reply({ type: 'yaver-bridge-toggle-panel' }); plugin.executeCommand('close', ''); };
})();
