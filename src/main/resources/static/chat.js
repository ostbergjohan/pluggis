'use strict';
// Pluggis – elevchatten (mobil först). Pratar med coachen via samma WebSocket som Tab5,
// visar dagens plan och läxorna, och laddar upp material (foto, skärmdump, Word, PDF).

const $ = (s) => document.querySelector(s);
const enc = encodeURIComponent;
const FILE_ACCEPT = 'image/*,.pdf,.docx,.txt,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document';
const MAX_FILES = 10;

/** Förminskar foton (max 2000 px, JPEG) innan uppladdning – snabbare och inom Claudes gränser. */
async function shrinkImage(file) {
  if (!file.type || !file.type.startsWith('image/') || file.type === 'image/gif') return file;
  try {
    const url = URL.createObjectURL(file);
    const img = await new Promise((ok, fail) => { const i = new Image(); i.onload = () => ok(i); i.onerror = fail; i.src = url; });
    URL.revokeObjectURL(url);
    const scale = Math.min(1, 2000 / Math.max(img.naturalWidth, img.naturalHeight));
    if (scale === 1 && file.size < 1_500_000) return file;
    const c = document.createElement('canvas');
    c.width = Math.round(img.naturalWidth * scale); c.height = Math.round(img.naturalHeight * scale);
    c.getContext('2d').drawImage(img, 0, 0, c.width, c.height);
    const blob = await new Promise(ok => c.toBlob(ok, 'image/jpeg', 0.85));
    return blob ? new File([blob], (file.name || 'bild').replace(/\.[^.]+$/, '') + '.jpg', { type: 'image/jpeg' }) : file;
  } catch { return file; }
}

/** Bild som kräver kod (img kan inte skicka headers): hämta som blob. */
async function authImage(path) {
  const res = await fetch(path, { headers: { 'X-Pluggis-Token': S.token } });
  if (!res.ok) throw new Error(String(res.status));
  return URL.createObjectURL(await res.blob());
}
const store = {
  get: (k, d) => { try { const v = localStorage.getItem(k); return v === null ? d : v; } catch { return d; } },
  set: (k, v) => { try { localStorage.setItem(k, v); } catch { /* privat läge */ } },
};

function h(tag, attrs, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v === null || v === undefined || v === false) continue;
    if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
    else if (k === 'class') el.className = v;
    else if (k === 'html') el.innerHTML = v;
    else el.setAttribute(k, v === true ? '' : v);
  }
  for (const c of kids.flat()) if (c !== null && c !== undefined && c !== false) el.append(c.nodeType ? c : String(c));
  return el;
}

// ---- Token: från länken (#t=…) första gången, sedan sparad i telefonen ----
(function takeTokenFromLink() {
  // #k=K7M2Q (elevkod) eller den äldre långa länken #t=…
  const m = location.hash.match(/[#&][kt]=([^&]+)/);
  if (m) {
    const prev = store.get('pluggis.chat.token', '');
    const next = decodeURIComponent(m[1]).trim().toUpperCase().length === 5 ? decodeURIComponent(m[1]).trim().toUpperCase() : decodeURIComponent(m[1]);
    if (prev !== next) store.set('pluggis.chat.history', '[]'); // ny elev på telefonen – börja om chatten
    store.set('pluggis.chat.token', next);
    history.replaceState(null, '', location.pathname);
  }
})();

const S = {
  token: store.get('pluggis.chat.token', ''),
  deviceId: store.get('pluggis.chat.device', 'tab5-1'),
  name: '',
  ws: null, retry: 0, tab: 'chatt',
  voice: store.get('pluggis.chat.voice', store.get('pluggis.chat.speak', '0') === '1' ? 'telefon' : 'av'),
  serverVoice: false,
  phoneUnlocked: false,
  history: JSON.parse(store.get('pluggis.chat.history', '[]') || '[]'),
  session: null, thinking: false,
};

async function api(path, opts = {}) {
  const headers = { 'X-Pluggis-Token': S.token };
  let body;
  if (opts.form) body = opts.form;
  else if (opts.body !== undefined) { headers['Content-Type'] = 'application/json'; body = JSON.stringify(opts.body); }
  const res = await fetch(path, { method: opts.method || 'GET', headers, body });
  if (res.status === 429) throw new Error('För många felaktiga försök – vänta en stund');
  if (res.status === 401) throw Object.assign(new Error('Koden fungerar inte – be om en ny'), { auth: true });
  if (opts.raw) return res;
  if (res.status === 403) throw new Error('Det här går inte att göra från elevchatten');
  if (!res.ok) {
    let msg = `Något gick fel (${res.status})`;
    try { const j = await res.json(); msg = j.error || j.message || msg; } catch { /* inte json */ }
    throw new Error(msg);
  }
  if (res.status === 204) return null;
  const type = res.headers.get('content-type') || '';
  return type.includes('json') ? res.json() : res.text();
}

let toastTimer;
function toast(text, bad) {
  const t = $('#toast');
  t.textContent = text; t.className = 'toast' + (bad ? ' bad' : ''); t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t.hidden = true; }, 3200);
}

// ---- Datum ----
function parseDay(iso) { if (!iso) return null; const [y, m, d] = iso.split('-').map(Number); return new Date(y, m - 1, d); }
function todayIso() { const d = new Date(); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`; }
function daysUntil(iso) { const d = parseDay(iso); return d ? Math.round((d - parseDay(todayIso())) / 86400000) : null; }
function rel(iso) {
  const n = daysUntil(iso);
  if (n === null) return 'inget datum';
  if (n === 0) return 'idag'; if (n === 1) return 'imorgon'; if (n === -1) return 'igår';
  if (n < 0) return `${-n} dagar sedan`;
  return n < 7 ? new Intl.DateTimeFormat('sv-SE', { weekday: 'long' }).format(parseDay(iso)) : `om ${n} dagar`;
}
const dayName = (iso) => {
  const n = daysUntil(iso);
  if (n === 0) return 'Idag'; if (n === 1) return 'Imorgon'; if (n === -1) return 'Igår';
  return new Intl.DateTimeFormat('sv-SE', { weekday: 'long', day: 'numeric', month: 'short' }).format(parseDay(iso));
};

// ---- Uppläsning ----
// Tre lägen: av, "pluggis" (Pluggis egen röst från servern, samma som Tab5) eller "telefon" (iPhones röst).
// iPhone spelar bara upp ljud som "låsts upp" av ett tryck – det görs när man väljer läge och när man skickar.
const VOICE_MODES = ['av', 'pluggis', 'telefon'];

// Ett enda <audio>-element som "låses upp" av första trycket och sedan återanvänds.
// Till skillnad från Web Audio hörs det även när iPhone står på ljudlöst, och det fastnar inte
// i iOS "avbrutet"-läge efter samtal, notiser eller skärmlås.
const player = new Audio();
player.preload = 'auto';
player.setAttribute('playsinline', '');
let playerUnlocked = false;
const SILENCE = (() => {
  // 0,1 s tyst wav (8 kHz, 8 bit, mono)
  const n = 800, buf = new Uint8Array(44 + n), v = new DataView(buf.buffer);
  const w = (o, str) => { for (let i = 0; i < str.length; i++) buf[o + i] = str.charCodeAt(i); };
  w(0, 'RIFF'); v.setUint32(4, 36 + n, true); w(8, 'WAVE'); w(12, 'fmt '); v.setUint32(16, 16, true);
  v.setUint16(20, 1, true); v.setUint16(22, 1, true); v.setUint32(24, 8000, true); v.setUint32(28, 8000, true);
  v.setUint16(32, 1, true); v.setUint16(34, 8, true); w(36, 'data'); v.setUint32(40, n, true); buf.fill(128, 44);
  let bin = ''; buf.forEach(b => { bin += String.fromCharCode(b); });
  return 'data:audio/wav;base64,' + btoa(bin);
})();

function unlockAudio() {
  try { if (navigator.audioSession) navigator.audioSession.type = 'playback'; } catch { /* äldre iOS */ }
  if (!playerUnlocked && S.voice === 'pluggis') {
    try {
      player.src = SILENCE;
      const p = player.play();
      if (p && p.then) p.then(() => { playerUnlocked = true; }).catch(() => {});
      else playerUnlocked = true;
    } catch { /* försök igen vid nästa tryck */ }
  }
  if (S.voice === 'telefon' && window.speechSynthesis && !S.phoneUnlocked) {
    // iOS släpper bara fram uppläsning som först startats av ett tryck
    const u = new SpeechSynthesisUtterance('.'); u.volume = 0.01; u.lang = 'sv-SE';
    speechSynthesis.speak(u);
    S.phoneUnlocked = true;
  }
}

/** Testljud när man väljer Pluggis röst – hörs det inte är det ljudet i telefonen som strular. */
async function testServerVoice() {
  try {
    const res = await fetch('/api/speech/preview', { method: 'POST',
      headers: { 'X-Pluggis-Token': S.token, 'Content-Type': 'application/json' },
      body: JSON.stringify({ text: S.name ? `Hej ${S.name}! Nu läser jag upp svaren.` : 'Hej! Nu läser jag upp svaren.' }) });
    if (!res.ok) throw new Error(String(res.status));
    await playBuffer(await res.arrayBuffer());
  } catch {
    toast('Kunde inte hämta Pluggis röst – är ljudtjänsten igång?', true);
  }
}

function svVoice() {
  if (!window.speechSynthesis) return null;
  const voices = speechSynthesis.getVoices().filter(v => v.lang && v.lang.toLowerCase().startsWith('sv'));
  return voices.find(v => /alva|klara|premium|enhanced|förbättrad/i.test(v.name)) || voices[0] || null;
}
if (window.speechSynthesis) speechSynthesis.addEventListener?.('voiceschanged', () => {});

/** Samma som serverns SpokenText: datum, veckor, sidor och klockslag som ord (bråk lämnas orörda). */
const MONTHS = ['januari', 'februari', 'mars', 'april', 'maj', 'juni', 'juli', 'augusti', 'september', 'oktober', 'november', 'december'];
function forSpeech(t) {
  if (!t) return t;
  const year = new Date().getFullYear();
  return t
    .replace(/\b(\d{4})-(\d{1,2})-(\d{1,2})\b/g, (m, y, mo, d) => (mo >= 1 && mo <= 12 && d >= 1 && d <= 31)
      ? `${+d} ${MONTHS[mo - 1]}${+y === year ? '' : ' ' + y}` : m)
    .replace(/(^|[^\p{L}])(måndag|tisdag|onsdag|torsdag|fredag|lördag|söndag|mån|tis|ons|tors|fre|lör|sön|den|till|på|från|senast|före|efter|innan|prov|provet|förhör|inlämning)\s+(\d{1,2})\/(\d{1,2})(?![\d/])/giu,
      (m, pre, w, d, mo) => (mo >= 1 && mo <= 12 && d >= 1 && d <= 31) ? `${pre}${w} ${+d} ${MONTHS[mo - 1]}` : m)
    .replace(/\bv(?:ecka)?\.?\s?(\d{1,2})\b/gi, 'vecka $1')
    .replace(/\b(?:s|sid)\.\s?(\d+)\s?[–—-]\s?(\d+)\b/gi, 'sidorna $1 till $2')
    .replace(/\b(?:s|sid)\.\s?(\d+)\b/gi, 'sidan $1')
    .replace(/\b(?:kl\.?\s?)?([01]?\d|2[0-3]):([0-5]\d)\b/gi, (m, hh, mm) => `klockan ${+hh}${mm === '00' ? '' : ' och ' + +mm}`)
    .replace(/\bkl\.\s?/gi, 'klockan ')
    .replace(/(\d)\s?[–—]\s?(\d)/g, '$1 till $2')
    .replace(/\bt\.ex\.?/gi, 'till exempel').replace(/\bbl\.a\.?/gi, 'bland annat').replace(/\bosv\.?/gi, 'och så vidare')
    .replace(/[*_#`>]+/g, '').replace(/\p{Extended_Pictographic}/gu, '').replace(/[ \t]{2,}/g, ' ').trim();
}

function speakPhone(text) {
  if (!window.speechSynthesis || !text) return;
  text = forSpeech(text);
  const u = new SpeechSynthesisUtterance(text);
  u.lang = 'sv-SE'; u.rate = 1.0; u.volume = 1;
  const v = svVoice(); if (v) u.voice = v;
  // iOS-bugg: cancel() direkt följt av speak() tappar ibland repliken – vänta en stund
  if (speechSynthesis.speaking || speechSynthesis.pending) {
    speechSynthesis.cancel();
    setTimeout(() => speechSynthesis.speak(u), 150);
  } else {
    speechSynthesis.speak(u);
  }
}

/** Text från coachen: läs upp med telefonens röst eller Pluggis röst. */
function speak(text) {
  if (S.voice === 'telefon') speakPhone(text);
  else if (S.voice === 'pluggis') speakServer(text);
}

