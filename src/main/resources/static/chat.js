'use strict';
// Pluggis – elevchatten (mobil först). Pratar med coachen via samma WebSocket som Tab5,
// visar dagens plan och läxorna, och laddar upp material (foto, skärmdump, Word, PDF).

const $ = (s) => document.querySelector(s);
const enc = encodeURIComponent;
const FILE_ACCEPT = 'image/*,.pdf,.docx,.txt,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document';
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
  const m = location.hash.match(/[#&]t=([^&]+)/);
  if (m) {
    store.set('pluggis.chat.token', decodeURIComponent(m[1]));
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
  if (res.status === 401 || res.status === 403) throw Object.assign(new Error('Länken fungerar inte längre – be om en ny'), { auth: true });
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

function speakPhone(text) {
  if (!window.speechSynthesis || !text) return;
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
async function fetchVoice(text) {
  const res = await fetch('/api/speech/preview', { method: 'POST',
    headers: { 'X-Pluggis-Token': S.token, 'Content-Type': 'application/json' }, body: JSON.stringify({ text }) });
  if (!res.ok) throw new Error(String(res.status));
  return res.arrayBuffer();
}
async function speakServer(text) {
  const run = ++speechRun;
  stopServerAudio();
  const list = sentences(text);
  let next = list.length ? fetchVoice(list[0]) : null;
  for (let i = 0; i < list.length; i++) {
    let buf;
    try { buf = await next; } catch { if (run === speechRun) toast('Kunde inte hämta Pluggis röst', true); return; }
    if (run !== speechRun) return;               // ett nytt svar har kommit – sluta läsa det gamla
    next = i + 1 < list.length ? fetchVoice(list[i + 1]) : null;
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
function connect() {
  if (S.ws && S.ws.readyState <= 1) return;
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  const ws = new WebSocket(`${proto}://${location.host}/ws/device?deviceId=${enc(S.deviceId)}&token=${enc(S.token)}`);
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
    if (e.code === 1008) { addMsg('err', 'Länken fungerar inte – be om en ny elevlänk.'); return; }
    const wait = Math.min(15000, 1000 * 2 ** S.retry++);
    setTimeout(() => { if (document.visibilityState === 'visible') connect(); }, wait);
  };
  ws.onmessage = (e) => {
    if (typeof e.data !== 'string') return;
    let m; try { m = JSON.parse(e.data); } catch { return; }
    onServer(m);
  };
}
document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible') { checkAlive(); refreshPoints(); } });
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
    case 'say': setThinking(false); addMsg('bot', m.text); speak(m.text); break;
    case 'heard': addMsg('me', m.text); break;
    case 'reminder': addMsg('bot reminder', '🔔 ' + m.text); speak(m.text); break;
    case 'error': setThinking(false); addMsg('err', m.message || m.text || 'Något gick fel'); break;
    case 'points': toast(`+${m.delta} poäng${m.reason ? ' – ' + m.reason : ''}`); setPoints(m.weekTotal); break;
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

// ---- Vyer ----
const views = {};

views.chatt = () => {
  const input = h('textarea', { rows: 1, placeholder: S.name ? `Skriv till Pluggis, ${S.name}…` : 'Skriv till Pluggis…', enterkeyhint: 'send',
    oninput: () => { input.style.height = 'auto'; input.style.height = Math.min(120, input.scrollHeight) + 'px'; },
    onkeydown: (e) => { if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) { e.preventDefault(); sendText(); } } });
  const Recognition = window.SpeechRecognition || window.webkitSpeechRecognition;
  // Webbläsarens taligenkänning kräver https. Över vanlig http (hemma på wifi) funkar ändå
  // tangentbordets egen mikrofon på iPhone – den visar vi en ledtråd om i stället.
  const mic = Recognition && window.isSecureContext ? h('button', { class: 'round', title: 'Prata', onclick: listen }, '🎤') : null;
  if (!mic && /iPhone|iPad/.test(navigator.userAgent)) input.placeholder = 'Skriv – eller tryck 🎤 på tangentbordet';

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
    rec.onend = () => { mic.classList.remove('rec'); mic.textContent = '🎤'; };
    rec.// Varje tryck håller ljudet upplåst (iOS stänger av det när appen legat i bakgrunden)
document.addEventListener('touchend', () => { if (S.voice !== 'av') unlockAudio(); }, { passive: true });
document.addEventListener('click', () => { if (S.voice !== 'av') unlockAudio(); });
start();
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
  return wrap;

  function addCardTo(list, c) {
    const cls = 'card' + (c.result === true ? ' right' : c.result === false ? ' wrong' : '');
    list.append(h('div', { class: cls }, c.front, c.result === undefined ? null
      : h('small', {}, `${c.result ? 'Rätt!' : 'Inte riktigt'}${c.box ? ` · fack ${c.box}` : ''}`)));
  }
};

views.plan = async () => {
  const page = h('div', { class: 'page' }, h('h2', {}, 'Planen'));
  const [today, upcoming] = await Promise.all([api('/api/plan/today'), api('/api/plan/upcoming?days=14')]);
  page.append(h('h3', {}, 'Idag'), today.length
    ? h('div', { class: 'box' }, today.map(t => planRow(t.item, t.homeworkTitle, t.overdue)))
    : h('div', { class: 'box' }, h('div', { class: 'empty' }, 'Inget planerat idag 🎉')));

  const todayStr = todayIso();
  const later = new Map();
  for (const i of upcoming) {
    if (i.planDate <= todayStr) continue;
    if (!later.has(i.planDate)) later.set(i.planDate, []);
    later.get(i.planDate).push(i);
  }
  for (const [day, items] of later) {
    page.append(h('h3', {}, dayName(day)), h('div', { class: 'box' }, items.map(i => planRow(i, null, false))));
  }
  if (!later.size) page.append(h('p', { class: 'empty' }, 'Inget mer planerat de kommande två veckorna.'));
  return page;

  function planRow(item, homeworkTitle, overdue) {
    const tick = h('button', { class: 'tick', 'aria-label': 'Klar', disabled: item.done || null }, item.done ? '✓' : '');
    const row = h('div', { class: 'row' + (item.done ? ' done' : '') }, tick,
      h('div', { class: 'grow' }, h('div', { class: 'task' }, item.task),
        h('div', { class: 'meta' }, `${item.minutes} min`, homeworkTitle ? ` · ${homeworkTitle}` : '',
          overdue ? ' · ' : '', overdue ? h('span', { class: 'tag late' }, 'ligger efter') : '')));
    tick.addEventListener('click', async () => {
      if (item.done) return;
      try {
        await api(`/api/plan/items/${item.id}/done?deviceId=${enc(S.deviceId)}`, { method: 'POST' });
        item.done = true; row.classList.add('done'); tick.textContent = '✓'; tick.disabled = true;
        toast('Bra jobbat! +3 poäng'); refreshPoints();
      } catch (e) { toast(e.message, true); }
    });
    return row;
  }
};

views.laxor = async () => {
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
    return h('div', { class: 'row' },
      h('div', { class: 'grow' },
        h('div', {}, h('span', { class: 'tag' + (x.kind === 'TEST' ? ' test' : '') }, x.kind === 'TEST' ? 'Prov' : 'Läxa'), ' ', x.title),
        h('div', { class: 'meta' }, [x.subject, rel(x.dueDate)].filter(Boolean).join(' · '),
          missing ? ' · ' : '', missing ? h('span', { class: 'tag late' }, 'vad ingår?') : '')),
      isPast ? null : h('button', { class: 'small-btn', onclick: () => openUpload(x.id) }, '📎 Material'));
  }
  page.append(h('p', { class: 'meta', style: 'color:var(--muted);font-size:14px;margin-top:12px' },
    'Lägg till material (bild på sidan, Word, PDF) så vet Pluggis vad som ingår och gör en plan.'));
  return page;
};

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

// ---- Uppladdning ----
async function openUpload(homeworkId) {
  const picker = h('input', { type: 'file', accept: FILE_ACCEPT, style: 'display:none' });
  document.body.append(picker);
  picker.addEventListener('change', async () => {
    const file = picker.files[0];
    picker.remove();
    if (!file) return;
    if (!S.homework) { try { S.homework = (await api('/api/homework')).filter(x => x.kind !== 'OTHER'); } catch { S.homework = []; } }
    showSheet(file, homeworkId);
  });
  picker.click();
}

function showSheet(file, homeworkId) {
  const sheet = $('#sheet');
  const target = h('select', {}, h('option', { value: '' }, 'En ny läxa eller ett nytt prov'),
    (S.homework || []).filter(x => x.status === 'OPEN' && !(x.dueDate && daysUntil(x.dueDate) < 0)).map(x =>
      h('option', { value: x.id, selected: String(x.id) === String(homeworkId) || null },
        `${x.kind === 'TEST' ? 'Prov' : 'Läxa'}: ${x.title}${x.dueDate ? ` (${rel(x.dueDate)})` : ''}`)));
  const cards = h('input', { type: 'checkbox' });
  const cardsRow = h('label', { class: 'check', style: homeworkId ? '' : 'display:none' }, cards, 'Gör flashkort av det också');
  target.addEventListener('change', () => { cardsRow.style.display = target.value ? '' : 'none'; });
  const go = h('button', { class: 'go' }, 'Skicka');
  const close = () => { sheet.hidden = true; sheet.replaceChildren(); };
  go.addEventListener('click', async () => {
    go.disabled = true; go.textContent = 'Pluggis läser…';
    const fd = new FormData(); fd.append('file', file);
    const q = new URLSearchParams({ deviceId: S.deviceId });
    if (target.value) { q.set('homeworkId', target.value); q.set('flashcards', String(cards.checked)); }
    try {
      const r = await api(`/api/homework/upload?${q}`, { method: 'POST', form: fd });
      close();
      if (target.value) {
        const parts = [r.sections && r.sections.length ? `${r.sections.length} avsnitt inlagda` : 'Materialet sparat',
          r.replanned ? 'ny plan klar' : null, r.deckId ? `${r.cardCount} flashkort` : null].filter(Boolean);
        toast(parts.join(' · '));
        addMsg('note', `📎 ${file.name}: ${parts.join(' · ')}`);
      } else {
        const n = (r.saved || []).length;
        toast(n ? `La till ${n} st` : (r.note || 'Hittade inga läxor i filen'));
        addMsg('note', `📷 ${n ? `La till: ${(r.saved || []).map(x => x.title).join(', ')}` : (r.note || 'Hittade inga läxor')}`);
      }
      S.homework = null;
      if (S.tab !== 'chatt') show(S.tab);
    } catch (e) {
      go.disabled = false; go.textContent = 'Skicka';
      toast(e.message, true);
    }
  });
  sheet.replaceChildren(h('div', { class: 'panel', onclick: (e) => e.stopPropagation() },
    h('h2', {}, `📎 ${file.name}`),
    h('label', {}, 'Vad är det?'), target, cardsRow,
    h('div', { class: 'actions' }, h('button', { onclick: close }, 'Avbryt'), go)));
  sheet.onclick = close;
  sheet.hidden = false;
}

// ---- Poäng ----
function setPoints(n) { if (n !== undefined && n !== null) $('#points').textContent = `${n} p`; }
async function refreshPoints() {
  try { setPoints((await api(`/api/devices/${enc(S.deviceId)}/rewards`)).weekPoints); } catch { /* inte viktigt */ }
}

// ---- Flikar ----
async function show(tab) {
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
$('#points').addEventListener('click', () => show('poang'));

// ---- Start ----
function askForLink() {
  const input = h('input', { placeholder: 'Klistra in elevlänken eller koden', autocapitalize: 'off', autocorrect: 'off' });
  $('#view').replaceChildren(h('div', { class: 'login' },
    h('h2', {}, 'Hej! 👋'), h('p', {}, 'Öppna elevlänken du fått, eller klistra in den här.'), input,
    h('button', { class: 'big', onclick: () => {
      const v = input.value.trim(); const m = v.match(/[#&]t=([^&\s]+)/);
      S.token = m ? decodeURIComponent(m[1]) : v;
      store.set('pluggis.chat.token', S.token);
      // Varje tryck håller ljudet upplåst (iOS stänger av det när appen legat i bakgrunden)
document.addEventListener('touchend', () => { if (S.voice !== 'av') unlockAudio(); }, { passive: true });
document.addEventListener('click', () => { if (S.voice !== 'av') unlockAudio(); });
start();
    } }, 'Fortsätt')));
}

async function start() {
  try {
    const me = await api('/api/chat/me');
    S.deviceId = me.deviceId || S.deviceId;
    S.name = me.name || '';
    S.serverVoice = !!me.serverVoice;
    if (S.voice === 'pluggis' && !S.serverVoice) S.voice = 'telefon';
    store.set('pluggis.chat.device', S.deviceId);
  } catch (e) {
    if (e.auth) { askForLink(); toast(e.message, true); return; }
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
