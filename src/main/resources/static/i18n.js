/* Pluggis – svenska eller engelska i elevchatten och föräldravyn.
 *
 * Gränssnittet är skrivet på svenska. När språket är engelska översätts texten i sidan när den visas:
 * en MutationObserver går igenom nya och ändrade textnoder och attribut (placeholder, title, aria-label)
 * och byter ut svenska texter som finns i ordlistan nedan. Mallar med {0}, {1} … matchar texter med
 * varierande delar ("{0} min kvar" → "5 min kvar"). Text i element med data-noi18n (t.ex. det eleven
 * skrivit) rörs inte. confirm/alert/prompt översätts också.
 *
 * Elevchatten tar språket från /api/chat/me (elevens inställning), föräldravyn från sitt eget val.
 * Valet sparas i webbläsaren så att inloggningen visas på rätt språk nästa gång.
 */
(function () {
  const KEY = 'pluggis.lang';
  let lang = 'sv';
  try { lang = localStorage.getItem(KEY) === 'en' ? 'en' : 'sv'; } catch (e) { /* privat läge */ }

  const EN = {};          // exakta texter: svenska → engelska
  const TEMPLATES = [];   // [regex, engelsk mall]

  function esc(s) { return s.replace(/[.*+?^$()|[\]\\]/g, '\\$&'); }

  /** Lägger till översättningar: { "svensk text": "English text", "{0} min kvar": "{0} min left" }. */
  function add(dict) {
    for (const [sv, en] of Object.entries(dict)) {
      const k = sv.trim();
      if (/\{\d\}/.test(k)) {
        const re = new RegExp('^' + esc(k).replace(/\\?\{(\d)\\?\}/g, '(.+?)') + '$', 's');
        const order = [...k.matchAll(/\{(\d)\}/g)].map(m => Number(m[1]));
        TEMPLATES.push([re, en.trim(), order, k.replace(/\{\d\}/g, '').length]);
      } else {
        EN[k] = en.trim();
      }
    }
    // mest specifika mallen först: "{0} min" får inte ta "Lyssna på … ca {2} min"
    TEMPLATES.sort((a, b) => b[3] - a[3]);
    if (observer) translateTree(document.body);   // ordlistan kom efter att sidan börjat visas
  }

  /** Översätter en text (svenska → engelska) om språket är engelska. Okänd text lämnas som den är. */
  function t(s) {
    if (lang !== 'en' || typeof s !== 'string') return s;
    const m = s.match(/^(\s*)([\s\S]*?)(\s*)$/);
    const core = m[2];
    if (!core || !/[A-Za-zÅÄÖåäö]/.test(core)) return s;
    if (Object.prototype.hasOwnProperty.call(EN, core)) return m[1] + EN[core] + m[3];
    for (const [re, en, order, lit] of TEMPLATES) {
      const r = core.match(re);
      // korta mallar ("{0} av {1}", "{0} min") gäller bara korta värden, inte hela meningar i fri text
      if (r && lit < 6 && r.slice(1).some(v => v.length > 24)) continue;
      if (r) {
        const vals = {};
        order.forEach((n, i) => { vals[n] = t(r[i + 1]); });
        return m[1] + en.replace(/\{(\d)\}/g, (_, n) => vals[n] ?? '') + m[3];
      }
    }
    return s;
  }

  const ATTRS = ['placeholder', 'title', 'aria-label', 'alt'];
  const SKIP = new Set(['SCRIPT', 'STYLE', 'TEXTAREA', 'CODE', 'PRE']);
  function skipped(el) {
    for (let e = el; e; e = e.parentElement) {
      if (SKIP.has(e.tagName) || (e.dataset && 'noi18n' in e.dataset)) return true;
    }
    return false;
  }
  function translateText(node) {
    if (!node.parentElement || skipped(node.parentElement)) return;
    const v = node.nodeValue, n = t(v);
    if (n !== v) node.nodeValue = n;
  }
  function translateAttrs(el) {
    if ((el.dataset && 'noi18n' in el.dataset) || (el.parentElement && skipped(el.parentElement))) return;   // textrutans placeholder översätts
    for (const a of ATTRS) {
      const v = el.getAttribute && el.getAttribute(a);
      if (v) { const n = t(v); if (n !== v) el.setAttribute(a, n); }
    }
  }
  function translateTree(root) {
    if (lang !== 'en' || !root) return;
    if (root.nodeType === 3) { translateText(root); return; }
    if (root.nodeType !== 1) return;
    translateAttrs(root);
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT);
    let n;
    while ((n = walker.nextNode())) {
      if (n.nodeType === 3) translateText(n); else translateAttrs(n);
    }
  }

  let observer = null;
  function watch() {
    if (observer || lang !== 'en' || !document.body) return;
    translateTree(document.body);
    document.title = t(document.title);
    observer = new MutationObserver(list => {
      for (const m of list) {
        if (m.type === 'characterData') translateText(m.target);
        else if (m.type === 'attributes') translateAttrs(m.target);
        else m.addedNodes.forEach(translateTree);
      }
    });
    observer.observe(document.body, { subtree: true, childList: true, characterData: true, attributes: true, attributeFilter: ATTRS });
  }

  const origConfirm = window.confirm.bind(window), origAlert = window.alert.bind(window), origPrompt = window.prompt.bind(window);
  window.confirm = (m) => origConfirm(t(m));
  window.alert = (m) => origAlert(t(m));
  window.prompt = (m, d) => origPrompt(t(m), d);

  /** Byter språk. Från engelska till svenska laddas sidan om (texten är redan utbytt). */
  function setLang(l) {
    const next = l === 'en' ? 'en' : 'sv';
    try { localStorage.setItem(KEY, next); } catch (e) { /* privat läge */ }
    if (next === lang) { watch(); return false; }
    if (lang === 'en' && next === 'sv') { location.reload(); return true; }
    lang = next;
    document.documentElement.lang = lang;
    watch();
    return false;
  }

  window.I18N = {
    add, t, setLang,
    get lang() { return lang; },
    /** För datum och tal: "sv-SE" eller "en-GB". */
    locale() { return lang === 'en' ? 'en-GB' : 'sv-SE'; },
    /** Telefonens röst och taligenkänning. */
    speechLang() { return lang === 'en' ? 'en-GB' : 'sv-SE'; },
  };

  if (lang === 'en') {
    document.documentElement.lang = 'en';
    if (document.body) watch(); else document.addEventListener('DOMContentLoaded', watch);
  }
})();