// Pluggis röst hämtas över vanlig HTTP, mening för mening (nästa hämtas medan den förra spelas).
// Tål mobilnät och att iPhone lägger appen i bakgrunden bättre än ljud över WebSocket.
let speechRun = 0;
let currentSource = null;
function sentences(text) {
  const parts = (text.match(/[^.!?…]+[.!?…]+["”»)]*\s*|[^.!?…]+$/g) || [text]).map(x => x.trim()).filter(Boolean);
  const out = [];
  for (const p of parts) {
    if (out.length && (out[out.length - 1].length + p.length) < 120) out[out.length - 1] += ' ' + p;
    else out.push(p.length > 280 ? p.slice(0, 280) : p);
  }
  return out;
}
// ---- Språk: glosor och material på spanska, tyska, franska och engelska läses med rätt röst ----
/** Språket för ett ämne/en kortlek: 'es', 'de', 'fr', 'en' – annars ''. */
function langOf(...texts) {
  const t = texts.filter(Boolean).join(' ').toLowerCase();
  if (/spansk|españ|espan/.test(t)) return 'es';
  if (/tysk|deutsch/.test(t)) return 'de';
  if (/fransk|français/.test(t)) return 'fr';
  if (/engelsk|english/.test(t)) return 'en';
  return '';
}
/** Ser texten svensk ut (å/ä/ö eller vanliga svenska småord)? */
function looksSwedish(text) {
  const t = ` ${(text || '').toLowerCase()} `;
  return /[åäö]/.test(t) || / (och|att|jag|är|en|ett|det|med|för|inte|som|har|vi|du|han|hon|min|vad) /.test(t);
}
/** Gissar språket på en icke-svensk text: tecken (ñ ¿ ß ç …) och vanliga småord. */
function guessLang(text) {
  const t = ` ${(text || '').toLowerCase().replace(/[.,!?;:"()]/g, ' ')} `;
  const count = (re) => (t.match(re) || []).length;
  const score = {
    es: count(/[ñ¿¡]/g) * 3 + count(/ (el|la|los|las|un|una|que|y|es|por|para|con|muy|soy|estoy|tengo|hola|gracias|de|del|mi|tu|su) /g),
    de: count(/ß/g) * 3 + count(/ (der|die|das|und|ist|ich|nicht|mit|ein|eine|zu|sie|wir|auf|für|bin|habe) /g),
    fr: count(/[çœ]/g) * 3 + count(/[èêàù]/g) + count(/ (le|les|et|est|je|vous|une|pour|avec|pas|ne|bonjour|merci|des|du|au|nous|suis) /g),
    en: count(/ (the|and|is|are|you|of|to|with|this|that|it|have|has|was|what|my|your) /g),
  };
  const best = Object.entries(score).sort((a, b) => b[1] - a[1])[0];
  return best[1] > 0 ? best[0] : '';
}
/**
 * Röstens språk för en mening. Språkämne (spanska, tyska …): alltid språkets röst – även de svenska delarna.
 * Annars svensk röst, utom för text som ser utländsk ut.
 */
function voiceLang(text, lang) { return lang || (looksSwedish(text) ? '' : guessLang(text)); }
const PHONE_LANG = { es: 'es-ES', de: 'de-DE', fr: 'fr-FR', en: 'en-GB' };

async function fetchVoice(text, lang = '') {
  const url = '/api/speech/preview' + (lang ? `?lang=${lang}` : '');
  let res = await fetch(url, { method: 'POST',
    headers: { 'X-Pluggis-Token': S.token, 'Content-Type': 'application/json' }, body: JSON.stringify({ text }) });
  if (!res.ok && lang) {   // ingen språkröst på servern (Azure saknas) – svensk röst hellre än tystnad
    res = await fetch('/api/speech/preview', { method: 'POST',
      headers: { 'X-Pluggis-Token': S.token, 'Content-Type': 'application/json' }, body: JSON.stringify({ text }) });
  }
  if (!res.ok) throw new Error(String(res.status));
  return res.arrayBuffer();
}
async function speakServer(text, lang = '') {
  const run = ++speechRun;
  stopServerAudio();
  // En lista (t.ex. fråga + alternativ) läses del för del, så att varje del får rätt språk
  const list = Array.isArray(text) ? text.flatMap(t => sentences(t)) : sentences(text);
  let next = list.length ? fetchVoice(list[0], voiceLang(list[0], lang)) : null;
  for (let i = 0; i < list.length; i++) {
    let buf;
    try { buf = await next; } catch { if (run === speechRun) toast('Kunde inte hämta Pluggis röst', true); return; }
    if (run !== speechRun) return;               // ett nytt svar har kommit – sluta läsa det gamla
    next = i + 1 < list.length ? fetchVoice(list[i + 1], voiceLang(list[i + 1], lang)) : null;
    const ok = await playBuffer(buf);
    if (run !== speechRun) return;
    if (!ok && !playerUnlocked) {
      // Ljudet blockerades – läs resten med telefonens röst hellre än att det blir tyst
      if (window.speechSynthesis) speakPhone(list.slice(i).join(' '));
      return;
    }
  }
}
let currentUrl = null;
function stopServerAudio() {
  try { player.pause(); } catch { /* inget spelas */ }
  if (currentUrl) { URL.revokeObjectURL(currentUrl); currentUrl = null; }
}

/** Spelar en wav. Löser alltid ut – med tidsgräns – så att en fastnad uppspelning aldrig stoppar nästa svar. */
function playBuffer(arrayBuffer) {
  return new Promise((resolve) => {
    let done = false;
    const finish = (ok) => {
      if (done) return;
      done = true;
      clearTimeout(timer);
      player.onended = player.onerror = null;
      resolve(ok);
    };
    // wav från Piper: ca 44 kB per sekund → tidsgräns = längden + 4 s
    const seconds = Math.min(60, arrayBuffer.byteLength / 44100 + 4);
    const timer = setTimeout(() => finish(false), seconds * 1000);
    try {
      stopServerAudio();
      currentUrl = URL.createObjectURL(new Blob([arrayBuffer], { type: 'audio/wav' }));
      player.onended = () => finish(true);
      player.onerror = () => finish(false);
      player.src = currentUrl;
      const p = player.play();
      if (p && p.catch) {
        p.catch(() => {
          // iOS vägrade spela (inget tryck än) – be om ett tryck
          playerUnlocked = false;
          toast('Tryck på skärmen så hörs Pluggis');
          finish(false);
        });
      }
    } catch { finish(false); }
  });
}

/** Reserv: vanligt <audio>-element (om Web Audio inte kan avkoda). */
function playWithElement(arrayBuffer) {
  return new Promise((resolve) => {
    try {
      const url = URL.createObjectURL(new Blob([arrayBuffer], { type: 'audio/wav' }));
      const a = new Audio(url);
      a.onended = a.onerror = () => { URL.revokeObjectURL(url); resolve(); };
      a.play().catch(() => resolve());
    } catch { resolve(); }
  });
}

function setVoice(mode) {
  S.voice = mode;
  store.set('pluggis.chat.voice', mode);
  paintSpeak();
  unlockAudio();
  if (mode !== 'pluggis') { speechRun++; stopServerAudio(); }
  if (mode !== 'telefon' && window.speechSynthesis) speechSynthesis.cancel();
}

$('#speak').addEventListener('click', () => {
  const modes = S.serverVoice ? VOICE_MODES : ['av', 'telefon'];
  const next = modes[(modes.indexOf(S.voice) + 1) % modes.length] || 'av';
  setVoice(next);
  if (next === 'pluggis') { toast('🔊 Pluggis läser upp svaren'); testServerVoice(); }
  else if (next === 'telefon') { toast('📱 Telefonens röst läser upp svaren'); speakPhone('Hej! Nu läser jag upp svaren.'); }
  else toast('🔇 Uppläsning av');
});
function paintSpeak() {
  const b = $('#speak');
  b.textContent = S.voice === 'av' ? '🔇' : S.voice === 'pluggis' ? '🔊' : '📱🔊';
  b.setAttribute('aria-pressed', String(S.voice !== 'av'));
  b.title = { av: 'Uppläsning av – tryck för att slå på', pluggis: 'Pluggis röst läser upp – tryck för att byta', telefon: 'Telefonens röst läser upp – tryck för att byta' }[S.voice];
}

// ---- WebSocket till coachen ----
// Ett id per öppnad sida: servern stänger vår gamla anslutning när vi ansluter igen.
const CLIENT_ID = Math.random().toString(36).slice(2, 10) + Date.now().toString(36);
function connect() {
  if (S.ws && S.ws.readyState <= 1) return;
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  const ws = new WebSocket(`${proto}://${location.host}/ws/device?deviceId=${enc(S.deviceId)}&token=${enc(S.token)}&client=${CLIENT_ID}`);
  ws.binaryType = 'arraybuffer';
  S.ws = ws;
  ws.onopen = () => {
    S.retry = 0;
    $('#conn').className = 'dot on';
    ws.send(JSON.stringify({ type: 'hello', audio: false }));
    if (S.queued) { const q = S.queued, retry = S.queuedRetry; S.queued = null; S.queuedRetry = false; send(q, retry); }
  };
  ws.onclose = (e) => {
    if (S.ws === ws) S.ws = null;
    $('#conn').className = 'dot off';
    if (e.code === 1008) { addMsg('err', 'Koden fungerar inte – be om en ny.'); return; }
    const wait = Math.min(15000, 1000 * 2 ** S.retry++);
    setTimeout(() => { if (document.visibilityState === 'visible') connect(); }, wait);
  };
  ws.onmessage = (e) => {
    if (S.ws !== ws) { try { ws.close(); } catch { /* redan */ } return; }   // gammal anslutning – ignorera
    if (typeof e.data !== 'string') return;
    let m; try { m = JSON.parse(e.data); } catch { return; }
    onServer(m);
  };
}
document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible') { checkAlive(); refreshPoints(); checkVersion(); } });

/**
 * Ny version på servern? Hemskärms-appen på iPhone laddas inte om när man växlar tillbaka till den,
 * så vi frågar när appen kommer fram (och var tionde minut) och laddar om när eleven inte är mitt i något.
 */
let versionTimer = null;
async function checkVersion() {
  if (!S.version) return;
  let v;
  try { v = (await api('/api/chat/me')).version; } catch { return; }
  if (!v || v === S.version) return;
  const busy = () => (document.querySelector('textarea') || {}).value || readingBtn || !$('#sheet').hidden;
  const reload = () => { if (busy()) { clearTimeout(versionTimer); versionTimer = setTimeout(reload, 15000); return; } location.reload(); };
  reload();
}
setInterval(() => { if (document.visibilityState === 'visible') checkVersion(); }, 10 * 60 * 1000);
window.addEventListener('pageshow', () => checkAlive());
window.addEventListener('online', () => checkAlive());
// Livstecken: iOS kan lämna en anslutning som ser öppen ut men är död. Svarar den inte på ping
// inom 6 s ansluter vi om.
let pongTimer = null;
function checkAlive() {
  if (!S.ws || S.ws.readyState !== 1) { connect(); return; }
  clearTimeout(pongTimer);
  const ws = S.ws;
  try { ws.send('{"type":"ping"}'); } catch { /* stängd */ }
  pongTimer = setTimeout(() => {
    if (S.ws === ws) { S.ws = null; try { ws.close(); } catch { /* redan */ } $('#conn').className = 'dot off'; connect(); }
  }, 6000);
}
setInterval(() => { if (document.visibilityState === 'visible') checkAlive(); }, 20000);

function send(obj, isRetry) {
  if (!S.ws || S.ws.readyState !== 1) {
    // Köa och skicka när anslutningen är uppe igen
    S.queued = obj;
    toast('Ansluter igen…');
    connect();
    return true;
  }
  S.ws.send(JSON.stringify(obj));
  if (obj.type === 'text' || obj.type === 'button') {
    // Kommer inget "tänker" inom 5 s var anslutningen död – anslut om och skicka igen (en gång)
    clearTimeout(S.waitTimer);
    S.pending = obj;
    S.waitTimer = setTimeout(() => {
      if (S.pending !== obj || isRetry) return;
      const ws = S.ws; S.ws = null;
      try { if (ws) ws.close(); } catch { /* redan */ }
      S.queued = obj; S.queuedRetry = true;
      connect();
    }, 5000);
  }
  return true;
}

function onServer(m) {
  if (m.type === 'pong') { clearTimeout(pongTimer); return; }
  if (m.type === 'thinking' || m.type === 'say') { clearTimeout(S.waitTimer); S.pending = null; }
  switch (m.type) {
    case 'thinking': setThinking(true); break;
    case 'say':
      setThinking(false);
      if (m.text === S.lastSay && Date.now() - S.lastSayAt < 15000) break;   // samma svar en gång till – säg det inte igen
      S.lastSay = m.text; S.lastSayAt = Date.now();
      addMsg('bot', m.text); speak(m.text); break;
    case 'heard': addMsg('me', m.text); break;
    case 'reminder': addMsg('bot reminder', '🔔 ' + m.text); speak(m.text); break;
    case 'error': setThinking(false); addMsg('err', m.message || m.text || 'Något gick fel'); break;
    case 'points': if (S.points !== false) { toast(`+${m.delta} poäng${m.reason ? ' – ' + m.reason : ''}`); setPoints(m.weekTotal); } break;
    case 'card': addCard({ front: m.front, hint: m.hint }); break;
    case 'card_answer': addCard({ front: m.back, result: m.correct, box: m.box }); break;
    case 'session':
      if (m.state === 'started') S.session = { task: m.task, total: (m.minutes || 0) * 60, left: (m.minutes || 0) * 60 };
      if (m.state === 'finished') S.session = null;
      if (m.state === 'time_up') addMsg('note', '⏰ Tiden är ute!');
      paintStatus();
      break;
    case 'timer':
      S.session = { task: m.task, total: m.totalSeconds, left: m.remainingSeconds };
      paintStatus();
      break;
    case 'take_photo': openUpload(); break;
    case 'open_practice': openPractice(m.kind, m.homeworkId); break;
    default: break;
  }
}

// ---- Chatthistorik (sparas i telefonen, senaste 150) ----
function remember(entry) {
  S.history.push(entry);
  if (S.history.length > 150) S.history = S.history.slice(-150);
  store.set('pluggis.chat.history', JSON.stringify(S.history));
}
function addMsg(kind, text, save = true) {
  if (save) remember({ k: kind, t: text });
  const list = $('#msgs');
  if (!list) return;
  list.append(h('div', { class: 'msg ' + kind }, text));
  scrollDown();
}
function addCard(c, save = true) {
  if (save) remember({ k: 'card', c });
  const list = $('#msgs');
  if (!list) return;
  const cls = 'card' + (c.result === true ? ' right' : c.result === false ? ' wrong' : '');
  list.append(h('div', { class: cls }, c.front,
    c.result === undefined
      ? (c.hint ? h('small', {}, 'Skriv ditt svar – eller be om en ledtråd') : h('small', {}, 'Skriv ditt svar'))
      : h('small', {}, `${c.result ? 'Rätt!' : 'Inte riktigt'}${c.box ? ` · fack ${c.box}` : ''}`)));
  scrollDown();
}
function setThinking(on) {
  S.thinking = on;
  const list = $('#msgs');
  if (!list) return;
  list.querySelector('.thinking')?.remove();
  if (on) { list.append(h('div', { class: 'msg bot thinking' }, h('span', { class: 'typing' }, h('i'), h('i'), h('i')))); scrollDown(); }
}
function scrollDown() { const l = $('#msgs'); if (l) requestAnimationFrame(() => { l.scrollTop = l.scrollHeight; }); }

function paintStatus() {
  const box = $('#status');
  if (!box) return;
  if (!S.session) { box.hidden = true; return; }
  box.hidden = false;
  const min = Math.max(0, Math.ceil(S.session.left / 60));
  const pct = S.session.total ? Math.max(0, Math.round(100 * S.session.left / S.session.total)) : 0;
  box.replaceChildren(`⏱ ${S.session.task || 'Pluggpass'} · ${min} min kvar`,
    h('div', { class: 'bar-track' }, h('div', { class: 'bar-fill', style: `width:${pct}%` })));
}

// Linjeritad mikrofon, som den på iPhones tangentbord
const MIC_SVG = '<svg class="mic-ico" viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">'
  + '<rect x="9" y="3" width="6" height="11" rx="3"/><path d="M5.5 11a6.5 6.5 0 0 0 13 0"/><path d="M12 17.5V21"/></svg>';

// ---- Vyer ----
const views = {};

views.chatt = () => {
  const input = h('textarea', { rows: 1, placeholder: S.name ? `Skriv till Pluggis, ${S.name}…` : 'Skriv till Pluggis…', enterkeyhint: 'send',
    oninput: () => { input.style.height = 'auto'; input.style.height = Math.min(120, input.scrollHeight) + 'px'; },
    onkeydown: (e) => { if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) { e.preventDefault(); sendText(); } } });
  const Recognition = window.SpeechRecognition || window.webkitSpeechRecognition;
  // Webbläsarens taligenkänning kräver https. Över vanlig http (hemma på wifi) funkar ändå
  // tangentbordets egen mikrofon på iPhone – den visar vi en ledtråd om i stället.
  const mic = Recognition && window.isSecureContext ? h('button', { class: 'round', title: 'Prata', onclick: listen, html: MIC_SVG }) : null;
  const micHint = !mic && /iPhone|iPad/.test(navigator.userAgent)
    ? h('div', { class: 'mic-hint', html: `Vill du prata? Tryck på ${MIC_SVG} på tangentbordet.` }) : null;
  if (micHint) input.placeholder = S.name ? `Skriv eller prata, ${S.name}…` : 'Skriv eller prata…';

  function sendText() {
    const text = input.value.trim();
    if (!text) return;
    unlockAudio();
    if (send({ type: 'text', text })) {
      addMsg('me', text);
      input.value = ''; input.style.height = 'auto';
    }
  }
  function press(button, gesture, label) {
    unlockAudio();
    if (send({ type: 'button', button, gesture })) addMsg('note', label);
  }
  function listen() {
    const rec = new Recognition();
    rec.lang = 'sv-SE'; rec.interimResults = false;
    mic.classList.add('rec'); mic.textContent = '●';
    rec.onresult = (e) => { input.value = e.results[0][0].transcript; sendText(); };
    rec.onerror = () => toast('Hörde inte – testa igen', true);
    rec.onend = () => { mic.classList.remove('rec'); mic.innerHTML = MIC_SVG; };
    rec.start();
  }

  const msgs = h('div', { class: 'msgs', id: 'msgs' });
  const wrap = h('div', { style: 'display:flex;flex-direction:column;flex:1;min-height:0' },
    h('div', { class: 'status', id: 'status', hidden: true }),
    msgs,
    h('div', { class: 'quick' },
      h('button', { onclick: () => press('main', 'click', S.session ? 'Jag har fastnat' : 'Jag vill börja plugga') }, S.session ? '😵 Jag har fastnat' : '▶ Börja plugga'),
      h('button', { onclick: () => { if (send({ type: 'text', text: 'Kan du förhöra mig med flashkort?' })) addMsg('me', 'Kan du förhöra mig med flashkort?'); } }, '🃏 Förhör mig'),
      h('button', { onclick: () => { if (send({ type: 'text', text: 'Vad ska jag göra idag?' })) addMsg('me', 'Vad ska jag göra idag?'); } }, '📋 Vad idag?'),
      S.session ? h('button', { onclick: () => press('main', 'long', 'Avslutar passet') }, '⏹ Klar') : null),
    micHint,
    h('div', { class: 'composer' },
      h('button', { class: 'round', title: 'Ladda upp foto eller fil', onclick: () => openUpload() }, '📎'),
      input, mic,
      h('button', { class: 'round send', title: 'Skicka', onclick: sendText }, '➤')));

  for (const e of S.history) {
    if (e.k === 'card') addCardTo(msgs, e.c); else msgs.append(h('div', { class: 'msg ' + e.k }, e.t));
  }
  if (!S.history.length) {
    msgs.append(h('div', { class: 'msg bot' },
      `Hej${S.name ? ' ' + S.name : ''}! 👋 Jag är Pluggis. Tryck ▶ Börja plugga när du vill köra, fråga om läxor, eller 📎 för att skicka ett foto på en uppgift.`));
  }
  if (S.thinking) msgs.append(h('div', { class: 'msg bot thinking' }, h('span', { class: 'typing' }, h('i'), h('i'), h('i'))));
  setTimeout(() => { paintStatus(); scrollDown(); }, 0);
  // Stor skärm (dator, iPad liggande): dagens plan bredvid chatten
  if (WIDE.matches) {
    return h('div', { class: 'chat-wide' }, wrap, todayAside());
  }
  return wrap;

  function addCardTo(list, c) {
    const cls = 'card' + (c.result === true ? ' right' : c.result === false ? ' wrong' : '');
    list.append(h('div', { class: cls }, c.front, c.result === undefined ? null
      : h('small', {}, `${c.result ? 'Rätt!' : 'Inte riktigt'}${c.box ? ` · fack ${c.box}` : ''}`)));
  }
};

/** Bred skärm (dator i webbläsaren): sidomeny i stället för flikrad, och dagens plan bredvid chatten. */
const WIDE = window.matchMedia ? window.matchMedia('(min-width: 1100px)') : { matches: false };
WIDE.addEventListener && WIDE.addEventListener('change', () => { if (S.tab === 'chatt') show('chatt'); });

function todayAside() {
  const aside = h('aside', { class: 'side' }, h('div', { class: 'side-title' }, 'Idag'), h('div', { class: 'empty' }, '…'));
  (async () => {
    try {
      const [today, progress, hw] = await Promise.all([api('/api/plan/today'), api('/api/plan/progress').catch(() => []),
        api('/api/homework?openOnly=false').catch(() => [])]);
      const hwById = new Map(hw.map(x => [x.id, x]));
      const parts = [h('div', { class: 'side-title' }, 'Idag')];
      if (!today.length) parts.push(h('div', { class: 'empty' }, 'Inget planerat idag 🎉'));
      const list = h('div', { class: 'box' });
      // Ett block per läxa/prov, samma som i Plan (servern har redan sorterat)
      const groups = [];
      for (const t of today) {
        const last = groups[groups.length - 1];
        if (last && last.id === t.item.homeworkId) last.items.push(t); else groups.push({ id: t.item.homeworkId, items: [t] });
      }
      for (const g of groups) {
        const x = hwById.get(g.id);
        const b = { color: subjectColor(x && x.subject, x ? x.title : ''), items: g.items };
        list.append(h('div', { class: 'block-head', style: `--c:${b.color}` }, h('span', { class: 'block-ico' }, subjectIcon(x && x.subject, x ? x.title : '')),
          h('span', { class: 'block-title' }, x ? blockLabel(x) : (g.items[0].homeworkTitle || 'Övrigt')),
          h('span', { class: 'block-min' }, `${g.items.reduce((n, t) => n + t.item.minutes, 0)} min`)));
        for (const t of b.items) {
          const it = t.item;
          const tick = h('button', { class: 'tick', 'aria-label': 'Klar', disabled: it.done || null }, it.done ? '✓' : '');
          const row = h('div', { class: 'row in-block' + (it.done ? ' done' : ''), style: `--c:${b.color}` }, tick,
            h('div', { class: 'grow' }, h('div', { class: 'task' }, it.task), h('div', { class: 'meta' }, `${it.minutes} min`,
              t.overdue ? ' · ligger efter' : '')));
          tick.addEventListener('click', async () => {
            if (it.done) return;
            try {
              await api(`/api/plan/items/${it.id}/done?deviceId=${enc(S.deviceId)}`, { method: 'POST' });
              it.done = true; row.classList.add('done'); tick.textContent = '✓'; tick.disabled = true;
              refreshPoints(); toast('Bra jobbat!' + pts(' +3 poäng'));
            } catch (e) { toast(e.message, true); }
          });
          list.append(row);
        }
      }
      if (today.length) parts.push(list);
      if (progress.length) parts.push(h('div', { class: 'side-title' }, 'På väg mot'), ...progress.slice(0, 3).map(progressCard));
      parts.push(h('button', { class: 'small-btn', style: 'margin-top:12px', onclick: () => show('plan') }, 'Hela planen ›'));
      aside.replaceChildren(...parts);
    } catch (e) {
      console.warn('Dagens plan:', e);
      aside.replaceChildren(h('div', { class: 'empty' }, 'Kunde inte hämta planen'));
    }
  })();
  return aside;
}

/** Nedräkning och hur långt man kommit, för ett prov/en läxa. */
function progressCard(p) {
  const when = p.daysLeft === 0 ? 'idag' : p.daysLeft === 1 ? 'imorgon' : p.daysLeft == null ? '' : `om ${p.daysLeft} dagar`;
  return h('div', { class: 'prog' },
    h('div', { class: 'prog-head' },
      h('span', { class: 'tag' + (p.kind === 'TEST' ? ' test' : '') }, p.kind === 'TEST' ? 'Prov' : 'Läxa'),
      h('span', { class: 'prog-title' }, p.title),
      h('span', { class: 'prog-when' }, when)),
    h('div', { class: 'prog-track' }, h('div', { class: 'prog-fill', style: `width:${p.percent}%` })),
    h('div', { class: 'meta' }, `${p.percent} % klart · ${p.doneItems} av ${p.totalItems} moment`,
      p.behind ? ` · ${p.behind} ligger efter` : ''));
}

views.plan = async () => {
  const page = h('div', { class: 'page' }, h('h2', {}, 'Planen'));
  const [today, upcoming, progress, hw] = await Promise.all([api('/api/plan/today'), api('/api/plan/upcoming?days=14'),
    api('/api/plan/progress').catch(() => []), api('/api/homework?openOnly=false').catch(() => [])]);
  const hwById = new Map(hw.map(x => [x.id, x]));
  // Ämne + titel, t.ex. "NO · Prov fotosyntes" (ämnet bara om det inte redan står i titeln)
  const titleOf = (id, fallback) => {
    const x = hwById.get(id);
    if (!x) return fallback || null;
    const subj = (x.subject || '').trim();
    return subj && !x.title.toLowerCase().includes(subj.toLowerCase()) ? `${subj} · ${x.title}` : x.title;
  };
  const allDone = today.length > 0 && today.every(t => t.item.done);
  if (allDone) page.append(h('div', { class: 'day-done' }, '🎉 Allt för idag är klart! Bra jobbat.'));
  page.append(h('h3', {}, 'Idag'), today.length
    ? h('div', { class: 'box', id: 'todayBox' }, blocks(today.map(t => ({ item: t.item, overdue: t.overdue, title: t.homeworkTitle }))))
    : h('div', { class: 'box' }, h('div', { class: 'empty' }, 'Inget planerat idag 🎉')));
  if (progress.length) page.append(h('h3', {}, 'På väg mot'), h('div', { class: 'progs' }, progress.slice(0, 5).map(progressCard)));

  const todayStr = todayIso();
  const later = new Map();
  for (const i of upcoming) {
    if (i.planDate <= todayStr) continue;
    if (!later.has(i.planDate)) later.set(i.planDate, []);
    later.get(i.planDate).push(i);
  }
  for (const [day, items] of later) {
    page.append(h('h3', {}, dayName(day)), h('div', { class: 'box' }, blocks(items.map(i => ({ item: i, overdue: false })))));
  }
  if (!later.size) page.append(h('p', { class: 'empty' }, 'Inget mer planerat de kommande två veckorna.'));
  return page;

  /** Ett block per läxa/prov: rubrik med ämnets symbol och färg, sedan momenten. Servern har redan grupperat. */
  function blocks(list) {
    const out = [];
    let cur = null;
    for (const t of list) {
      const id = t.item.homeworkId;
      if (!cur || cur.id !== id) {
        const x = hwById.get(id);
        const color = subjectColor(x && x.subject, x ? x.title : t.title);
        const mins = list.filter(o => o.item.homeworkId === id).reduce((n, o) => n + o.item.minutes, 0);
        cur = { id, color };
        out.push(h('div', { class: 'block-head', style: `--c:${color}` },
          h('span', { class: 'block-ico' }, subjectIcon(x && x.subject, x ? x.title : t.title)),
          h('span', { class: 'block-title' }, x ? blockLabel(x) : (t.title || 'Övrigt'), x && x.weight >= 80 ? ' ⭐' : ''),
          h('span', { class: 'block-min' }, `${mins} min`)));
      }
      const row = planRow(t.item, null, t.overdue);
      row.classList.add('in-block'); row.style.setProperty('--c', cur.color);
      out.push(row);
    }
    return out;
  }

  function planRow(item, homeworkTitle, overdue) {
    const tick = h('button', { class: 'tick', 'aria-label': 'Klar', disabled: item.done || null }, item.done ? '✓' : '');
    const examBtn = !item.done && /testprov/i.test(item.task)
      ? h('button', { class: 'small-btn', style: 'margin-left:8px', onclick: (e) => { e.stopPropagation(); startExam(item.homeworkId, e.target); } }, '📝 Starta') : null;
    const row = h('div', { class: 'row' + (item.done ? ' done' : '') }, tick,
      h('div', { class: 'grow' }, h('div', { class: 'task' }, item.task, examBtn),
        h('div', { class: 'meta' }, `${item.minutes} min`, homeworkTitle ? ` · ${homeworkTitle}` : '',
          overdue ? ' · ' : '', overdue ? h('span', { class: 'tag late' }, 'ligger efter') : '')));
    tick.addEventListener('click', async () => {
      if (item.done) return;
      try {
        await api(`/api/plan/items/${item.id}/done?deviceId=${enc(S.deviceId)}`, { method: 'POST' });
        item.done = true; row.classList.add('done'); tick.textContent = '✓'; tick.disabled = true;
        refreshPoints();
        const box = document.getElementById('todayBox');
        const rest = box ? [...box.querySelectorAll('.row')].filter(r => !r.classList.contains('done')).length : 1;
        if (box && box.contains(row) && rest === 0) {
          toast('🎉 Hela dagen klar!' + pts(' +3 och +5 i bonus'));
          setTimeout(() => show('plan'), 1200);
        } else toast('Bra jobbat!' + pts(' +3 poäng'));
      } catch (e) { toast(e.message, true); }
    });
    return row;
  }
};

views.laxor = async () => {
  if (S.openHomework) return homeworkDetail(S.openHomework);
  const page = h('div', { class: 'page' },
    h('button', { class: 'big', onclick: () => openUpload() }, '📷 Fota eller ladda upp en läxa'),
    h('h2', {}, 'Läxor och prov'));
  const all = (await api('/api/homework')).filter(x => x.kind !== 'OTHER')
    .sort((a, b) => (a.dueDate || '9999').localeCompare(b.dueDate || '9999'));
  S.homework = all;
  if (!all.length) { page.append(h('div', { class: 'box' }, h('div', { class: 'empty' }, 'Inga läxor inlagda. Fota veckoplanen så läser Pluggis av den.'))); return page; }
  const upcoming = all.filter(x => !x.dueDate || daysUntil(x.dueDate) >= 0);
  const past = all.filter(x => x.dueDate && daysUntil(x.dueDate) < 0).reverse();
  page.append(upcoming.length ? h('div', { class: 'box' }, upcoming.map(row))
    : h('div', { class: 'box' }, h('div', { class: 'empty' }, 'Inget kommande just nu 🎉')));
  if (past.length) {
    const box = h('div', { class: 'box', hidden: true }, past.map(row));
    const btn = h('button', { class: 'small-btn', onclick: () => { box.hidden = !box.hidden; btn.textContent = box.hidden ? 'Visa' : 'Dölj'; } }, 'Visa');
    page.append(h('div', { style: 'display:flex;align-items:center;gap:8px;margin-top:18px' },
      h('h3', { style: 'margin:0;flex:1' }, `Passerat (${past.length})`), btn), box);
  }
  function row(x) {
    const isPast = x.dueDate && daysUntil(x.dueDate) < 0;
    const missing = !isPast && !x.description && !(x.steps || []).length;
    return h('div', { class: 'row tap', onclick: (e) => { if (e.target.closest('button')) return; S.openHomework = x.id; show('laxor'); } },
      h('div', { class: 'grow' },
        h('div', {}, h('span', { class: 'tag' + (x.kind === 'TEST' ? ' test' : '') }, x.kind === 'TEST' ? 'Prov' : 'Läxa'), ' ', x.title, x.weight >= 80 ? ' ⭐' : ''),
        h('div', { class: 'meta' }, [x.subject, rel(x.dueDate)].filter(Boolean).join(' · '),
          missing ? ' · ' : '', missing ? h('span', { class: 'tag late' }, 'vad ingår?') : '')),
      isPast ? null : h('button', { class: 'small-btn', onclick: () => openUpload(x.id) }, '📎 Material'));
  }
  page.append(h('p', { class: 'meta', style: 'color:var(--muted);font-size:14px;margin-top:12px' },
    'Lägg till material (bild på sidan, Word, PDF) så vet Pluggis vad som ingår och gör en plan.'));
  return page;
};

// ---- Flashkort, snabbquiz och lucktext ----
views.kort = async () => {
  // Kortlekar sparas inte – varje övning görs ny av läxans material
  const page = h('div', { class: 'page' }, h('h2', {}, 'Öva'));
  const hw = await api('/api/homework?openOnly=false').catch(() => []);
  const upcoming = hw.filter(x => x.status === 'OPEN' && x.kind !== 'OTHER').sort((a, b) => (a.dueDate || '9').localeCompare(b.dueDate || '9'));
  if (upcoming.length) page.append(quizMaker(upcoming));
  else page.append(h('div', { class: 'box' }, h('div', { class: 'empty' },
    'Inga läxor eller prov att öva på just nu. Lägg till en läxa eller skicka material med 📎, så kan Pluggis göra övningar av den.')));
  page.append(h('p', { class: 'hint-small' }, 'Tips: säg "förhör mig" i chatten så förhör Pluggis dig med rösten i stället.'));
  return page;
};

// ---- Fokus: öva på ett delområde (t.ex. "Siffror 1–100") i stället för hela läxan ----
const focusState = { chosen: {}, topics: {} };
function focusOf(homeworkId) { return focusState.chosen[homeworkId] || ''; }

/** Knappraden "Öva på: Allt · Siffror · Veckodagar …" för vald läxa. getId() ger läxans id. */
function focusRow(getId) {
  const row = h('div', { class: 'focus-row' });
  async function paint() {
    const id = getId();
    if (!id) { row.replaceChildren(); return; }
    let topics = focusState.topics[id];
    if (!topics) {
      row.replaceChildren(h('span', { class: 'meta' }, 'Letar delområden …'));
      try { topics = (await api(`/api/homework/${id}/topics`)).topics || []; } catch { topics = []; }
      focusState.topics[id] = topics;
      if (getId() !== id) return;
    }
    if (!topics.length) { row.replaceChildren(); return; }
    const cur = focusOf(id);
    row.replaceChildren(h('span', { class: 'focus-label' }, 'Öva på:'),
      ...['', ...topics].map(t => h('button', { class: 'focus-chip' + (t === cur ? ' on' : ''),
        onclick: () => { focusState.chosen[id] = t; paint(); } }, t || 'Allt')));
  }
  paint();
  return { el: row, paint };
}

/** Välj läxa/prov och sort, så gör Claude frågorna av materialet. */
function quizMaker(list) {
  const sel = h('select', { class: 'quiz-sel' }, list.map(x => h('option', { value: x.id }, `${x.kind === 'TEST' ? 'Prov' : 'Läxa'}: ${x.title}${x.dueDate ? ' · ' + rel(x.dueDate) : ''}`)));
  const go = (mode) => (e) => makeQuiz(Number(sel.value), mode, e.target);
  const focus = focusRow(() => Number(sel.value));
  sel.addEventListener('change', () => focus.paint());
  return h('div', { class: 'box quiz-maker' },
    h('div', { class: 'quiz-title' }, 'Ny övning'), sel, focus.el,
    h('div', { class: 'quiz-btns six' },
      h('button', { class: 'quiz-btn', onclick: go('quiz') }, '⚡', h('span', {}, 'Snabbquiz'), h('small', {}, 'fyra alternativ')),
      h('button', { class: 'quiz-btn', onclick: go('cloze') }, '✏️', h('span', {}, 'Lucktext'), h('small', {}, 'fyll i ordet')),
      h('button', { class: 'quiz-btn', onclick: (e) => explainFor(Number(sel.value), e.target) }, '🧠', h('span', {}, 'Förklara'), h('small', {}, 'lär ut till Pluggis')),
      h('button', { class: 'quiz-btn', onclick: (e) => startExam(Number(sel.value), e.target) }, '📝', h('span', {}, 'Testprov'), h('small', {}, 'som ett riktigt prov')),
      h('button', { class: 'quiz-btn', onclick: (e) => startMatch(Number(sel.value), e.target) }, '🧩', h('span', {}, 'Matcha par'), h('small', {}, 'slå ditt rekord')),
      h('button', { class: 'quiz-btn', onclick: (e) => startOrder(Number(sel.value), e.target) }, '🔢', h('span', {}, 'Rätt ordning'), h('small', {}, 'steg och händelser'))));
}

/** Coachen öppnar en övning (eleven bad om ett quiz i chatten): gå till Öva och starta den där. */
async function openPractice(kind, homeworkId) {
  await show('kort');
  if (!homeworkId) { toast('Välj läxa och övning här'); return; }
  const start = { quiz: () => makeQuiz(homeworkId, 'quiz'), cloze: () => makeQuiz(homeworkId, 'cloze'),
    explain: () => explainFor(homeworkId), exam: () => startExam(homeworkId),
    match: () => startMatch(homeworkId), order: () => startOrder(homeworkId) }[kind] || (() => makeQuiz(homeworkId, 'quiz'));
  toast(kind === 'match' ? 'Startar …' : 'Pluggis gör övningen …');
  start();
}

async function makeQuiz(homeworkId, mode, btn) {
  const label = btn ? btn.closest('button').innerHTML : '';
  if (btn) { btn = btn.closest('button'); btn.disabled = true; btn.innerHTML = '⏳<span>Pluggis gör frågor…</span><small>tar några sekunder</small>'; }
  try {
    const r = await api('/api/flashcards/quiz', { method: 'POST', body: { homeworkId, mode, count: 10, focus: focusOf(homeworkId) || null } });
    if (r.note) toast(r.note);
    practiceDeck({ deck: r.deck, dueNow: r.cardCount });
  } catch (e) {
    toast(e.message || 'Det gick inte att göra frågor just nu', true);
    if (btn) { btn.disabled = false; btn.innerHTML = label; }
  }
}

// ---- Tid i Öva räknas som plugg ----
/**
 * När en övning börjar frågar appen servern om ett id (servern minns starttiden). Aktiv tid räknas varje sekund
 * när appen syns och eleven tryckt/skrivit de senaste två minuterna. När eleven lämnar övningen skickas tiden;
 * från 1 minut blir den ett pass (minutpoäng från 3 min, och streak). Finns ett moment i dagens plan för läxan
 * frågar appen om det ska bockas av.
 */
const Practice = {
  id: null, what: null, homeworkId: null, active: 0, last: Date.now(), timer: null,
  async begin(what, homeworkId, passive) {
    if (this.timer && this.what === what) return;   // samma övning pågår redan
    this.end(false);
    this.paused = null;
    this.what = what; this.homeworkId = homeworkId || null; this.passive = passive || null; this.active = 0; this.last = Date.now();
    this.timer = setInterval(() => {
      // Aktiv tid: appen syns och eleven har tryckt nyligen – eller lyssnar just nu
      if ((passive && passive()) || (document.visibilityState === 'visible' && Date.now() - this.last < 120000)) this.active++;
    }, 1000);
    try {
      const r = await api('/api/practice/start', { method: 'POST', body: { what, homeworkId: this.homeworkId } });
      if (this.timer) this.id = r.id;
    } catch { /* tiden räknas bara inte */ }
  },
  end(ask = true) {
    if (!this.timer) return;
    clearInterval(this.timer); this.timer = null;
    const id = this.id, active = this.active;
    this.id = null;
    if (!id) return;
    api(`/api/practice/${id}/end?deviceId=${enc(S.deviceId)}`, { method: 'POST', body: { activeSeconds: active } })
      .then(r => {
        if (r.counted && r.minutes >= 1) { toast(`⏱ ${r.minutes} min övning räknad`); refreshPoints(); }
        if (ask && r.planItem) offerTick(r.planItem);
      }).catch(() => { /* tyst */ });
  },
  /** När appen stängs: skicka tiden ändå (utan fråga). */
  flush() {
    if (!this.timer || !this.id) { if (this.timer) { clearInterval(this.timer); this.timer = null; } return; }
    clearInterval(this.timer); this.timer = null;
    try {
      fetch(`/api/practice/${this.id}/end?deviceId=${enc(S.deviceId)}`, { method: 'POST', keepalive: true,
        headers: { 'X-Pluggis-Token': S.token, 'Content-Type': 'application/json' }, body: JSON.stringify({ activeSeconds: this.active }) });
    } catch { /* tyst */ }
    this.id = null;
  },
};
['pointerdown', 'keydown', 'input'].forEach(ev => document.addEventListener(ev, () => { Practice.last = Date.now(); }, { passive: true, capture: true }));
window.addEventListener('pagehide', () => Practice.flush());
// iPhone: appen i bakgrunden (byter app, låser skärmen) – skicka tiden direkt, iOS kan stänga appen sedan.
// Kommer eleven tillbaka till samma övning börjar räkningen om.
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'hidden' && Practice.timer) {
    const p = Practice;
    if (p.passive && p.passive()) return;   // lyssnar fortfarande (ljudet spelar vidare)
    p.paused = { what: p.what, homeworkId: p.homeworkId, passive: p.passive, view: $('#view').firstElementChild };
    p.flush();
  } else if (document.visibilityState === 'visible' && Practice.paused) {
    const q = Practice.paused;
    Practice.paused = null;
    if (q.view && q.view === $('#view').firstElementChild) Practice.begin(q.what, q.homeworkId, q.passive);   // samma övning syns fortfarande
  }
});

