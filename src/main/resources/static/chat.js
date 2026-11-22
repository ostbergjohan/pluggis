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
  if (n === 0) return 'Idag'; if (n === 1) return 'Imorgon';
  return new Intl.DateTimeFormat('sv-SE', { weekday: 'long', day: 'numeric', month: 'short' }).format(parseDay(iso));
};

// ---- Uppläsning ----
// Tre lägen: av, "pluggis" (Pluggis egen röst från servern, samma som Tab5) eller "telefon" (iPhones röst).
// iPhone spelar bara upp ljud som "låsts upp" av ett tryck – det görs när man väljer läge och när man skickar.
const VOICE_MODES = ['av', 'pluggis', 'telefon'];
let audioCtx = null;
let playChain = Promise.resolve();

function unlockAudio() {
  // iOS 17+: spela även när telefonen står på ljudlöst (sidoknappen)
  try { if (navigator.audioSession) navigator.audioSession.type = 'playback'; } catch { /* äldre iOS */ }
  try {
    if (!audioCtx) audioCtx = new (window.AudioContext || window.webkitAudioContext)();
    if (audioCtx.state !== 'running') audioCtx.resume();
    // En kort tystnad låser upp uppspelningen på iOS
    const buf = audioCtx.createBuffer(1, 1, 22050);
    const src = audioCtx.createBufferSource(); src.buffer = buf; src.connect(audioCtx.destination); src.start(0);
  } catch { /* ingen Web Audio */ }
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
    playServerAudio(await res.arrayBuffer(), true);
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

/** Text från coachen: läs upp med telefonens röst (Pluggis röst kommer som ljud från servern). */
function speak(text) {
  if (S.voice === 'telefon') speakPhone(text);
}

/** Ljud (wav) från servern – spelas i ordning. */
function playServerAudio(arrayBuffer, force) {
  if ((S.voice !== 'pluggis' && !force) || !arrayBuffer) return;
  if (!audioCtx) { try { audioCtx = new (window.AudioContext || window.webkitAudioContext)(); } catch { return playWithElement(arrayBuffer); } }
  const copy = arrayBuffer.slice(0);
  playChain = playChain.then(async () => {
    if (audioCtx.state !== 'running') {
      try { await audioCtx.resume(); } catch { /* kräver tryck */ }
      if (audioCtx.state !== 'running') { toast('Tryck någonstans på skärmen så hörs Pluggis'); }
    }
    await new Promise((resolve) => {
      const play = (buf) => {
        const src = audioCtx.createBufferSource();
        src.buffer = buf; src.connect(audioCtx.destination);
        src.onended = resolve; src.start(0);
      };
      const fail = () => { playWithElement(copy).then(resolve); };
      try {
        const p = audioCtx.decodeAudioData(copy, play, fail);
        if (p && p.catch) p.catch(fail);
      } catch { fail(); }
    });
  }).catch(() => {});
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
  if (S.ws && S.ws.readyState === 1) S.ws.send(JSON.stringify({ type: 'hello', audio: mode === 'pluggis', audioFormat: 'webm' }));
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
    ws.send(JSON.stringify({ type: 'hello', audio: S.voice === 'pluggis', audioFormat: 'webm' }));
  };
  ws.onclose = (e) => {
    if (S.ws === ws) S.ws = null;
    $('#conn').className = 'dot off';
    if (e.code === 1008) { addMsg('err', 'Länken fungerar inte – be om en ny elevlänk.'); return; }
    const wait = Math.min(15000, 1000 * 2 ** S.retry++);
    setTimeout(() => { if (document.visibilityState === 'visible') connect(); }, wait);
  };
  ws.onmessage = (e) => {
    if (typeof e.data !== 'string') { playServerAudio(e.data); return; }
    let m; try { m = JSON.parse(e.data); } catch { return; }
    onServer(m);
  };
}
document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible') { connect(); refreshPoints(); } });
setInterval(() => { if (S.ws && S.ws.readyState === 1) S.ws.send('{"type":"ping"}'); }, 25000);

function send(obj) {
  if (!S.ws || S.ws.readyState !== 1) { toast('Inte ansluten än – försöker igen…', true); connect(); return false; }
  S.ws.send(JSON.stringify(obj));
  return true;
}

function onServer(m) {
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