/** "Bocka av 'Öva glosor 1–15'?" – ett tryck efter en övning. */
function offerTick(item) {
  const sheet = $('#sheet');
  const close = () => { sheet.hidden = true; sheet.replaceChildren(); };
  sheet.replaceChildren(h('div', { class: 'panel' },
    h('h2', {}, 'Bra jobbat! 💪'),
    h('p', {}, 'Ska du bocka av det här i dagens plan?'),
    h('div', { class: 'box', style: 'padding:12px 14px;font-weight:600' }, item.task),
    h('div', { class: 'actions' },
      h('button', { onclick: close }, 'Inte än'),
      h('button', { class: 'go', onclick: async () => {
        close();
        try {
          await api(`/api/plan/items/${item.id}/done?deviceId=${enc(S.deviceId)}`, { method: 'POST' });
          toast('Avbockat!' + pts(' +3 poäng')); refreshPoints();
        } catch (e) { toast(e.message, true); }
      } }, '✓ Bocka av'))));
  sheet.hidden = false;
}

// ---- Matcha par ----
const shuffle = (a) => a.map(v => [Math.random(), v]).sort((x, y) => x[0] - y[0]).map(x => x[1]);
const clock = (ms) => { const s = Math.round(ms / 1000); return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`; };

/** Par av läxans material: Pluggis gör nya frågor (snabbquiz) och paren spelas i telefonen. */
async function startMatch(homeworkId, btn) {
  let cards = [];
  btn = btn && btn.closest('button');
  const label = btn ? btn.innerHTML : '';
  if (btn) { btn.disabled = true; btn.innerHTML = '⏳<span>Pluggis gör par…</span>'; }
  try {
    const r = await api('/api/flashcards/quiz', { method: 'POST', body: { homeworkId, mode: 'quiz', count: 10, focus: focusOf(homeworkId) || null } });
    cards = await api(`/api/flashcards/decks/${r.deck.id}/cards`);
  } catch (e) { toast(e.message, true); return; }
  finally { if (btn) { btn.disabled = false; btn.innerHTML = label; } }
  const usable = cards.filter(c => c.back && c.back.length <= 70 && c.front.length <= 90)
    .map(c => ({ id: c.id, left: c.kind === 'CLOZE' ? c.front.replace('___', '…') : c.front, right: c.back }));
  const seen = new Set();
  const fw = focusOf(homeworkId).toLowerCase().split(/[^a-zåäöéñü0-9]+/).filter(w => w.length > 3);
  const about = (p) => fw.some(w => `${p.left} ${p.right}`.toLowerCase().includes(w));
  const pool = shuffle(usable).sort((a, b) => about(b) - about(a)).filter(p => { const k = p.right.toLowerCase(); if (seen.has(k)) return false; seen.add(k); return true; });
  if (pool.length < 3) {
    toast('Pluggis hittade inte tillräckligt med par i materialet – prova Snabbquiz i stället', true);
    return;
  }
  // Variation: kort som var med de senaste gångerna hamnar sist
  const recentKey = `pluggis.match.${homeworkId}`;
  let recent = [];
  try { recent = JSON.parse(localStorage.getItem(recentKey) || '[]'); } catch { /* */ }
  pool.sort((a, b) => recent.includes(a.id) - recent.includes(b.id));
  const chosen = pool.slice(0, 6);
  try { localStorage.setItem(recentKey, JSON.stringify([...chosen.map(p => p.id), ...recent].slice(0, 18))); } catch { /* */ }
  const hw = (await api('/api/homework?openOnly=false').catch(() => [])).find(x => x.id === homeworkId);
  playMatch(chosen, hw ? hw.title : 'Matcha par', homeworkId);
}

function playMatch(pairs, title, homeworkId) {
  Practice.begin(`Matcha par: ${title}`, homeworkId);
  const key = `pluggis.match.${homeworkId}`;
  const best = Number(store.get(key, '0')) || 0;
  let picked = null, left = pairs.length, misses = 0;
  const started = Date.now();
  const timer = h('span', { class: 'match-time' }, '0:00');
  const tick = setInterval(() => { if (!document.body.contains(timer)) return clearInterval(tick); timer.textContent = clock(Date.now() - started); }, 500);
  const tile = (pair, side) => {
    const b = h('button', { class: 'match-tile ' + side }, side === 'l' ? pair.left : pair.right);
    b.addEventListener('click', () => choose(b, pair, side));
    return b;
  };
  const lefts = shuffle(pairs).map(p => tile(p, 'l'));
  const rights = shuffle(pairs).map(p => tile(p, 'r'));
  const status = h('div', { class: 'meta', style: 'text-align:center;margin-top:10px' }, 'Tryck på ett begrepp och sedan på det som hör ihop med det.');
  $('#view').replaceChildren(h('div', { class: 'page' },
    h('div', { style: 'display:flex;align-items:center;gap:8px' },
      h('button', { class: 'small-btn', onclick: () => { clearInterval(tick); show('kort'); } }, '‹ Öva'),
      h('h2', { style: 'margin:0;flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap' }, `🧩 ${title}`), timer),
    best ? h('div', { class: 'meta', style: 'margin:6px 0 0' }, `Ditt rekord: ${clock(best)}`) : null,
    h('div', { class: 'match-grid' }, h('div', { class: 'match-col' }, lefts), h('div', { class: 'match-col' }, rights)),
    status));

  function choose(b, pair, side) {
    if (b.classList.contains('done')) return;
    if (!picked || picked.side === side) {
      if (picked) picked.b.classList.remove('sel');
      picked = { b, pair, side }; b.classList.add('sel');
      return;
    }
    const other = picked; picked = null; other.b.classList.remove('sel');
    if (other.pair === pair) {
      [b, other.b].forEach(x => { x.classList.add('done'); x.disabled = true; });
      if (--left === 0) finish();
    } else {
      misses++;
      [b, other.b].forEach(x => { x.classList.add('miss'); setTimeout(() => x.classList.remove('miss'), 450); });
    }
  }

  async function finish() {
    clearInterval(tick);
    const ms = Date.now() - started;
    const record = !best || ms < best;
    if (record) store.set(key, String(ms));
    try { await api(`/api/games/done?deviceId=${enc(S.deviceId)}`, { method: 'POST', body: { kind: 'match', what: title } }); refreshPoints(); } catch { /* poäng är bonus */ }
    status.replaceChildren(h('div', { class: 'box exam-score' },
      h('div', { class: 'exam-big' }, clock(ms)),
      h('div', {}, record ? (best ? 'Nytt rekord! 🏆' : 'Första rekordet satt! 🏆') : `Rekordet är ${clock(best)} – en gång till?`),
      h('div', { class: 'meta' }, `${pairs.length} par · ${misses} ${misses === 1 ? 'miss' : 'missar'}`),
      h('div', { class: 'quiz-btns', style: 'margin-top:12px' },
        h('button', { class: 'quiz-btn', onclick: () => startMatch(homeworkId) }, '🔁', h('span', {}, 'En gång till')),
        h('button', { class: 'quiz-btn', onclick: () => show('kort') }, '🎯', h('span', {}, 'Till Öva')))));
  }
}

// ---- Rätt ordning ----
async function startOrder(homeworkId, btn) {
  btn = btn && btn.closest('button');
  const label = btn ? btn.innerHTML : '';
  if (btn) { btn.disabled = true; btn.innerHTML = '⏳<span>Pluggis letar ordningar…</span><small>tar några sekunder</small>'; }
  let g;
  try {
    g = await api('/api/games/order', { method: 'POST', body: { homeworkId, focus: focusOf(homeworkId) || null } });
  } catch (e) {
    toast(e.message || 'Det gick inte just nu', true);
    if (btn) { btn.disabled = false; btn.innerHTML = label; }
    return;
  }
  if (g.note) toast(g.note);
  Practice.begin('Rätt ordning', homeworkId);
  let k = 0, totalMiss = 0;
  play();

  function play() {
    const seq = g.sequences[k];
    const placed = h('div', { class: 'order-placed' });
    const pool = h('div', { class: 'order-pool' });
    let next = 0, misses = 0;
    const why = h('div', { class: 'meta order-why', hidden: true }, seq.why || '');
    const after = h('div');
    for (const step of shuffle(seq.steps)) {
      const b = h('button', { class: 'order-step' }, step);
      b.addEventListener('click', () => {
        if (step === seq.steps[next]) {
          next++;
          b.remove();
          placed.querySelector('.order-slot').replaceWith(h('div', { class: 'order-done' }, h('span', { class: 'order-n' }, String(next)), step));
          if (next === seq.steps.length) done();
        } else {
          misses++; totalMiss++;
          b.classList.add('miss'); setTimeout(() => b.classList.remove('miss'), 450);
        }
      });
      pool.append(b);
    }
    for (let i = 0; i < seq.steps.length; i++) placed.append(h('div', { class: 'order-slot' }, h('span', { class: 'order-n' }, String(i + 1))));
    $('#view').replaceChildren(h('div', { class: 'page' },
      h('div', { style: 'display:flex;align-items:center;gap:8px' },
        h('button', { class: 'small-btn', onclick: () => show('kort') }, '‹ Öva'),
        h('h2', { style: 'margin:0;flex:1' }, `🔢 ${seq.title}`),
        h('span', { class: 'meta' }, `${k + 1} av ${g.sequences.length}`)),
      h('p', { class: 'meta', style: 'margin:6px 0 10px' }, `${seq.prompt}. Tryck på det som kommer först, sedan nästa …`),
      placed, h('div', { class: 'meta', style: 'margin:12px 0 6px' }, 'Kvar:'), pool, why, after));
    autoRead(`${seq.title}. ${seq.prompt}.`);

    function done() {
      why.hidden = !seq.why;
      const last = k === g.sequences.length - 1;
      after.replaceChildren(h('div', { class: 'meta', style: 'text-align:center;margin:10px 0' },
        misses ? `Klart! ${misses} ${misses === 1 ? 'miss' : 'missar'} på vägen.` : 'Helt rätt på första försöket! 🎉'),
        h('button', { class: 'big', onclick: async () => {
          if (!last) { k++; play(); return; }
          try { await api(`/api/games/done?deviceId=${enc(S.deviceId)}`, { method: 'POST', body: { kind: 'order', what: g.sequences.map(s => s.title).join(', ') } }); refreshPoints(); } catch { /* bonus */ }
          toast(totalMiss ? `Alla ordningar klara! ${pts('+3 poäng')}` : `Alla rätt utan miss! 🎉 ${pts('+3 poäng')}`);
          show('kort');
        } }, last ? 'Klar ✓' : 'Nästa ›'));
    }
  }
}

// ---- Testprov ----
async function startExam(homeworkId, btn) {
  btn = btn && btn.closest('button');
  const label = btn ? btn.innerHTML : '';
  if (btn) { btn.disabled = true; btn.innerHTML = '⏳<span>Pluggis gör provet…</span><small>tar en halv minut</small>'; }
  let ex;
  try {
    ex = await api('/api/exam/start', { method: 'POST', body: { homeworkId, count: 10, focus: focusOf(homeworkId) || null } });
  } catch (e) {
    toast(e.message || 'Det gick inte just nu', true);
    if (btn) { btn.disabled = false; btn.innerHTML = label; }
    return;
  }
  if (ex.note) toast(ex.note);
  Practice.begin(ex.title, homeworkId);
  const answers = {};
  let i = -1;
  const mins = Math.max(5, Math.round(ex.questions.length * 1.2));
  // Startsida
  $('#view').replaceChildren(h('div', { class: 'page' },
    h('button', { class: 'small-btn', onclick: () => show('kort') }, '‹ Tillbaka'),
    h('div', { class: 'box exam-intro' },
      h('div', { class: 'exam-ico' }, '📝'), h('h2', {}, ex.title),
      h('p', {}, `${ex.questions.length} frågor · ca ${mins} minuter`),
      h('p', { class: 'meta' }, 'Som ett riktigt prov: svara på allt, sedan lämnar du in och får resultatet. Du kan gå fram och tillbaka. Gissa hellre än att lämna tomt!'),
      h('button', { class: 'big', onclick: () => go(0) }, 'Starta provet'))));

  function go(n) {
    i = n;
    const q = ex.questions[i];
    const last = i === ex.questions.length - 1;
    const bar = h('div', { class: 'quiz-progress' }, h('div', { style: `width:${Math.round(100 * i / ex.questions.length)}%` }));
    const body = h('div', { class: 'fc-card exam-q' });
    if (q.type === 'cloze') { const [a, b] = q.question.split('___'); body.append(a, h('span', { class: 'cloze-gap' }, answers[q.n] || '\u00a0\u00a0\u00a0\u00a0\u00a0\u00a0'), b || ''); }
    else body.textContent = q.question;
    let input;
    if (q.type === 'open') {
      input = h('textarea', { rows: 5, class: 'exam-text', placeholder: 'Skriv ditt svar – egna ord, 1–3 meningar (eller tryck på mikrofonen på tangentbordet)' });
      input.value = answers[q.n] || '';
      input.addEventListener('input', () => { answers[q.n] = input.value; });
    } else {
      const buttons = q.choices.map((c, k) => {
        const b = h('button', { class: 'choice' + (answers[q.n] === c ? ' picked' : ''), onclick: () => {
          answers[q.n] = c; buttons.forEach(x => x.classList.remove('picked')); b.classList.add('picked');
          if (q.type === 'cloze') body.querySelector('.cloze-gap').textContent = c;
        } }, h('span', { class: 'choice-letter' }, 'ABCD'[k]), h('span', {}, c));
        return b;
      });
      input = h('div', { class: 'choices' }, buttons);
    }
    const unanswered = () => ex.questions.filter(x => !(answers[x.n] || '').trim()).length;
    $('#view').replaceChildren(h('div', { class: 'page' },
      h('div', { style: 'display:flex;align-items:center;gap:8px' },
        h('h2', { style: 'margin:0;flex:1' }, `Fråga ${i + 1} av ${ex.questions.length}`),
        h('span', { class: 'meta' }, q.type === 'open' ? '2 p' : '1 p')),
      bar, body, h('div', { style: 'margin-top:14px' }, input),
      h('div', { class: 'exam-nav' },
        i > 0 ? h('button', { class: 'small-btn', onclick: () => go(i - 1) }, '‹ Förra') : h('span'),
        last ? h('button', { class: 'big', onclick: () => {
            const left = unanswered();
            if (left && !confirm(`${left} ${left === 1 ? 'fråga' : 'frågor'} utan svar. Lämna in ändå?`)) return;
            submit();
          } }, 'Lämna in')
          : h('button', { class: 'big', onclick: () => go(i + 1) }, 'Nästa ›'))));
    autoRead([q.question.replace(/___/g, ' lucka '), ...(q.choices || []).map((c, k) => `${'ABCD'[k]}: ${c}.`)], langOf(ex.title));
    if (q.type === 'open') input.focus();
  }

  async function submit() {
    $('#view').replaceChildren(h('div', { class: 'page' }, h('div', { class: 'box exam-intro' },
      h('div', { class: 'exam-ico' }, '⏳'), h('h2', {}, 'Pluggis rättar…'), h('p', { class: 'meta' }, 'Skrivfrågorna läses igenom – det tar några sekunder.'))));
    let r;
    try {
      r = await api(`/api/exam/${ex.id}/submit?deviceId=${enc(S.deviceId)}`, { method: 'POST', body: { answers } });
    } catch (e) {
      toast(e.message || 'Det gick inte att lämna in', true);
      go(ex.questions.length - 1);
      return;
    }
    refreshPoints();
    result(r);
  }

  function result(r) {
    const mood = r.percent >= 80 ? 'Riktigt starkt! 🎉' : r.percent >= 50 ? 'Bra jobbat – du är på god väg 💪' : 'Bra att du gjorde det nu – nu vet du vad du ska kolla 💪';
    $('#view').replaceChildren(h('div', { class: 'page' },
      h('div', { class: 'box exam-score' },
        h('div', { class: 'exam-big' }, `${r.points} / ${r.maxPoints}`),
        h('div', {}, `${r.percent} % · ${mood}`),
        S.points !== false ? h('div', { class: 'meta' }, '+10 poäng för att du gjorde provet') : null),
      r.review.length ? h('div', { class: 'box exam-review' },
        h('div', { class: 'quiz-title' }, 'Det här ska du kolla igen'),
        r.review.map(t => h('div', { class: 'ex-point miss' }, '○ ', t)),
        r.reviewDeckId ? h('button', { class: 'big', style: 'margin-top:10px', onclick: () => practiceDeck({ deck: { id: r.reviewDeckId, name: '🔁 Repetera' }, dueNow: r.review.length }) },
          '🔁 Öva på det du missade') : null) : null,
      h('h3', {}, 'Alla frågor'),
      h('div', { class: 'box' }, r.items.map(it => h('div', { class: 'row exam-item' },
        h('div', { class: 'exam-mark ' + (it.points === it.max ? 'ok' : it.points ? 'half' : 'miss') }, it.points === it.max ? '✓' : it.points ? '½' : '✗'),
        h('div', { class: 'grow' },
          h('div', { class: 'task' }, `${it.n}. ${it.question}`),
          h('div', { class: 'meta' }, 'Ditt svar: ', (it.yourAnswer || '').trim() || '–'),
          it.points < it.max ? h('div', { class: 'meta exam-answer' }, (it.type === 'open' ? 'Ett bra svar: ' : 'Rätt svar: ') + it.answer) : null,
          it.feedback ? h('div', { class: 'meta' }, it.feedback) : null),
        h('div', { class: 'meta' }, `${it.points}/${it.max}`)))),
      h('button', { class: 'big', style: 'margin-top:14px', onclick: () => show('kort') }, 'Tillbaka till Öva')));
  }
}

// ---- Förklara för Pluggis ----
async function explainFor(homeworkId, btn) {
  btn = btn && btn.closest('button');
  const label = btn ? btn.innerHTML : '';
  if (btn) { btn.disabled = true; btn.innerHTML = '⏳<span>Pluggis funderar…</span><small>tar några sekunder</small>'; }
  let r;
  try {
    r = await api('/api/explain/start', { method: 'POST', body: { homeworkId, focus: focusOf(homeworkId) || null } });
  } catch (e) {
    toast(e.message || 'Det gick inte just nu', true);
    if (btn) { btn.disabled = false; btn.innerHTML = label; }
    return;
  }
  const log = h('div', { class: 'ex-log' });
  const input = h('textarea', { rows: 3, placeholder: 'Förklara med egna ord – skriv, eller tryck på mikrofonen på tangentbordet' });
  const send = h('button', { class: 'big' }, 'Skicka');
  const box = h('div', { class: 'ex-input' }, input, send);
  const bubble = (who, text) => {
    const b = h('div', { class: `ex-msg ${who}` }, who === 'pluggis' ? h('span', { class: 'ex-who' }, '🧠') : null, h('div', {}, text));
    log.append(b); if (b.scrollIntoView) b.scrollIntoView({ behavior: 'smooth', block: 'end' });
    if (who === 'pluggis') speak(text);
  };
  $('#view').replaceChildren(h('div', { class: 'page' },
    h('div', { style: 'display:flex;align-items:center;gap:8px' },
      h('button', { class: 'small-btn', onclick: () => { stopReading(); show(S.openHomework ? 'laxor' : 'kort'); } }, '‹ Tillbaka'),
      h('h2', { style: 'margin:0;flex:1' }, `Förklara: ${r.concept}`)),
    h('p', { class: 'meta', style: 'color:var(--muted);margin:6px 0 10px' }, 'Pluggis kan inte det här – lär ut det! Med egna ord räcker, det behöver inte vara perfekt.'),
    log, box));
  if (r.note) toast(r.note);
  Practice.begin(`Förklara: ${r.concept}`, homeworkId);
  bubble('pluggis', r.reply);
  autoRead(r.reply);
  input.focus();

  send.addEventListener('click', async () => {
    const text = input.value.trim();
    if (!text) { input.focus(); return; }
    stopReading();
    bubble('me', text);
    input.value = ''; send.disabled = true; send.textContent = 'Pluggis lyssnar…';
    try {
      const t = await api(`/api/explain/${r.id}/answer?deviceId=${enc(S.deviceId)}`, { method: 'POST', body: { text } });
      bubble('pluggis', t.reply);
      autoRead(t.reply);
      if (t.done) {
        box.replaceWith(explainSummary(t, homeworkId));
        refreshPoints();
        return;
      }
    } catch (e) {
      toast(e.message || 'Det gick inte att skicka', true);
    }
    send.disabled = false; send.textContent = 'Skicka'; input.focus();
  });
}

function explainSummary(t, homeworkId) {
  const all = t.covered.length + t.missing.length;
  return h('div', { class: 'box ex-summary' },
    h('div', { class: 'ex-score' }, `Du fick med ${t.covered.length} av ${all}`,
      S.points !== false ? h('span', { class: 'meta' }, '  +5 poäng') : null),
    t.covered.map(p => h('div', { class: 'ex-point ok' }, '✓ ', p)),
    t.missing.length ? h('div', { class: 'meta', style: 'margin:8px 0 2px' }, 'Det här kan du lägga till nästa gång:') : null,
    t.missing.map(p => h('div', { class: 'ex-point miss' }, '○ ', p)),
    h('div', { class: 'quiz-btns', style: 'margin-top:12px' },
      h('button', { class: 'quiz-btn', onclick: (e) => explainFor(homeworkId, e.target) }, '🧠', h('span', {}, 'En till')),
      h('button', { class: 'quiz-btn', onclick: () => show('kort') }, '🎯', h('span', {}, 'Till Öva'))));
}

const norm = (t) => (t || '').toLowerCase().normalize('NFC').replace(/[^\p{L}\p{N} ]/gu, ' ').replace(/\s+/g, ' ').trim();

/** Öva en lek: vanliga kort (visa svar → rätt/fel) eller flerval/lucktext (tryck på ett alternativ). */
function practiceDeck(d) {
  Practice.begin(d.deck.name, d.deck.homeworkId);
  let onlyDue = d.dueNow > 0;
  const score = { done: 0, right: 0, total: d.dueNow || null };
  const front = h('div', { class: 'fc-front' });
  const back = h('div', { class: 'fc-back', hidden: true });
  const hintEl = h('div', { class: 'fc-hint', hidden: true });
  const controls = h('div', { class: 'fc-controls' });
  const status = h('div', { class: 'meta fc-status' });
  const bar = h('div', { class: 'quiz-progress' }, h('div'));
  let deckLang = langOf(d.deck.subject, d.deck.name);
  if (!deckLang) {
    api(`/api/flashcards/decks/${d.deck.id}/cards`).then(cards => {
      const votes = {};
      for (const c of cards) for (const t of [c.back, ...(c.wrongOptions || [])]) {
        if (!t || looksSwedish(t)) continue;
        const g = guessLang(t);
        if (g) votes[g] = (votes[g] || 0) + 1;
      }
      const best = Object.entries(votes).sort((a, b) => b[1] - a[1])[0];
      if (best && best[1] >= 2) deckLang = best[0];
    }).catch(() => {});
  }
  const say = h('button', { class: 'small-btn', onclick: () => readAloud(spoken(), say, deckLang) }, '🔊 Läs upp');
  let current = null;
  const spoken = () => {
    if (!current) return front.textContent;
    const q = current.front.replace(/___/g, ' lucka ');
    if (current.kind === 'BASIC' || !current.choices) return [q];
    return [q, ...current.choices.map((c, i) => `${'ABCD'[i]}: ${c}.`)];
  };
  $('#view').replaceChildren(h('div', { class: 'page' },
    h('div', { style: 'display:flex;align-items:center;gap:8px' },
      h('button', { class: 'small-btn', onclick: () => show('kort') }, '‹ Öva'),
      h('h2', { style: 'margin:0;flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap' }, d.deck.name)),
    bar, h('div', { class: 'fc-card' }, front, hintEl, back), h('div', { style: 'text-align:center;margin-top:8px' }, say),
    controls, status));
  next();

  function progress() {
    const n = score.total ? Math.min(100, Math.round(100 * score.done / score.total)) : 0;
    bar.firstChild.style.width = `${n}%`;
    bar.hidden = !score.total;
  }

  async function next() {
    back.hidden = true; hintEl.hidden = true; progress();
    const res = await api(`/api/flashcards/decks/${d.deck.id}/next?practiceAnyway=${!onlyDue}`, { raw: true }).catch(() => null);
    if (res && res.status === 404 && onlyDue) {
      current = null;
      front.textContent = !score.done ? 'Inget att repetera just nu.' : score.right * 2 >= score.done
        ? `Klart! ${score.right} rätt av ${score.done} 🎉` : `Klart! ${score.right} rätt av ${score.done} – bra att du körde. En gång till så sitter det bättre 💪`;
      controls.replaceChildren(h('button', { class: 'big', onclick: () => { onlyDue = false; score.total = null; next(); } }, 'Öva lite till ändå'),
        h('button', { class: 'small-btn', onclick: () => show('kort') }, 'Tillbaka till lekarna'));
      say.hidden = true; bar.hidden = true;
      return;
    }
    if (!res || !res.ok) { current = null; front.textContent = 'Inga kort i leken.'; controls.replaceChildren(); say.hidden = true; return; }
    const card = await res.json();
    current = card;
    say.hidden = false;
    back.textContent = card.back;
    hintEl.textContent = card.hint ? `Ledtråd: ${card.hint}` : '';
    const hintBtn = card.hint ? h('button', { class: 'small-btn', onclick: (e) => { hintEl.hidden = false; e.target.remove(); } }, '💡 Ledtråd') : null;
    if (card.kind === 'BASIC' || !(card.wrongOptions || []).length) {
      front.textContent = card.front;
      controls.replaceChildren(...[hintBtn, h('button', { class: 'big', onclick: () => reveal(card) }, 'Visa svar')].filter(Boolean));
      autoRead(spoken(), deckLang);
      return;
    }
    // Flerval och lucktext: alternativen i slumpad ordning
    card.choices = [...card.wrongOptions, card.back].map(v => [Math.random(), v]).sort((a, b) => a[0] - b[0]).map(x => x[1]);
    if (card.kind === 'CLOZE') {
      const [a, b] = card.front.split('___');
      front.replaceChildren(a, h('span', { class: 'cloze-gap' }, '      '), b || '');
    } else {
      front.textContent = card.front;
    }
    const buttons = card.choices.map((c, i) => h('button', { class: 'choice', onclick: () => pick(card, c, buttons) },
      h('span', { class: 'choice-letter' }, 'ABCD'[i]), h('span', {}, c)));
    controls.replaceChildren(...[h('div', { class: 'choices' }, buttons), hintBtn].filter(Boolean));
    autoRead(spoken(), deckLang);
  }

  async function pick(card, value, buttons) {
    if (readingBtn) stopReading();
    const correct = norm(value) === norm(card.back);
    buttons.forEach(b => {
      b.disabled = true;
      const v = b.lastChild.textContent;
      if (norm(v) === norm(card.back)) b.classList.add('right');
      else if (v === value) b.classList.add('wrong');
    });
    if (card.kind === 'CLOZE') front.querySelector('.cloze-gap').textContent = card.back;
    if (!correct && card.kind !== 'CLOZE') back.hidden = false;
    await send(card, correct);
    controls.append(h('button', { class: 'big next-btn', onclick: next }, correct ? 'Rätt! Nästa →' : 'Nästa →'));
  }

  function reveal(card) {
    if (readingBtn) stopReading();
    back.hidden = false;
    controls.replaceChildren(
      h('button', { class: 'fc-wrong', onclick: async () => { await send(card, false); next(); } }, '✗ Fel'),
      h('button', { class: 'fc-right', onclick: async () => { await send(card, true); next(); } }, '✓ Rätt'));
  }

  async function send(card, correct) {
    try {
      await api(`/api/flashcards/cards/${card.id}/grade?deviceId=${enc(S.deviceId)}&quiet=true`, { method: 'POST', body: { correct } });
    } catch (e) { toast(e.message, true); return; }
    score.done++; if (correct) score.right++;
    status.textContent = `${score.right} rätt av ${score.done}`;
    progress();
    refreshPoints();
  }
}

views.poang = async () => {
  const [w, events] = await Promise.all([
    api(`/api/devices/${enc(S.deviceId)}/rewards`),
    api(`/api/devices/${enc(S.deviceId)}/rewards/events`).catch(() => []),
  ]);
  setPoints(w.weekPoints);
  const kr = (n) => { const v = Number(n || 0); return `${v.toLocaleString('sv-SE', { minimumFractionDigits: Number.isInteger(v) ? 0 : 2, maximumFractionDigits: 2 })} kr`; };
  const page = h('div', { class: 'page' });

  const card = h('div', { class: 'score' },
    h('div', { class: 'big-num' }, w.weekPoints),
    h('div', { class: 'unit' }, 'poäng den här veckan'));
  if (w.moneyEnabled && Number(w.sekPerPoint) > 0) {
    const capPoints = Number(w.weeklyCapSek) / Number(w.sekPerPoint);
    const pct = capPoints > 0 ? Math.min(100, Math.round(100 * w.weekPoints / capPoints)) : 0;
    card.append(h('div', { class: 'money' }, `= ${kr(w.earnedSek)}`),
      h('div', { class: 'track' }, h('div', { class: 'fill', style: `width:${pct}%` })),
      h('div', { class: 'small' }, pct >= 100 ? `Max ${kr(w.weeklyCapSek)} nått den här veckan! 🎉`
        : `${Math.max(0, Math.ceil(capPoints - w.weekPoints))} poäng kvar till max ${kr(w.weeklyCapSek)}`));
  }
  page.append(card);

  page.append(h('div', { class: 'stats' },
    h('div', { class: 'stat' }, h('b', {}, `${w.streakDays > 0 ? '🔥 ' : ''}${w.streakDays}`), h('span', {}, w.streakDays === 1 ? 'dag i rad' : 'dagar i rad')),
    h('div', { class: 'stat' }, h('b', {}, w.allTimePoints), h('span', {}, 'poäng totalt')),
    w.moneyEnabled
      ? h('div', { class: 'stat' }, h('b', {}, kr(w.unpaidSek)), h('span', {}, 'kvar att få'))
      : h('div', { class: 'stat' }, h('b', {}, events.filter(e => e.awardedOn === todayIso()).reduce((s, e) => s + e.points, 0)), h('span', {}, 'poäng idag'))));

  // Senaste – grupperat per dag
  page.append(h('h3', {}, 'Senaste'));
  if (!events.length) {
    page.append(h('div', { class: 'box' }, h('div', { class: 'empty' }, 'Inga poäng än – tryck ▶ Börja plugga i chatten så kör vi!')));
  } else {
    const byDay = new Map();
    for (const e of events.slice(0, 40)) {
      const d = e.awardedOn || (e.createdAt || '').slice(0, 10);
      if (!byDay.has(d)) byDay.set(d, []);
      byDay.get(d).push(e);
    }
    for (const [day, list] of byDay) {
      const sum = list.reduce((s, e) => s + e.points, 0);
      page.append(h('div', { style: 'display:flex;justify-content:space-between;margin:14px 2px 6px;color:var(--muted);font-size:14px;font-weight:600' },
        h('span', { style: 'text-transform:capitalize' }, dayName(day)), h('span', {}, `+${sum}`)),
        h('div', { class: 'box' }, list.map(e => h('div', { class: 'row' },
          h('div', { class: 'grow' }, e.reason || kindLabel(e.kind)),
          h('div', { class: 'plus' }, `+${e.points}`)))));
    }
  }

  // Så får man poäng
  page.append(h('h3', {}, 'Så får du poäng'), h('div', { class: 'box rules' },
    rule('⏱ Varje minut du pluggar', '+1'),
    rule('✅ Höll hela passet', '+5'),
    rule('🗓 Bockat av i planen', '+3'),
    rule('📚 Läxa klar', '+10'),
    rule('🃏 Försök på ett flashkort', '+1'),
    rule('🎯 Rätt svar på kortet', '+2'),
    rule('🔥 Dagar i rad', '+3 per dag'),
    h('div', { class: 'row', style: 'color:var(--muted);font-size:13px' }, 'Poäng dras aldrig av. Det är att sätta sig och försöka som räknas mest.')));
  return page;

  function rule(text, pts) { return h('div', { class: 'row' }, h('div', { class: 'grow' }, text), h('div', { class: 'plus' }, pts)); }
};

function kindLabel(kind) {
  return ({ CARD_ATTEMPT: 'Flashkort', CARD_CORRECT: 'Rätt på flashkort', SESSION_MINUTES: 'Pluggminuter', SESSION_COMPLETED: 'Höll hela passet',
    HOMEWORK_DONE: 'Läxa klar', PLAN_ITEM_DONE: 'Bockat av i planen', STREAK: 'Dagar i rad', BONUS: 'Bonus' })[kind] || 'Poäng';
}

/** En läxa/ett prov: vad som ingår och materialet (sidor med bild, text och uppläsning). */
async function homeworkDetail(id) {
  const [all, material] = await Promise.all([api('/api/homework?openOnly=false'), api(`/api/homework/${id}/material`)]);
  const x = all.find(y => y.id === id);
  const page = h('div', { class: 'page' },
    h('button', { class: 'small-btn', onclick: () => { S.openHomework = null; stopReading(); show('laxor'); } }, '‹ Tillbaka'));
  if (!x) { page.append(h('div', { class: 'empty' }, 'Hittade inte läxan.')); return page; }
  page.append(h('h2', { style: 'margin-top:12px' }, h('span', { class: 'tag' + (x.kind === 'TEST' ? ' test' : '') }, x.kind === 'TEST' ? 'Prov' : 'Läxa'), ' ', x.title),
    h('div', { class: 'meta', style: 'color:var(--muted)' }, [x.subject, rel(x.dueDate)].filter(Boolean).join(' · ')));
  const listen = listenPlayer(x, material);
  if (listen) page.append(listen.el);
  try {
    const prog = (await api('/api/plan/progress')).find(p => p.homeworkId === x.id);
    if (prog) page.append(h('div', { style: 'margin-top:10px' }, progressCard(prog)));
  } catch { /* inget framsteg */ }
  if (x.description) page.append(h('p', { style: 'white-space:pre-wrap' }, x.description));
  if ((x.steps || []).length) page.append(h('h3', {}, 'Det här ingår'), h('div', { class: 'box' }, x.steps.map(st => h('div', { class: 'row' }, st))));

  page.append(h('h3', { style: 'margin-top:18px' }, `Material (${material.length})`));
  if (!material.length) {
    page.append(h('div', { class: 'box' }, h('div', { class: 'empty' }, 'Inget material än. Fota sidorna i boken så vet Pluggis vad som ingår.')));
  } else {
    page.append(h('div', { class: 'mats' }, material.map(m => {
      const img = m.hasImage ? h('img', { class: 'mat-img', alt: m.filename || 'sida', loading: 'lazy' }) : null;
      if (img) authImage(`/api/homework/material/${m.id}/image`).then(u => { img.src = u; img.onclick = () => zoom(u); }).catch(() => img.remove());
      const text = h('div', { class: 'mat-text', hidden: true }, m.text || '');
      return h('div', { class: 'mat' }, img,
        h('div', { class: 'mat-body' },
          h('div', { class: 'mat-title' }, m.page ? `Sida ${m.page}` : (m.filename || 'Fil')),
          m.summary ? h('div', { class: 'meta' }, m.summary) : null,
          listen && listen.has(m.id) ? h('div', { class: 'meta listen-mark', 'data-mat': m.id }, listen.mark(m.id)) : null,
          m.text ? h('div', { class: 'mat-actions' },
            listen && listen.has(m.id) ? h('button', { class: 'small-btn', onclick: () => { listen.jump(m.id); window.scrollTo({ top: 0, behavior: 'smooth' }); } }, '🎧 Lyssna härifrån') : null,
            h('button', { class: 'small-btn', onclick: (e) => { text.hidden = !text.hidden; e.target.textContent = text.hidden ? 'Visa text' : 'Dölj text'; } }, 'Visa text')) : null,
          text));
    })));
  }
  page.append(h('button', { class: 'big', style: 'margin-top:16px', onclick: () => openUpload(x.id) }, '📷 Lägg till sidor eller filer'));
  if (x.kind !== 'OTHER') {
    page.append(h('h3', {}, 'Öva'), focusRow(() => x.id).el, h('div', { class: 'quiz-btns six' },
      h('button', { class: 'quiz-btn', onclick: (e) => makeQuiz(x.id, 'quiz', e.target) }, '⚡', h('span', {}, 'Snabbquiz'), h('small', {}, 'fyra alternativ')),
      h('button', { class: 'quiz-btn', onclick: (e) => makeQuiz(x.id, 'cloze', e.target) }, '✏️', h('span', {}, 'Lucktext'), h('small', {}, 'fyll i ordet')),
      h('button', { class: 'quiz-btn', onclick: (e) => explainFor(x.id, e.target) }, '🧠', h('span', {}, 'Förklara'), h('small', {}, 'lär ut till Pluggis')),
      h('button', { class: 'quiz-btn', onclick: (e) => startExam(x.id, e.target) }, '📝', h('span', {}, 'Testprov'), h('small', {}, 'som ett riktigt prov')),
      h('button', { class: 'quiz-btn', onclick: (e) => startMatch(x.id, e.target) }, '🧩', h('span', {}, 'Matcha par'), h('small', {}, 'slå ditt rekord')),
      h('button', { class: 'quiz-btn', onclick: (e) => startOrder(x.id, e.target) }, '🔢', h('span', {}, 'Rätt ordning'), h('small', {}, 'steg och händelser'))));
  }
  return page;
}

function zoom(url) {
  const sheet = $('#sheet');
  sheet.replaceChildren(h('img', { src: url, class: 'zoomed', alt: 'sida' }));
  sheet.onclick = () => { sheet.hidden = true; sheet.replaceChildren(); };
  sheet.hidden = false;
}

// ---- Läs upp material ----
let readingBtn = null;
function stopReading() {
  speechRun++;
  stopServerAudio();
  if (window.speechSynthesis) speechSynthesis.cancel();
  if (readingBtn) { readingBtn.textContent = readingBtn.dataset.label || '🔊 Läs upp'; readingBtn = null; }
}
/**
 * Läs upp en fråga automatiskt när den visas (eleven har dyslexi) – om inte rösten är avstängd under 🔊.
 * text kan vara en lista (fråga + alternativ) så att varje del får rätt röst.
 */
function autoRead(text, lang = '') {
  if (S.voice === 'av' || !text || (Array.isArray(text) && !text.length)) return;
  const b = h('button');   // osynlig – readAloud vill ha en knapp att visa "Sluta" på
  setTimeout(() => readAloud(text, b, lang), 150);
}

async function readAloud(text, btn, lang = '') {
  if (readingBtn === btn) { stopReading(); return; }   // tryck igen = sluta
  stopReading();
  readingBtn = btn;
  btn.dataset.label = btn.textContent;
  btn.textContent = '⏹ Sluta';
  // Pluggis röst om den finns (Azure/Piper), annars telefonens
  const usePluggis = S.serverVoice && S.voice !== 'telefon';
  try { if (navigator.audioSession) navigator.audioSession.type = 'playback'; } catch { /* */ }
  if (usePluggis) {
    if (!playerUnlocked) { try { player.src = SILENCE; player.play().then(() => { playerUnlocked = true; }).catch(() => {}); } catch { /* */ } }
    const run = speechRun + 1;
    await speakServer(text, lang);
    if (speechRun === run && readingBtn === btn) { btn.textContent = btn.dataset.label; readingBtn = null; }
  } else if (window.speechSynthesis) {
    const parts = Array.isArray(text) ? text : [text];
    parts.forEach((part, i) => {
      const u = new SpeechSynthesisUtterance(forSpeech(part));
      const l = voiceLang(part, lang);
      if (l) {   // telefonens röst för språket (iPhone/Android har spanska, tyska, franska …)
        u.lang = PHONE_LANG[l];
        const v = speechSynthesis.getVoices().find(x => x.lang && x.lang.toLowerCase().startsWith(l));
        if (v) u.voice = v;
      } else {
        u.lang = 'sv-SE'; const v = svVoice(); if (v) u.voice = v;
      }
      if (i === parts.length - 1) u.onend = () => { if (readingBtn === btn) { btn.textContent = btn.dataset.label; readingBtn = null; } };
      speechSynthesis.speak(u);
    });
  }
}

// ---- Lyssna på materialet (som en ljudbok) ----
/**
 * Läser upp allt inläst material i en läxa, sida för sida och mening för mening. Var eleven är sparas på
 * läxan (servern), så det går att fortsätta senare – även på en annan enhet. Sidor som lyssnats klart får ✓.
 */
const Listener = { active: null };

function listenPlayer(x, material) {
  const chapters = material.filter(m => (m.text || '').trim())
    .sort((a, b) => (a.page ?? 1e9) - (b.page ?? 1e9) || a.id - b.id)
    .map((m, i) => ({ id: m.id, label: m.page ? `Sida ${m.page}` : (m.filename || `Del ${i + 1}`), sents: sentences(m.text) }))
    .filter(c => c.sents.length);
  if (!chapters.length) return null;
  const listened = new Set(x.listenedIds || []);
  const hwLang = langOf(x.subject, x.title);   // språkläxa: utländska meningar läses med språkets röst
  let ci = Math.max(0, chapters.findIndex(c => c.id === x.listenMaterialId));
  let si = chapters[ci].id === x.listenMaterialId ? (x.listenSentence || 0) : 0;
  if (si >= chapters[ci].sents.length) si = 0;
  let playing = false, run = 0, sinceSave = 0, finished = false;
  const total = chapters.reduce((n, c) => n + c.sents.length, 0);
  const posOf = () => chapters.slice(0, ci).reduce((n, c) => n + c.sents.length, 0) + si;
  const resumable = ci > 0 || si > 0;

  const title = h('div', { class: 'listen-title' });
  const now = h('div', { class: 'listen-now' });
  const bar = h('div', { class: 'quiz-progress' }, h('div'));
  const playBtn = h('button', { class: 'listen-play', 'aria-label': 'Spela' }, '▶');
  const back = h('button', { class: 'listen-btn', title: 'Förra meningen', 'aria-label': 'Förra meningen' }, '⏮');
  const fwd = h('button', { class: 'listen-btn', title: 'Nästa sida', 'aria-label': 'Nästa sida' }, '⏭');
  const hint = h('div', { class: 'meta listen-hint' });
  const el = h('div', { class: 'box listen' },
    h('div', { class: 'listen-head' }, h('span', { class: 'listen-ico' }, '🎧'), h('div', { class: 'grow' }, title, hint)),
    bar, now, h('div', { class: 'listen-ctrl' }, back, playBtn, fwd));

  function paint() {
    const c = chapters[ci];
    title.textContent = finished ? 'Allt uppläst ✓' : `${c.label} · ${ci + 1} av ${chapters.length}`;
    bar.firstChild.style.width = `${Math.round(100 * (finished ? total : posOf()) / total)}%`;
    playBtn.textContent = playing ? '⏸' : '▶';
    playBtn.setAttribute('aria-label', playing ? 'Pausa' : 'Spela');
    now.textContent = finished ? '' : c.sents[si] || '';
    now.hidden = !playing && !resumable && posOf() === 0;
    hint.textContent = playing ? 'Lyssnar …'
      : finished ? 'Tryck ▶ för att lyssna igen från början.'
      : posOf() > 0 ? `Fortsätt där du slutade – ${Math.round(100 * posOf() / total)} % lyssnat`
      : `Lyssna på allt material: ${chapters.length} ${chapters.length === 1 ? 'del' : 'sidor'}, ca ${Math.max(1, Math.round(total * 6 / 60))} min`;
    document.querySelectorAll('.listen-mark').forEach(m => {
      const id = Number(m.dataset.mat);
      m.textContent = mark(id);
      m.classList.toggle('on', id === chapters[ci].id && (playing || posOf() > 0) && !finished);
    });
  }
  function mark(id) {
    if (!finished && id === chapters[ci].id && (playing || posOf() > 0)) return playing ? '🎧 Lyssnar nu' : '🎧 Du är här';
    return listened.has(id) ? '✓ Lyssnat' : '';
  }
  async function save() {
    sinceSave = 0;
    try {
      await api(`/api/homework/${x.id}/listen`, { method: 'PUT',
        body: { materialId: finished ? null : chapters[ci].id, sentence: finished ? 0 : si, listened: [...listened] } });
    } catch { /* sparas nästa gång */ }
  }
  /** En mening: Pluggis röst om den finns, annars telefonens. Löser ut när den är klar. */
  let prefetch = null;
  async function sayOne(text, myRun, nextText) {
    const usePluggis = S.serverVoice && S.voice !== 'telefon';
    if (usePluggis) {
      try {
        const buf = await (prefetch && prefetch.text === text ? prefetch.p : fetchVoice(text, voiceLang(text, hwLang)));
        prefetch = nextText ? { text: nextText, p: fetchVoice(nextText, voiceLang(nextText, hwLang)).catch(() => null) } : null;
        if (myRun !== run || !buf) return !!buf;
        const ok = await playBuffer(buf);
        if (ok || playerUnlocked) return true;
      } catch { /* telefonens röst i stället */ }
    }
    if (!window.speechSynthesis || myRun !== run) return false;
    return new Promise(resolve => {
      const u = new SpeechSynthesisUtterance(forSpeech(text));
      const l = voiceLang(text, hwLang);
      if (l) { u.lang = PHONE_LANG[l]; const v = speechSynthesis.getVoices().find(x => x.lang && x.lang.toLowerCase().startsWith(l)); if (v) u.voice = v; }
      else { u.lang = 'sv-SE'; const v = svVoice(); if (v) u.voice = v; }
      u.onend = () => resolve(true); u.onerror = () => resolve(false);
      speechSynthesis.speak(u);
    });
  }
  async function play() {
    if (finished) { finished = false; ci = 0; si = 0; }
    stopReading();
    if (Listener.active && Listener.active !== api_) Listener.active.pause();
    Listener.active = api_;
    playing = true;
    const myRun = ++run;
    try { if (navigator.audioSession) navigator.audioSession.type = 'playback'; } catch { /* */ }
    if (!playerUnlocked) { try { player.src = SILENCE; player.play().then(() => { playerUnlocked = true; }).catch(() => {}); } catch { /* */ } }
    Practice.begin(`Lyssnar: ${x.title}`, x.id, () => playing);
    paint();
    while (playing && myRun === run) {
      const c = chapters[ci];
      const nextText = si + 1 < c.sents.length ? c.sents[si + 1] : (chapters[ci + 1] ? chapters[ci + 1].sents[0] : null);
      paint();
      const ok = await sayOne(c.sents[si], myRun, nextText);
      if (myRun !== run) return;
      if (!ok) { toast('Uppläsningen stannade – tryck ▶ igen', true); pause(); return; }
      si++;
      if (si >= c.sents.length) {
        listened.add(c.id);
        if (ci + 1 >= chapters.length) { finished = true; playing = false; await save(); paint(); toast('Allt material uppläst! 🎧'); return; }
        ci++; si = 0;
        await save();
      } else if (++sinceSave >= 4) {
        save();
      }
    }
  }
  function pause() {
    if (!playing) return;
    playing = false; run++;
    stopServerAudio();
    try { if (window.speechSynthesis) speechSynthesis.cancel(); } catch { /* */ }
    save(); paint();
  }
  const api_ = {
    el, pause,
    has: (id) => chapters.some(c => c.id === id),
    mark,
    jump(id) { const i = chapters.findIndex(c => c.id === id); if (i < 0) return; pause(); finished = false; ci = i; si = 0; play(); },
  };
  playBtn.addEventListener('click', () => (playing ? pause() : play()));
  back.addEventListener('click', () => {
    const was = playing; pause();
    if (si > 0) si--; else if (ci > 0) { ci--; si = chapters[ci].sents.length - 1; }
    finished = false; paint(); if (was) play();
  });
  fwd.addEventListener('click', () => {
    const was = playing; pause();
    if (ci + 1 < chapters.length) { ci++; si = 0; finished = false; }
    paint(); save(); if (was) play();
  });
  setTimeout(paint, 0);
  return api_;
}

// ---- Uppladdning ----
async function openUpload(homeworkId) {
  const picker = h('input', { type: 'file', accept: FILE_ACCEPT, multiple: true, style: 'display:none' });
  document.body.append(picker);
  picker.addEventListener('change', async () => {
    let files = [...picker.files];
    picker.remove();
    if (!files.length) return;
    if (files.length > MAX_FILES) { toast(`Högst ${MAX_FILES} åt gången – tar de första ${MAX_FILES}`, true); files = files.slice(0, MAX_FILES); }
    if (!S.homework) { try { S.homework = (await api('/api/homework')).filter(x => x.kind !== 'OTHER'); } catch { S.homework = []; } }
    showSheet(files, homeworkId);
  });
  picker.click();
}

/** Små förhandsbilder med ✕, och en knapp för att lägga till fler (t.ex. ett foto i taget med kameran). */
function fileStrip(files, onChange) {
  const strip = h('div', { class: 'file-strip' });
  const draw = () => {
    strip.replaceChildren(...files.map((f, i) => {
      const thumb = f.type && f.type.startsWith('image/')
        ? h('img', { src: URL.createObjectURL(f), alt: '' })
        : h('span', { class: 'file-doc' }, (f.name.split('.').pop() || 'fil').toUpperCase());
      return h('div', { class: 'file-thumb' }, thumb, h('span', { class: 'file-n' }, String(i + 1)),
        h('button', { class: 'file-x', 'aria-label': 'Ta bort', onclick: (e) => { e.preventDefault(); files.splice(i, 1); draw(); onChange(); } }, '✕'));
    }),
    files.length < MAX_FILES ? h('button', { class: 'file-add', onclick: (e) => {
      e.preventDefault();
      const more = h('input', { type: 'file', accept: FILE_ACCEPT, multiple: true, style: 'display:none' });
      document.body.append(more);
      more.addEventListener('change', () => {
        const add = [...more.files].slice(0, MAX_FILES - files.length);
        more.remove();
        if ([...more.files].length > add.length) toast(`Högst ${MAX_FILES} sidor åt gången`, true);
        files.push(...add); draw(); onChange();
      });
      more.click();
    } }, '➕', h('small', {}, 'Fler sidor')) : null);
  };
  draw();
  return strip;
}

function showSheet(files, homeworkId) {
  const sheet = $('#sheet');
  const title = h('h2', {});
  const setTitle = () => { title.textContent = `📎 ${files.length === 1 ? files[0].name : `${files.length} sidor`}`; go.disabled = !files.length; };
  const target = h('select', {}, h('option', { value: '' }, 'En ny läxa eller ett nytt prov'),
    (S.homework || []).filter(x => x.status === 'OPEN' && !(x.dueDate && daysUntil(x.dueDate) < 0)).map(x =>
      h('option', { value: x.id, selected: String(x.id) === String(homeworkId) || null },
        `${x.kind === 'TEST' ? 'Prov' : 'Läxa'}: ${x.title}${x.dueDate ? ` (${rel(x.dueDate)})` : ''}`)));
  const cards = h('input', { type: 'checkbox' });
  const cardsRow = h('label', { class: 'check', style: homeworkId ? '' : 'display:none' }, cards, 'Gör flashkort av det också');
  target.addEventListener('change', () => { cardsRow.style.display = target.value ? '' : 'none'; });
  const go = h('button', { class: 'go' }, 'Skicka');
  const close = () => { sheet.hidden = true; sheet.replaceChildren(); };
  const strip = fileStrip(files, setTitle);
  setTitle();
  go.addEventListener('click', async () => {
    const label = files.length === 1 ? files[0].name : `${files.length} sidor`;
    go.disabled = true; go.textContent = files.length > 1 ? `Pluggis läser ${files.length} sidor…` : 'Pluggis läser…';
    const fd = new FormData();
    for (const f of files) { const small = await shrinkImage(f); fd.append('file', small, small.name || f.name); }
    const q = new URLSearchParams({ deviceId: S.deviceId });
    if (target.value) { q.set('homeworkId', target.value); q.set('flashcards', String(cards.checked)); }
    try {
      const r = await api(`/api/homework/upload?${q}`, { method: 'POST', form: fd });
      close();
      if (target.value) {
        const parts = [r.sections && r.sections.length ? `${r.sections.length} avsnitt inlagda` : 'Materialet sparat',
          r.replanned ? 'ny plan klar' : null, r.deckId ? `${r.cardCount} flashkort` : null].filter(Boolean);
        toast(parts.join(' · '));
        addMsg('note', `📎 ${label}: ${parts.join(' · ')}`);
      } else {
        const n = (r.saved || []).length;
        toast(n ? `La till ${n} st` : (r.note || 'Hittade inga läxor i filen'));
        addMsg('note', `📷 ${n ? `La till: ${(r.saved || []).map(x => x.title).join(', ')}` : (r.note || 'Hittade inga läxor')}`);
      }
      S.homework = null;
      if (target.value) S.openHomework = Number(target.value);
      if (S.tab !== 'chatt') show(S.tab);
    } catch (e) {
      go.disabled = false; go.textContent = 'Skicka';
      toast(e.message, true);
    }
  });
  sheet.replaceChildren(h('div', { class: 'panel', onclick: (e) => e.stopPropagation() },
    title, strip,
    h('p', { class: 'hint' }, 'Fota en sida i taget och tryck ➕ för nästa – eller välj flera i Bilder på en gång. Alla sidor läses tillsammans.'),
    h('label', {}, 'Vad är det?'), target, cardsRow,
    h('div', { class: 'actions' }, h('button', { onclick: close }, 'Avbryt'), go)));
  sheet.onclick = close;
  sheet.hidden = false;
}

// ---- Poäng ----
/** Poäng kan vara avstängt för eleven (föräldern väljer i admin) – då syns de inte alls här. */
function paintPoints() {
  $('#points').hidden = !S.points;
  const tab = document.querySelector('#tabbar [data-tab=poang]');
  if (tab) tab.hidden = !S.points;
  if (!S.points && S.tab === 'poang') show('chatt');
}
const pts = (text) => S.points === false ? '' : text;
function setPoints(n) { if (n !== undefined && n !== null) $('#points').textContent = `${n} p`; }
async function refreshPoints() {
  if (S.points === false) return;
  try { setPoints((await api(`/api/devices/${enc(S.deviceId)}/rewards`)).weekPoints); } catch { /* inte viktigt */ }
}

// ---- Flikar ----
async function show(tab) {
  if (Listener.active) Listener.active.pause();
  Practice.end();
  if (tab !== 'laxor') { S.openHomework = null; }
  if (typeof stopReading === 'function' && readingBtn) stopReading();
  S.tab = tab;
  document.querySelectorAll('#tabbar button').forEach(b => b.classList.toggle('on', b.dataset.tab === tab));
  const view = $('#view');
  view.replaceChildren(h('div', { class: 'empty' }, '…'));
  try {
    const el = await views[tab]();
    if (S.tab === tab) view.replaceChildren(el);
  } catch (e) {
    view.replaceChildren(h('div', { class: 'page' }, h('div', { class: 'empty' }, e.message)));
  }
}
document.querySelectorAll('#tabbar button').forEach(b => b.addEventListener('click', () => show(b.dataset.tab)));
// Logga ut (t.ex. byta elev på en delad telefon): tryck på Pluggis-namnet
document.querySelector('.title').addEventListener('click', () => {
  if (!confirm(`Logga ut${S.name ? ' ' + S.name : ''} från Pluggis på den här telefonen?`)) return;
  store.set('pluggis.chat.token', ''); store.set('pluggis.chat.history', '[]');
  S.token = ''; S.history = [];
  if (S.ws) { try { S.ws.close(); } catch { /* */ } S.ws = null; }
  askForLink();
});
$('#points').addEventListener('click', () => show('poang'));

// ---- Start ----
function askForLink() {
  const input = h('input', { class: 'code-input', placeholder: 'ABC12', maxlength: '12', autocapitalize: 'characters',
    autocorrect: 'off', autocomplete: 'one-time-code', spellcheck: 'false', inputmode: 'text' });
  const go = () => {
    const v = input.value.trim(); const m = v.match(/[#&][kt]=([^&\s]+)/);
    const code = m ? decodeURIComponent(m[1]) : v.replace(/[\s-]/g, '').toUpperCase();
    if (!code) return;
    if (code !== S.token) store.set('pluggis.chat.history', '[]');
    S.token = code; S.history = JSON.parse(store.get('pluggis.chat.history', '[]'));
    store.set('pluggis.chat.token', S.token);
    start();
  };
  input.addEventListener('keydown', (e) => { if (e.key === 'Enter') go(); });
  $('#view').replaceChildren(h('div', { class: 'login' },
    h('h2', {}, 'Hej! 👋'), h('p', {}, 'Skriv in din kod till Pluggis.'), input,
    h('button', { class: 'big', onclick: go }, 'Logga in'),
    h('p', { style: 'color:var(--muted);font-size:14px' }, 'Koden har 5 tecken och finns hos den som satt upp Pluggis.')));
  setTimeout(() => input.focus(), 50);
}

async function start() {
  try {
    const me = await api('/api/chat/me');
    S.deviceId = me.deviceId || S.deviceId;
    S.name = me.name || '';
    S.serverVoice = !!me.serverVoice;
    S.points = me.pointsEnabled !== false;
    S.version = me.version || null;
    paintPoints();
    if (S.voice === 'pluggis' && !S.serverVoice) S.voice = 'telefon';
    store.set('pluggis.chat.device', S.deviceId);
  } catch (e) {
    if (e.auth) { askForLink(); if (S.token) toast('Koden fungerar inte – kolla att den är rätt', true); return; }
  }
  paintSpeak();
  connect();
  refreshPoints();
  show('chatt');
}
// Varje tryck håller ljudet upplåst (iOS stänger av det när appen legat i bakgrunden)
document.addEventListener('touchend', () => { if (S.voice !== 'av') unlockAudio(); }, { passive: true });
document.addEventListener('click', () => { if (S.voice !== 'av') unlockAudio(); });
start();
