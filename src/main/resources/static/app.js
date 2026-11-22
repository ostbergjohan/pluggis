'use strict';

/* Pluggis – föräldravy och Tab5-simulator. Inga beroenden, inget byggsteg. */

// ---------- Hjälpare ----------

const store = {
  get(key, fallback) {
    try { const v = localStorage.getItem(key); return v === null ? fallback : v; } catch { return fallback; }
  },
  set(key, value) {
    try { localStorage.setItem(key, value); } catch { /* privat läge e.d. */ }
  },
};

const state = {
  token: store.get('lc.token', ''),
  deviceId: store.get('lc.device', 'tab5-1'),
  studentId: store.get('lc.student', ''),
  students: [],
  homework: [],
  openHomeworkId: null,
  openDeckId: null,
  ws: null,
  speak: store.get('lc.speak', '1') === '1',
};

const $ = (sel, root = document) => root.querySelector(sel);

/** Skapar element säkert (text sätts som text, aldrig som HTML). */
function h(tag, attrs = {}, ...children) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v === null || v === undefined || v === false) continue;
    if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2), v);
    else if (k === 'class') el.className = v;
    else if (k === 'value') el.value = v;
    else if (k === 'checked') el.checked = !!v;
    else el.setAttribute(k, v === true ? '' : v);
  }
  for (const c of children.flat(Infinity)) {
    if (c === null || c === undefined || c === false) continue;
    el.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
  return el;
}

function toast(message, isError = false) {
  const t = h('div', { class: 'toast' + (isError ? ' error' : '') }, message);
  $('#toasts').append(t);
  setTimeout(() => t.remove(), isError ? 6000 : 3000);
}

async function api(path, { method = 'GET', body, form, raw } = {}) {
  const headers = {};
  if (state.token) headers['X-Pluggis-Token'] = state.token;
  if (state.studentId) headers['X-Pluggis-Student'] = state.studentId;
  let payload;
  if (form) payload = form;
  else if (body !== undefined) {
    headers['Content-Type'] = typeof body === 'string' ? 'text/plain' : 'application/json';
    payload = typeof body === 'string' ? body : JSON.stringify(body);
  }
  const res = await fetch(path, { method, headers, body: payload });
  if (res.status === 401) {
    toast('Fel eller saknad token – ange den under Inställningar.', true);
    throw new Error('401');
  }
  if (raw) return res;
  if (res.status === 204) return null;
  const type = res.headers.get('content-type') || '';
  const data = type.includes('json') ? await res.json() : await res.text();
  if (!res.ok) {
    const msg = (data && data.error) || (typeof data === 'string' && data) || res.statusText;
    throw new Error(msg);
  }
  return data;
}

/** Kör en åtgärd med felhantering och valfri knapp som visar att något händer. */
async function run(fn, button, okMessage) {
  const label = button ? button.textContent : null;
  if (button) { button.disabled = true; button.replaceChildren(h('span', { class: 'spinner' }), ' ', label); }
  try {
    const result = await fn();
    if (okMessage) toast(okMessage);
    return result;
  } catch (e) {
    if (e.message !== '401') toast(e.message || 'Något gick fel', true);
    return undefined;
  } finally {
    if (button) { button.disabled = false; button.textContent = label; }
  }
}

const SV_DAY = new Intl.DateTimeFormat('sv-SE', { weekday: 'short', day: 'numeric', month: 'numeric' });
const SV_LONG = new Intl.DateTimeFormat('sv-SE', { weekday: 'long', day: 'numeric', month: 'long' });

function parseDay(iso) {
  if (!iso) return null;
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(y, m - 1, d);
}
function fmtDay(iso) { const d = parseDay(iso); return d ? SV_DAY.format(d) : '–'; }
function fmtLong(iso) { const d = parseDay(iso); return d ? SV_LONG.format(d) : '–'; }
function todayIso() {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}
function daysUntil(iso) {
  const d = parseDay(iso);
  if (!d) return null;
  const t = parseDay(todayIso());
  return Math.round((d - t) / 86400000);
}
function relDays(iso) {
  const n = daysUntil(iso);
  if (n === null) return '';
  if (n === 0) return 'idag';
  if (n === 1) return 'imorgon';
  if (n < 0) return `${-n} dagar sedan`;
  return `om ${n} dagar`;
}
function kr(v) {
  const n = Number(v || 0);
  return n.toLocaleString('sv-SE', { minimumFractionDigits: 0, maximumFractionDigits: 2 }) + ' kr';
}
function lines(text) { return (text || '').split('\n').map(s => s.trim()).filter(Boolean); }
function kindTag(kind) {
  if (kind === 'TEST') return h('span', { class: 'tag test' }, 'Prov');
  if (kind === 'OTHER') return h('span', { class: 'tag other' }, 'Annat');
  return h('span', { class: 'tag' }, 'Läxa');
}
const enc = encodeURIComponent;
const FILE_ACCEPT = 'image/*,.pdf,.docx,.txt,.md,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document';
const MAX_FILES = 10;

/** Förminskar foton (max 2000 px, JPEG) innan uppladdning – snabbare och inom Claudes gränser. */
async function shrinkImage(file) {
  if (!file.type || !file.type.startsWith('image/') || file.type === 'image/gif') return file;
  try {
    const url = URL.createObjectURL(file);
    const img = await new Promise((ok, fail) => { const i = new Image(); i.onload = () => ok(i); i.onerror = fail; i.src = url; });
    URL.revokeObjectURL(url);
    const max = 2000;
    const scale = Math.min(1, max / Math.max(img.naturalWidth, img.naturalHeight));
    if (scale === 1 && file.size < 1_500_000) return file;
    const c = document.createElement('canvas');
    c.width = Math.round(img.naturalWidth * scale); c.height = Math.round(img.naturalHeight * scale);
    c.getContext('2d').drawImage(img, 0, 0, c.width, c.height);
    const blob = await new Promise(ok => c.toBlob(ok, 'image/jpeg', 0.85));
    if (!blob) return file;
    return new File([blob], file.name.replace(/\.[^.]+$/, '') + '.jpg', { type: 'image/jpeg' });
  } catch {
    return file;
  }
}

/** FormData med alla filer (foton förminskade). */
async function filesForm(files) {
  const fd = new FormData();
  for (const f of files.slice(0, MAX_FILES)) {
    const small = await shrinkImage(f);
    fd.append('file', small, small.name || f.name);
  }
  return fd;
}

/** Bild som kräver token (img src kan inte skicka headers): hämta som blob. */
async function authImage(path) {
  const res = await api(path, { raw: true });
  if (!res.ok) throw new Error(String(res.status));
  return URL.createObjectURL(await res.blob());
}

async function loadHomework(all = true) {
  state.homework = await api(`/api/homework?openOnly=${!all}`);
  return state.homework;
}
function homeworkTitle(id) {
  const hw = state.homework.find(x => x.id === id);
  return hw ? hw.title : `Läxa ${id}`;
}

// ---------- Diagram ----------

function barChart(series, labels, { height = 160, colors = ['bar', 'bar2'], unit = '' } = {}) {
  const ns = 'http://www.w3.org/2000/svg';
  const w = 600, pad = 22, n = labels.length;
  const max = Math.max(1, ...series.flat());
  const svg = document.createElementNS(ns, 'svg');
  svg.setAttribute('viewBox', `0 0 ${w} ${height + pad}`);
  svg.setAttribute('role', 'img');
  const slot = (w - 10) / n;
  const bw = Math.max(3, (slot - 6) / series.length);
  series.forEach((vals, si) => {
    vals.forEach((v, i) => {
      const bh = Math.round((v / max) * (height - 14));
      const r = document.createElementNS(ns, 'rect');
      r.setAttribute('x', 5 + i * slot + 3 + si * bw);
      r.setAttribute('y', height - bh);
      r.setAttribute('width', bw - 1);
      r.setAttribute('height', Math.max(bh, v > 0 ? 2 : 0));
      r.setAttribute('rx', 2);
      r.setAttribute('class', colors[si]);
      const title = document.createElementNS(ns, 'title');
      title.textContent = `${labels[i]}: ${v}${unit}`;
      r.append(title);
      svg.append(r);
    });
  });
  labels.forEach((l, i) => {
    if (n > 16 && i % 2) return;
    const t = document.createElementNS(ns, 'text');
    t.setAttribute('x', 5 + i * slot + slot / 2);
    t.setAttribute('y', height + 14);
    t.setAttribute('text-anchor', 'middle');
    t.setAttribute('class', 'axis');
    t.textContent = l;
    svg.append(t);
  });
  return h('div', { class: 'chart' }, svg);
}

// ---------- Vyer ----------

const views = {};

views.oversikt = async (main) => {
  main.append(h('h1', {}, 'Översikt'), h('p', { class: 'sub' }, `Enhet: ${state.deviceId}`));
  const [week, stats, today] = await Promise.all([
    api(`/api/devices/${enc(state.deviceId)}/rewards`),
    api(`/api/devices/${enc(state.deviceId)}/stats?days=14`),
    api('/api/plan/today'),
    loadHomework(false),
  ]);

  const accuracy = stats.totalCardsReviewed ? Math.round(100 * stats.totalCardsCorrect / stats.totalCardsReviewed) : null;
  main.append(h('div', { class: 'grid cols-4' },
    kpi('Poäng denna vecka', week.weekPoints, `Totalt ${week.allTimePoints}`),
    kpi('Intjänat denna vecka', kr(week.earnedSek), `Obetalt ${kr(week.unpaidSek)}`),
    kpi('Streak', `${week.streakDays} ${week.streakDays === 1 ? 'dag' : 'dagar'}`, 'dagar i rad med pass'),
    kpi('Pluggtid 14 dagar', `${stats.totalMinutes} min`, `${stats.totalSessions} pass, ${stats.activeDays} aktiva dagar`),
    kpi('Flashkort 14 dagar', stats.totalCardsReviewed, accuracy === null ? 'inga försök än' : `${accuracy} % rätt`),
  ));

  const upcoming = state.homework
    .filter(x => x.kind !== 'OTHER' && x.dueDate && daysUntil(x.dueDate) >= 0 && daysUntil(x.dueDate) <= 21)
    .sort((a, b) => a.dueDate.localeCompare(b.dueDate));
  const missingContent = state.homework.filter(x => x.kind !== 'OTHER' && !x.description && (!x.steps || !x.steps.length));

  const labels = stats.perDay.map(d => fmtDay(d.day).replace('.', ''));
  main.append(h('div', { class: 'grid cols-2', style: 'margin-top:16px' },
    h('section', { class: 'panel' }, h('h2', {}, 'Idag'), planChecklist(today, true)),
    h('section', { class: 'panel' }, h('h2', {}, 'Kommande läxor och prov'),
      upcoming.length ? h('div', { class: 'list' }, upcoming.map(x => h('div', { class: 'check' },
        kindTag(x.kind),
        h('div', { style: 'flex:1' }, h('div', { class: 'task' }, x.title),
          h('div', { class: 'item-meta' }, `${fmtLong(x.dueDate)} · ${relDays(x.dueDate)}`,
            (!x.description && !(x.steps || []).length) ? ' · innehåll saknas' : '')))))
        : h('div', { class: 'empty' }, 'Inget inom tre veckor.'),
      missingContent.length ? h('p', { class: 'item-meta' }, `${missingContent.length} saknar innehåll – `,
        h('a', { href: '#laxor' }, 'fyll i vad som ingår'), ' så görs en plan.') : null),
    h('section', { class: 'panel' }, h('h2', {}, 'Pluggminuter per dag'),
      barChart([stats.perDay.map(d => d.minutes)], labels, { unit: ' min' })),
    h('section', { class: 'panel' }, h('h2', {}, 'Flashkort per dag'),
      barChart([stats.perDay.map(d => d.cardsReviewed), stats.perDay.map(d => d.cardsCorrect)], labels),
      h('div', { class: 'legend' }, h('span', {}, h('i', { style: 'background:var(--accent)' }), 'försök'),
        h('span', {}, h('i', { style: 'background:var(--blue)' }), 'rätt'))),
  ));

  if (stats.decks.length) {
    main.append(h('section', { class: 'panel', style: 'margin-top:16px' }, h('h2', {}, 'Kortlekar'),
      h('table', {}, h('thead', {}, h('tr', {}, ['Lek', 'Kort', 'Sitter (fack 4–5)', 'Försök 14 d', 'Rätt'].map(t => h('th', {}, t)))),
        h('tbody', {}, stats.decks.map(d => h('tr', {},
          h('td', {}, d.name), h('td', {}, d.cards), h('td', {}, `${d.learned} / ${d.cards}`),
          h('td', {}, d.reviewed), h('td', {}, d.reviewed ? `${Math.round(100 * d.correct / d.reviewed)} %` : '–')))))));
  }
};

function kpi(label, value, hint) {
  return h('div', { class: 'panel kpi' }, h('div', { class: 'label' }, label), h('div', { class: 'value' }, value),
    hint ? h('div', { class: 'hint' }, hint) : null);
}

function planChecklist(items, showHomework) {
  if (!items.length) return h('div', { class: 'empty' }, 'Inget planerat.');
  return h('div', {}, items.map(t => {
    const item = t.item || t;
    const box = h('input', {
      type: 'checkbox', checked: item.done, title: item.done ? 'Klicka för att ångra' : 'Bocka av',
      onchange: async () => {
        if (box.checked) {
          const ok = await run(() => api(`/api/plan/items/${item.id}/done?deviceId=${enc(state.deviceId)}`, { method: 'POST' }),
            null, 'Bra jobbat! +3 poäng');
          if (ok !== undefined) { item.done = true; row.classList.add('done'); } else box.checked = false;
        } else {
          if (!confirm(`Ångra att "${item.task}" är gjort? Poängen för det tas bort.`)) { box.checked = true; return; }
          const ok = await run(() => api(`/api/plan/items/${item.id}/undo?deviceId=${enc(state.deviceId)}`, { method: 'POST' }),
            null, 'Ångrat – momentet är inte gjort');
          if (ok !== undefined) { item.done = false; row.classList.remove('done'); } else box.checked = true;
        }
      },
    });
    const row = h('label', { class: 'check' + (item.done ? ' done' : '') }, box,
      h('div', {}, h('div', { class: 'task' }, item.task),
        h('div', { class: 'item-meta' }, `${item.minutes} min`,
          showHomework && t.homeworkTitle ? ` · ${t.homeworkTitle}` : '',
          t.overdue ? ' · ' : '', t.overdue ? h('span', { class: 'tag late' }, 'ligger efter') : '')));
    return row;
  }));
}

// ----- Läxor & prov -----

views.laxor = async (main) => {
  main.append(h('h1', {}, 'Läxor & prov'),
    h('p', { class: 'sub' }, 'Prenumerera på InfoMentors kalender, lägg till för hand, klistra in text eller ladda upp skärmdumpar, Word-filer och PDF:er.'));

  const showDone = h('input', { type: 'checkbox', checked: store.get('lc.showDone', '0') === '1',
    onchange: () => { store.set('lc.showDone', showDone.checked ? '1' : '0'); render(); } });
  const forms = h('div');
  const photoInput = h('input', { type: 'file', accept: FILE_ACCEPT, multiple: true, style: 'display:none',
    onchange: () => {
      const files = [...photoInput.files];
      photoInput.value = '';
      if (files.length > MAX_FILES) toast(`Högst ${MAX_FILES} filer åt gången – tar de första ${MAX_FILES}`, true);
      if (files.length) forms.replaceChildren(uploadForm(files.slice(0, MAX_FILES)));
    } });
  const photoBtn = h('button', { onclick: () => photoInput.click() }, '📎 Ladda upp fil');

  main.append(h('div', { class: 'toolbar' },
    h('button', { class: 'primary', onclick: () => toggleForm(forms, newHomeworkForm) }, '+ Ny läxa eller prov'),
    h('button', { onclick: () => toggleForm(forms, calendarForm) }, '📅 Kalender'),
    h('button', { onclick: () => toggleForm(forms, pasteForm) }, '📋 Klistra in text'),
    photoBtn, photoInput,
    h('span', { class: 'spacer' }),
    h('label', { style: 'display:flex;gap:6px;align-items:center;margin:0' }, showDone, 'Visa klara')),
    forms);

  const list = h('div', { class: 'list' });
  main.append(list);

  async function render() {
    const all = await loadHomework(true);
    const items = all.filter(x => showDone.checked || x.status === 'OPEN')
      .sort((a, b) => (a.dueDate || '9999').localeCompare(b.dueDate || '9999'));
    if (!items.length) { list.replaceChildren(h('div', { class: 'empty' }, 'Inga läxor här än.')); return; }
    const past = items.filter(x => x.dueDate && daysUntil(x.dueDate) < 0);
    const upcoming = items.filter(x => x.dueDate && daysUntil(x.dueDate) >= 0);
    const undated = items.filter(x => !x.dueDate);
    // Passerat: senaste först
    past.reverse();
    const openPast = past.filter(x => x.status === 'OPEN');
    const pastOpen = store.get('lc.pastOpen', '0') === '1';
    const pastBody = h('div', { style: pastOpen ? '' : 'display:none' }, past.map(x => homeworkItem(x, render)));
    const parts = [];
    parts.push(h('h3', { class: 'group-head' }, `Kommande (${upcoming.length})`),
      upcoming.length ? upcoming.map(x => homeworkItem(x, render)) : h('div', { class: 'empty' }, 'Inget kommande.'));
    if (undated.length) parts.push(h('h3', { class: 'group-head' }, `Utan datum (${undated.length})`), undated.map(x => homeworkItem(x, render)));
    if (past.length) {
      const toggle = h('button', { class: 'small', onclick: () => {
        const show = pastBody.style.display === 'none';
        pastBody.style.display = show ? '' : 'none';
        toggle.textContent = show ? 'Dölj' : 'Visa';
        store.set('lc.pastOpen', show ? '1' : '0');
      } }, pastOpen ? 'Dölj' : 'Visa');
      parts.push(h('div', { class: 'group-head', style: 'display:flex;gap:8px;align-items:center;margin-top:18px' },
        h('h3', { style: 'margin:0;flex:1' }, `Passerat (${past.length})`), toggle,
        openPast.length ? h('button', { class: 'small', onclick: async (e) => {
          if (!confirm(`Markera ${openPast.length} passerade som klara?`)) return;
          const r = await run(() => api('/api/homework/archive-past', { method: 'POST' }), e.target);
          if (r) { toast(`${r.archived} markerade som klara`); render(); }
        } }, `✓ Markera ${openPast.length} som klara`) : null),
        pastBody);
    }
    list.replaceChildren(...parts.flat());
  }

  function newHomeworkForm() {
    const f = homeworkFields({});
    return h('div', { class: 'panel', style: 'margin-bottom:16px' }, h('h2', {}, 'Ny läxa eller prov'), f.el,
      h('div', { class: 'toolbar', style: 'margin-top:12px' },
        h('button', { class: 'primary', onclick: async (e) => {
          const v = f.values();
          if (!v.title) return toast('Ange en titel', true);
          const created = await run(() => api('/api/homework', { method: 'POST',
            body: { subject: v.subject, title: v.title, description: v.description, dueDate: v.dueDate, steps: v.steps } }), e.target);
          if (!created) return;
          await api(`/api/homework/${created.id}`, { method: 'PUT', body: { kind: v.kind, weight: v.weight, replan: false } });
          if (v.dueDate && (v.description || v.steps.length)) {
            await run(() => api(`/api/plan/homework/${created.id}`, { method: 'POST' }), null, 'Sparad och planerad');
          } else toast('Sparad');
          state.openHomeworkId = created.id;
          forms.replaceChildren();
          render();
        } }, 'Spara'),
        h('button', { onclick: () => forms.replaceChildren() }, 'Avbryt')));
  }

  function uploadForm(files) {
    const target = h('select', {}, h('option', { value: '' }, 'Ny läxa eller prov – läs av allt i filen'),
      state.homework.filter(x => x.status === 'OPEN' && x.kind !== 'OTHER' && !(x.dueDate && daysUntil(x.dueDate) < 0))
        .sort((a, b) => (a.dueDate || '9999').localeCompare(b.dueDate || '9999'))
        .map(x => h('option', { value: x.id }, `${x.kind === 'TEST' ? 'Prov' : 'Läxa'}: ${x.title}${x.dueDate ? ` (${fmtDay(x.dueDate)})` : ''}`)));
    const cards = h('input', { type: 'checkbox' });
    const cardsRow = h('label', { style: 'display:none;gap:6px;align-items:center' }, cards, 'Gör också flashkort av materialet');
    target.addEventListener('change', () => { cardsRow.style.display = target.value ? 'flex' : 'none'; });
    return h('div', { class: 'panel', style: 'margin-bottom:16px' }, h('h2', {}, files.length === 1 ? `Ladda upp: ${files[0].name}` : `Ladda upp ${files.length} filer`),
      h('label', {}, 'Vad är det här?'), target, cardsRow,
      h('p', { class: 'item-meta' }, 'Koppla till en läxa eller ett prov: Claude läser vad som ingår (avsnitt, sidor, begrepp), lägger in det och gör om planen.'),
      h('div', { class: 'toolbar', style: 'margin-top:12px' },
        h('button', { class: 'primary', onclick: async (e) => {
          const fd = await filesForm(files);
          const q = new URLSearchParams({ deviceId: state.deviceId });
          if (target.value) { q.set('homeworkId', target.value); q.set('flashcards', String(cards.checked)); }
          const r = await run(() => api(`/api/homework/upload?${q}`, { method: 'POST', form: fd }), e.target);
          if (!r) return;
          if (target.value) {
            toast([`${r.sections.length} avsnitt inlagda`, r.replanned ? 'ny plan gjord' : null,
              r.deckId ? `${r.cardCount} flashkort` : null, r.note].filter(Boolean).join(' · '));
            state.openHomeworkId = Number(target.value);
          } else {
            toast(`La till ${r.saved.length} st${r.note ? ' – ' + r.note : ''}`);
          }
          forms.replaceChildren();
          render();
        } }, 'Ladda upp'),
        h('button', { onclick: () => forms.replaceChildren() }, 'Avbryt')));
  }

  function calendarForm() {
    const panel = h('div', { class: 'panel', style: 'margin-bottom:16px' }, h('h2', {}, 'Kalender'),
      h('div', { class: 'empty' }, h('span', { class: 'spinner' }), ' Hämtar…'));
    (async () => {
      const st = await api('/api/calendar');
      const url = h('input', { value: st.url || '', placeholder: 'webcals://im2-api.infomentor.se/V1/CalendarV2/IcalSubscription/Subscription/…', autocomplete: 'off' });
      const only = h('input', { type: 'checkbox', checked: st.onlySchoolwork !== false });
      const status = h('p', { class: 'item-meta' }, syncText(st));
      const previewBox = h('div');
      const fileInput = h('input', { type: 'file', accept: '.ics,text/calendar', style: 'display:none' });
      let fileText = null;

      fileInput.addEventListener('change', async () => {
        const f = fileInput.files[0];
        if (!f) return;
        fileText = await f.text();
        const entries = await run(() => api('/api/calendar/preview', { method: 'POST', body: fileText }), fileBtn);
        if (entries) showPreview(entries, true);
        fileInput.value = '';
      });
      const fileBtn = h('button', { onclick: () => fileInput.click() }, '📂 Välj .ics-fil');

      function showPreview(entries, selectable) {
        if (!entries.length) { previewBox.replaceChildren(h('div', { class: 'empty' }, 'Kalendern var tom.')); return; }
        const boxes = [];
        const rows = entries.map(e => {
          const note = !e.inRange ? 'utanför tidsfönstret' : e.alreadyImported ? 'finns redan – uppdateras'
            : !e.schoolwork ? 'ser inte ut som läxa/prov' : '';
          const box = h('input', { type: 'checkbox', checked: e.inRange && (e.schoolwork || !only.checked), disabled: !e.inRange || !selectable });
          box.dataset.uid = e.uid;
          boxes.push(box);
          return h('tr', {}, h('td', {}, box), h('td', { style: 'white-space:nowrap' }, fmtDay(e.date)), h('td', {}, e.summary),
            h('td', {}, kindTag(e.kind)), h('td', { class: 'item-meta' }, note));
        });
        const willImport = () => boxes.filter(b => b.checked && !b.disabled).length;
        const count = h('span', { class: 'item-meta' }, '');
        const update = () => { count.textContent = selectable ? `${willImport()} valda` : `${entries.filter(e => e.inRange && (e.schoolwork || !only.checked)).length} importeras`; };
        boxes.forEach(b => b.addEventListener('change', update));
        update();
        previewBox.replaceChildren(
          h('table', { style: 'margin-top:12px' }, h('thead', {}, h('tr', {}, ['', 'Datum', 'Händelse', 'Typ', ''].map(t => h('th', {}, t)))), h('tbody', {}, rows)),
          h('div', { class: 'toolbar', style: 'margin-top:10px' },
            selectable ? h('button', { class: 'primary', onclick: async (e) => {
              const uids = boxes.filter(b => b.checked && !b.disabled).map(b => b.dataset.uid);
              if (!uids.length) return toast('Inget valt', true);
              const r = await run(() => api(`/api/calendar/import?uids=${enc(uids.join(','))}`, { method: 'POST', body: fileText }), e.target);
              if (r) { toast(`Importerade ${r.imported}${r.replanned ? `, planerade om ${r.replanned}` : ''}`); forms.replaceChildren(); render(); }
            } }, 'Importera valda') : null,
            count));
      }

      panel.replaceChildren(h('h2', {}, 'Kalender'),
        h('h3', {}, 'Prenumerera (synkas varje timme)'),
        h('p', { class: 'item-meta' }, 'InfoMentor: öppna kalendern, välj prenumerera/exportera och kopiera länken (webcals://…). Google Kalender: Inställningar → kalendern → "Hemlig adress i iCal-format". Länken är personlig – behandla den som ett lösenord.'),
        url,
        h('label', { style: 'display:flex;gap:6px;align-items:center' }, only, 'Bara läxor och prov (hoppa över studiedagar, lov m.m.)'),
        h('div', { class: 'toolbar', style: 'margin-top:10px' },
          h('button', { onclick: async (e) => {
            if (!url.value.trim()) return toast('Klistra in en länk', true);
            const entries = await run(() => api(`/api/calendar/preview?url=${enc(url.value.trim())}`, { method: 'POST' }), e.target);
            if (entries) showPreview(entries, false);
          } }, 'Förhandsgranska'),
          h('button', { class: 'primary', onclick: async (e) => {
            const r = await run(() => api('/api/calendar', { method: 'PUT', body: { url: url.value.trim(), onlySchoolwork: only.checked } }), e.target);
            if (r) { toast(`Sparad – importerade ${r.imported}, hoppade över ${r.skipped}${r.merged ? `, ${r.merged} dubbletter borttagna` : ''}`); status.textContent = syncText(await api('/api/calendar')); render(); }
          } }, 'Spara och synka'),
          st.url ? h('button', { onclick: async (e) => {
            const r = await run(() => api('/api/calendar/sync', { method: 'POST' }), e.target);
            if (r) { toast(`Synkad – ${r.imported} importerade${r.merged ? `, ${r.merged} dubbletter borttagna` : ''}`); status.textContent = syncText(await api('/api/calendar')); render(); }
          } }, '↻ Synka nu') : null,
          h('button', { onclick: async (e) => {
            const r = await run(() => api('/api/calendar/dedupe', { method: 'POST' }), e.target);
            if (r) { toast(r.merged ? `Tog bort ${r.merged} dubbletter` : 'Inga dubbletter hittades'); render(); }
          } }, '🧹 Rensa dubbletter'),
          st.url ? h('button', { class: 'danger', onclick: async () => {
            if (!confirm('Sluta prenumerera? Redan importerade läxor ligger kvar.')) return;
            await run(() => api('/api/calendar', { method: 'DELETE' }), null, 'Prenumerationen borttagen');
            forms.replaceChildren();
          } }, 'Ta bort') : null),
        status,
        h('h3', { style: 'margin-top:16px' }, 'Eller importera en fil'),
        h('div', { class: 'toolbar' }, fileBtn, fileInput, h('span', { class: 'item-meta' }, 'Du väljer själv vilka händelser som importeras.')),
        previewBox);
    })().catch(e => panel.replaceChildren(h('h2', {}, 'Kalender'), h('div', { class: 'empty' }, e.message)));
    return panel;
  }

  function syncText(st) {
    if (!st.url) return 'Ingen prenumeration än.';
    if (st.lastError) return `Senaste synk misslyckades: ${st.lastError}`;
    if (!st.lastSync) return 'Prenumeration sparad – synkas varje timme.';
    const r = st.lastResult || {};
    return `Senast synkad ${new Date(st.lastSync).toLocaleString('sv-SE')}: ${r.imported ?? 0} importerade, ${r.skipped ?? 0} överhoppade.`;
  }

  function pasteForm() {
    const ta = h('textarea', { rows: 8, placeholder: 'Klistra in veckoplanen eller veckobrevet från InfoMentor…' });
    return h('div', { class: 'panel', style: 'margin-bottom:16px' }, h('h2', {}, 'Klistra in från InfoMentor'), ta,
      h('div', { class: 'toolbar', style: 'margin-top:12px' },
        h('button', { class: 'primary', onclick: async (e) => {
          if (!ta.value.trim()) return;
          const r = await run(() => api(`/api/homework/text?deviceId=${enc(state.deviceId)}`, { method: 'POST', body: { text: ta.value } }), e.target);
          if (r) { toast(`La till ${r.saved.length} st${r.note ? ' – ' + r.note : ''}`); forms.replaceChildren(); render(); }
        } }, 'Läs av med Claude'),
        h('button', { onclick: () => forms.replaceChildren() }, 'Avbryt')));
  }

  await render();
};

function toggleForm(container, factory) {
  if (container.firstChild && container.firstChild.dataset.factory === factory.name) { container.replaceChildren(); return; }
  const el = factory();
  el.dataset.factory = factory.name;
  container.replaceChildren(el);
}

function homeworkFields(x) {
  const title = h('input', { value: x.title || '', placeholder: 'T.ex. NO-prov fotosyntes' });
  const subject = h('input', { value: x.subject || '', placeholder: 'T.ex. NO' });
  const kind = h('select', {}, h('option', { value: 'HOMEWORK' }, 'Läxa / inlämning'), h('option', { value: 'TEST' }, 'Prov / förhör'),
    h('option', { value: 'OTHER' }, 'Annat (räknas inte)'));
  kind.value = x.kind || 'HOMEWORK';
  const due = h('input', { type: 'date', value: x.dueDate || '' });
  const noDate = h('button', { type: 'button', class: 'small', title: 'Ta bort datumet', onclick: () => { due.value = ''; } }, '✕');
  const description = h('textarea', { rows: 3, placeholder: 'Vad ingår? T.ex. "Utkik kap 4 s. 40–72 + begreppslistan"' }, x.description || '');
  const steps = h('textarea', { rows: 3, placeholder: 'Ett per rad (valfritt) – avsnitt eller delsteg' }, (x.steps || []).join('\n'));
  const weight = h('select', {}, [10, 20, 30, 40, 50, 60, 70, 80, 90, 100].map(w => h('option', { value: w },
    `${w} %${w === 10 ? ' – litet' : w === 50 ? ' – normalt' : w === 100 ? ' – mycket viktigt' : ''}`)));
  weight.value = String(x.weight || 50);
  const el = h('div', {},
    h('div', { class: 'row' },
      h('div', {}, h('label', {}, 'Titel'), title),
      h('div', {}, h('label', {}, 'Ämne'), subject),
      h('div', {}, h('label', {}, 'Typ'), kind),
      h('div', {}, h('label', {}, 'Datum (inlämning/prov)'), h('div', { style: 'display:flex;gap:6px;align-items:center' }, due, noDate)),
      h('div', {}, h('label', { title: 'Hur viktigt det är. Högre vikt ger mer tid och fler repetitioner i planen.' }, 'Vikt'), weight)),
    h('label', {}, 'Vad som ingår'), description,
    h('label', {}, 'Avsnitt / delsteg'), steps);
  return {
    el,
    values: () => ({
      title: title.value.trim(), subject: subject.value.trim(), kind: kind.value,
      dueDate: due.value || null, clearDueDate: !due.value && !!x.dueDate,
      description: description.value.trim(), steps: lines(steps.value),
      weight: Number(weight.value),
    }),
  };
}

function homeworkItem(x, rerender) {
  const open = state.openHomeworkId === x.id;
  const body = h('div', { class: 'item-body' });
  const meta = [x.subject, x.dueDate ? `${fmtLong(x.dueDate)} (${relDays(x.dueDate)})` : 'inget datum'].filter(Boolean).join(' · ');
  const missing = x.kind !== 'OTHER' && !x.description && !(x.steps || []).length;
  const item = h('div', { class: 'item' },
    h('div', { class: 'item-head', onclick: () => {
      state.openHomeworkId = state.openHomeworkId === x.id ? null : x.id;
      if (state.openHomeworkId === x.id) fill(); else body.remove();
    } },
      kindTag(x.kind),
      h('div', { class: 'item-title' }, x.title),
      missing ? h('span', { class: 'tag late' }, 'innehåll saknas') : null,
      x.weight && x.weight !== 50 && x.kind !== 'OTHER' ? h('span', { class: 'tag' + (x.weight >= 80 ? ' late' : ''), title: 'Vikt' }, `vikt ${x.weight} %`) : null,
      x.status === 'DONE' ? h('span', { class: 'tag done' }, 'klar') : null,
      h('div', { class: 'item-meta' }, meta)));

  async function fill() {
    const f = homeworkFields(x);
    const planBox = h('div', {}, h('span', { class: 'spinner' }));
    const matBox = h('div', {}, h('span', { class: 'spinner' }));
    const staged = [];
    const stageBox = h('div');
    const drawStage = () => {
      if (!staged.length) { stageBox.replaceChildren(); return; }
      const cardsCb = h('input', { type: 'checkbox' });
      stageBox.replaceChildren(h('div', { class: 'stage' },
        h('div', { class: 'stage-list' }, staged.map((f, i) => h('div', { class: 'stage-item' },
          f.type && f.type.startsWith('image/') ? h('img', { src: URL.createObjectURL(f), alt: '' }) : h('span', { class: 'stage-doc' }, (f.name.split('.').pop() || 'fil').toUpperCase()),
          h('span', { class: 'item-meta' }, `${i + 1}. ${f.name}`),
          h('button', { class: 'ghost', title: 'Ta bort', onclick: () => { staged.splice(i, 1); drawStage(); } }, '✕')))),
        h('label', { style: 'display:flex;gap:6px;align-items:center;margin-top:8px' }, cardsCb, 'Gör flashkort av materialet också'),
        h('div', { class: 'toolbar', style: 'margin-top:8px' },
          staged.length < MAX_FILES ? h('button', { onclick: () => matInput.click() }, '➕ Lägg till fler sidor') : null,
          h('button', { class: 'primary', onclick: () => sendMaterial(staged.splice(0), cardsCb.checked) },
            `Skicka ${staged.length} ${staged.length === 1 ? 'fil' : 'sidor'}`),
          h('button', { class: 'ghost', onclick: () => { staged.length = 0; drawStage(); } }, 'Avbryt'))));
    };
    const matInput = h('input', { type: 'file', accept: FILE_ACCEPT, multiple: true, style: 'display:none', onchange: () => {
      const picked = [...matInput.files];
      matInput.value = '';
      const room = MAX_FILES - staged.length;
      if (picked.length > room) toast(`Högst ${MAX_FILES} sidor åt gången`, true);
      staged.push(...picked.slice(0, room));
      drawStage();
    } });
    const sendMaterial = async (files, cards) => {
      drawStage();
      if (!files.length) return;
      matBox.replaceChildren(h('span', { class: 'spinner' }), files.length > 1 ? ` Claude läser ${files.length} filer…` : ' Claude läser materialet…');
      const fd = await filesForm(files);
      const r = await run(() => api(`/api/homework/upload?homeworkId=${x.id}&flashcards=${cards}&deviceId=${enc(state.deviceId)}`,
        { method: 'POST', form: fd }));
      if (r) {
        toast([`${r.sections.length} avsnitt inlagda`, r.replanned ? 'ny plan gjord' : null,
          r.deckId ? `${r.cardCount} flashkort` : null, r.note].filter(Boolean).join(' · '));
        state.openHomeworkId = x.id;
        rerender();
      } else showMaterial();
    };
    body.replaceChildren(f.el,
      h('div', { class: 'toolbar', style: 'margin-top:12px' },
        h('button', { class: 'primary', onclick: async (e) => {
          const v = f.values();
          const r = await run(() => api(`/api/homework/${x.id}`, { method: 'PUT', body: v }), e.target, 'Sparat');
          if (r) rerender();
        } }, 'Spara'),
        x.kind === 'OTHER' ? null : h('button', { onclick: async (e) => {
          const v = f.values();
          await api(`/api/homework/${x.id}`, { method: 'PUT', body: { ...v, replan: false } });
          const r = await run(() => api(`/api/plan/homework/${x.id}`, { method: 'POST' }), e.target,
            'Ny plan gjord');
          if (r) { if (r.note) toast(r.note); showPlan(); }
        } }, '🗓 Planera'),
        h('button', { onclick: () => matInput.click() }, '📎 Material'),
        h('button', { onclick: async (e) => {
          const v = f.values();
          const r = await run(() => api(`/api/flashcards/generate?deviceId=${enc(state.deviceId)}`, { method: 'POST',
            body: { topic: v.title, subject: v.subject, sourceText: [v.description, ...v.steps].filter(Boolean).join('\n'),
              count: 15, homeworkId: x.id } }), e.target);
          if (r) toast(`Kortlek "${r.deck.name}" med ${r.cardCount} kort skapad`);
        } }, '🃏 Gör flashkort'),
        h('span', { class: 'spacer' }),
        x.status === 'OPEN'
          ? h('button', { onclick: async () => { await run(() => api(`/api/homework/${x.id}/done`, { method: 'POST' }), null, 'Markerad som klar'); rerender(); } }, '✓ Klar')
          : h('button', { onclick: async () => { await run(() => api(`/api/homework/${x.id}`, { method: 'PUT', body: { open: true } }), null, 'Öppnad igen'); rerender(); } }, 'Öppna igen'),
        h('button', { class: 'danger', onclick: async () => {
          if (!confirm(`Ta bort "${x.title}" och dess plan?`)) return;
          await run(() => api(`/api/homework/${x.id}`, { method: 'DELETE' }), null, 'Borttagen');
          rerender();
        } }, 'Ta bort')),
      h('h3', { style: 'margin-top:16px' }, 'Material'), matBox, stageBox, matInput,
      h('h3', { style: 'margin-top:16px' }, 'Plan'), planBox);
    item.append(body);
    showMaterial();

    async function showMaterial() {
      const list = await api(`/api/homework/${x.id}/material`);
      if (!list.length) {
        matBox.replaceChildren(h('div', { class: 'empty' }, 'Inget material än. Ladda upp en skärmdump, Word-fil, PDF eller text så läser Claude vad som ingår.'));
        return;
      }
      matBox.replaceChildren(...list.map(m => {
        const thumb = m.hasImage ? h('img', { class: 'mat-thumb', alt: m.filename || 'sida' }) : null;
        if (thumb) authImage(`/api/homework/material/${m.id}/image`).then(u => { thumb.src = u; thumb.onclick = () => window.open(u, '_blank'); }).catch(() => thumb.remove());
        const textBox = h('pre', { class: 'mat-text', style: 'display:none' }, m.text || '');
        return h('div', { class: 'check' },
          thumb || h('span', { class: 'tag' }, { IMAGE: 'Bild', PDF: 'PDF', WORD: 'Word', TEXT: 'Text' }[m.kind] || m.kind),
          h('div', { style: 'flex:1;min-width:0' }, h('div', { class: 'task' }, (m.page ? `Sida ${m.page} · ` : '') + (m.filename || 'fil')),
            m.summary ? h('div', { class: 'item-meta' }, m.summary) : null,
            m.text ? h('button', { class: 'small', style: 'margin-top:4px', onclick: (e) => {
              const show = textBox.style.display === 'none';
              textBox.style.display = show ? '' : 'none'; e.target.textContent = show ? 'Dölj text' : 'Visa text';
            } }, 'Visa text') : null, textBox),
          h('button', { class: 'small danger', onclick: async (e) => {
            const r = await run(() => api(`/api/homework/material/${m.id}`, { method: 'DELETE' }), e.target);
            if (r !== undefined) showMaterial();
          } }, '✕'));
      }));
    }

    async function showPlan() {
      const items = await api(`/api/plan/homework/${x.id}`);
      if (!items.length) {
        planBox.replaceChildren(h('div', { class: 'empty' },
          x.dueDate ? 'Ingen plan än – fyll i vad som ingår och tryck Planera.' : 'Ange datum och vad som ingår, tryck sedan Planera.'));
        return;
      }
      planBox.replaceChildren(...groupByDay(items).map(([day, its]) =>
        h('div', {}, h('div', { class: 'day-head' }, fmtLong(day)), planChecklist(its, false))));
    }
    showPlan();
  }

  if (open) fill();
  return item;
}

function groupByDay(items) {
  const map = new Map();
  for (const i of items) {
    const d = i.planDate;
    if (!map.has(d)) map.set(d, []);
    map.get(d).push(i);
  }
  return [...map.entries()].sort((a, b) => a[0].localeCompare(b[0]));
}

// ----- Plan -----

views.plan = async (main) => {
  const replanBtn = h('button', { class: 'secondary', onclick: async () => {
    const r = await run(() => api('/api/plan/replan-all', { method: 'POST' }), replanBtn,
      'Planerar om – tar en halv minut, sidan uppdateras sen');
    if (r !== undefined) {
      replanBtn.disabled = true; replanBtn.textContent = '⏳ Planerar om…';
      setTimeout(() => { if (location.hash === '#plan') route(); }, 30000);
    }
  } }, '🔄 Planera om allt');
  main.append(h('div', { style: 'display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap' },
      h('h1', {}, 'Plan'), replanBtn),
    h('p', { class: 'sub' }, 'Tre veckor framåt. Bocka av när det är gjort. Dra ⠿ för att flytta ett moment till en annan dag eller ändra ordning (eller ⋯ i mobilen). "Planera om allt" gör om planerna med max minuter per dag och pluggdagar.'));
  const [today, upcoming, , st] = await Promise.all([api('/api/plan/today'), api('/api/plan/upcoming?days=20'), loadHomework(true),
    api('/api/student').catch(() => ({}))]);
  const max = st.maxMinutesPerDay || 40;
  const studyDays = new Set(st.studyDays || []);
  const DOW = ['SUNDAY', 'MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY'];
  const hwById = new Map(state.homework.map(x => [x.id, x]));
  const titleOf = (id) => { const x = hwById.get(id); return x ? [x.subject, x.title].filter(Boolean).join(' · ') : `Läxa ${id}`; };

  const overdue = today.filter(t => t.overdue);
  const manual = state.homework.filter(x => x.manualPlan && x.status === 'OPEN');
  if (manual.length) {
    main.append(h('p', { class: 'item-meta', style: 'margin:-4px 0 12px' }, '✋ Flyttat för hand: ', manual.map(x => x.title).join(', '),
      '. Dessa planeras inte om automatiskt på natten – tryck Planera på läxan om Pluggis ska ta över igen.'));
  }

  let dragId = null;
  const byDay = new Map();
  for (const i of upcoming) { if (!byDay.has(i.planDate)) byDay.set(i.planDate, []); byDay.get(i.planDate).push(i); }

  async function arrange(day, ids) {
    const r = await run(() => api(`/api/plan/days/${day}`, { method: 'PUT', body: { ids } }));
    if (r !== undefined) route();
  }
  const openIds = (day) => (byDay.get(day) || []).filter(i => !i.done).map(i => i.id);
  function moveTo(id, day, index) {
    const ids = openIds(day).filter(x => x !== id);
    ids.splice(index == null ? ids.length : Math.max(0, Math.min(index, ids.length)), 0, id);
    arrange(day, ids);
  }

  function itemRow(t, day) {
    const item = t.item || t;
    const x = hwById.get(item.homeworkId);
    const row = h('div', { class: 'dnd-row' + (item.done ? ' is-done' : ''), draggable: item.done ? null : 'true' });
    row.dataset.id = item.id;
    const tools = h('div', { class: 'dnd-tools', hidden: true });
    const handle = item.done ? h('span', { class: 'dnd-handle off' }, '') : h('span', { class: 'dnd-handle', title: 'Dra för att flytta' }, '⠿');
    const more = item.done ? null : h('button', { class: 'ghost small', title: 'Flytta', onclick: (e) => {
      e.preventDefault(); tools.hidden = !tools.hidden;
      if (tools.hidden || tools.childElementCount) return;
      const date = h('input', { type: 'date', value: item.planDate, min: todayIso() });
      if (x && x.dueDate) { const d = parseDay(x.dueDate); d.setDate(d.getDate() - 1); date.max = d.toLocaleDateString('sv-SE'); }
      const ids = day ? openIds(day) : [];
      const pos = ids.indexOf(item.id);
      tools.append(h('label', { class: 'item-meta' }, 'Flytta till '), date,
        h('button', { class: 'small', onclick: () => { if (date.value) moveTo(item.id, date.value, null); } }, 'Flytta'),
        day && pos > 0 ? h('button', { class: 'small', title: 'Upp', onclick: () => moveTo(item.id, day, pos - 1) }, '↑') : null,
        day && pos >= 0 && pos < ids.length - 1 ? h('button', { class: 'small', title: 'Ner', onclick: () => moveTo(item.id, day, pos + 1) }, '↓') : null);
    } }, '⋯');
    const check = planChecklist([{ item, homeworkTitle: titleOf(item.homeworkId), overdue: t.overdue }], true);
    row.append(h('div', { class: 'dnd-main' }, handle, h('div', { style: 'flex:1;min-width:0' }, check), more), tools);
    row.addEventListener('dragstart', (e) => { dragId = item.id; row.classList.add('dragging'); e.dataTransfer.effectAllowed = 'move'; e.dataTransfer.setData('text/plain', String(item.id)); });
    row.addEventListener('dragend', () => { row.classList.remove('dragging'); document.querySelectorAll('.drop-mark').forEach(m => m.remove()); });
    return row;
  }

  function dayZone(day) {
    const items = byDay.get(day) || [];
    const total = items.reduce((n, i) => n + i.minutes, 0);
    const d = parseDay(day);
    const off = studyDays.size && !studyDays.has(DOW[d.getDay()]);
    const list = h('div', { class: 'dnd-list' });
    items.forEach(i => list.append(itemRow(i, day)));
    if (!items.length) list.append(h('div', { class: 'dnd-empty' }, off ? 'ledig dag' : 'inget planerat – släpp här'));
    const zone = h('div', { class: 'dnd-day' + (off ? ' off' : '') },
      h('div', { class: 'day-head dnd-head' }, h('span', {}, `${fmtLong(day)}${day === todayIso() ? ' – idag' : ''}`),
        total ? h('span', { class: 'item-meta' + (total > max ? ' over' : '') }, `${total} / ${max} min`) : null),
      list);
    const indexAt = (y) => {
      const rows = [...list.querySelectorAll('.dnd-row:not(.is-done)')].filter(r => Number(r.dataset.id) !== dragId);
      let i = 0; for (const r of rows) { const b = r.getBoundingClientRect(); if (y > b.top + b.height / 2) i++; }
      return { i, rows };
    };
    zone.addEventListener('dragover', (e) => {
      if (dragId == null) return;
      e.preventDefault(); e.dataTransfer.dropEffect = 'move';
      document.querySelectorAll('.drop-mark').forEach(m => m.remove());
      const { i, rows } = indexAt(e.clientY);
      const mark = h('div', { class: 'drop-mark' });
      if (rows[i]) list.insertBefore(mark, rows[i]); else list.append(mark);
    });
    zone.addEventListener('dragleave', (e) => { if (!zone.contains(e.relatedTarget)) zone.querySelectorAll('.drop-mark').forEach(m => m.remove()); });
    zone.addEventListener('drop', (e) => {
      e.preventDefault();
      if (dragId == null) return;
      const { i } = indexAt(e.clientY);
      const id = dragId; dragId = null;
      moveTo(id, day, i);
    });
    return zone;
  }

  if (overdue.length) {
    main.append(h('section', { class: 'panel', style: 'margin-bottom:16px' }, h('h2', {}, 'Ligger efter'),
      h('div', { class: 'dnd-list' }, overdue.map(t => itemRow(t, null))),
      h('p', { class: 'item-meta' }, 'Dra till en dag nedan, eller tryck ⋯ och välj dag.')));
  }
  const panel = h('section', { class: 'panel' });
  if (!upcoming.length) panel.append(h('div', { class: 'empty' }, 'Inget planerat. Gå till Läxor & prov, fyll i vad som ingår och tryck Planera.'));
  const start = parseDay(todayIso());
  for (let n = 0; n <= 20; n++) {
    const d = new Date(start); d.setDate(d.getDate() + n);
    panel.append(dayZone(d.toLocaleDateString('sv-SE')));
  }
  main.append(panel);
};

// ----- Kostnad -----

views.kostnad = async (main) => {
  const [c, cb] = await Promise.all([api('/api/costs?days=30'), api('/api/claude')]);
  const kr = (v) => `${(v || 0).toLocaleString('sv-SE', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} kr`;
  const n = (v) => (v || 0).toLocaleString('sv-SE');
  const sub = c.backend !== 'api';
  main.append(h('h1', {}, 'Kostnad'),
    h('p', { class: 'sub' }, `Uppskattat från tokens som Claude rapporterar, med API-prislistan och 1 USD = ${c.usdSek} kr. Den exakta fakturan finns i Claude Console.`));

  const card = (title, big, small) => h('div', { class: 'cost-card' }, h('div', { class: 'item-meta' }, title),
    h('div', { class: 'cost-big' }, big), small ? h('div', { class: 'item-meta' }, small) : null);
  main.append(claudeBackendPanel(cb));
  main.append(h('div', { class: 'cost-cards', style: 'margin-top:16px' },
    card(`Betalt i ${c.monthName}`, kr(c.month.sek), `${n(c.month.calls)} anrop`),
    card('Idag', kr(c.today.sek), `${n(c.today.calls)} anrop`),
    sub ? card('Sparat via abonnemanget', kr(c.month.subscriptionSek), 'hade kostat med API-nyckel') : null,
    card('Azure-röst', `${Math.round(100 * c.azure.characters / c.azure.free)} %`,
      `${n(c.azure.characters)} av ${n(c.azure.free)} gratis tecken`)));

  // Stapeldiagram senaste 30 dagarna
  const max = Math.max(0.01, ...c.days.map(d => d.sek + d.subscriptionSek));
  const bars = h('div', { class: 'cost-bars' }, c.days.map(d => h('div', {
      class: 'cost-bar', title: `${fmtDay(d.day)}: ${kr(d.sek)}${d.subscriptionSek ? ` (+ ${kr(d.subscriptionSek)} via abonnemang)` : ''}` },
    h('div', { class: 'cost-bar-sub', style: `height:${100 * d.subscriptionSek / max}%` }),
    h('div', { class: 'cost-bar-api', style: `height:${100 * d.sek / max}%` }))));
  main.append(h('section', { class: 'panel', style: 'margin-top:16px' }, h('h2', {}, 'Senaste 30 dagarna'), bars,
    h('p', { class: 'item-meta' }, h('span', { class: 'cost-dot api' }), ' betalt via API',
      sub ? [' ', h('span', { class: 'cost-dot sub' }), ' via abonnemanget (kostar inget extra)'] : null,
      ` · högsta dagen ${kr(max)}`)));

  const table = (rows, first) => h('table', {},
    h('thead', {}, h('tr', {}, [first, 'Anrop', 'Token in', 'Token ut', 'Betalt', sub ? 'Abonnemang' : null].filter(Boolean).map(t => h('th', {}, t)))),
    h('tbody', {}, rows.map(r => h('tr', {}, h('td', {}, r.label || r.name, r.models ? h('div', { class: 'item-meta' }, r.models.join(', ')) : null),
      h('td', {}, n(r.calls)), h('td', {}, n(r.inputTokens)), h('td', {}, n(r.outputTokens)), h('td', {}, kr(r.sek)),
      sub ? h('td', {}, kr(r.subscriptionSek)) : null))));
  main.append(h('section', { class: 'panel', style: 'margin-top:16px' }, h('h2', {}, `Per funktion i ${c.monthName}`),
    c.purposes.length ? table(c.purposes, 'Funktion') : h('div', { class: 'empty' }, 'Inga anrop ännu den här månaden.')));
  if (c.students.length > 1) {
    main.append(h('section', { class: 'panel', style: 'margin-top:16px' }, h('h2', {}, `Per elev i ${c.monthName}`), table(c.students, 'Elev')));
  }
  main.append(h('section', { class: 'panel', style: 'margin-top:16px' }, h('h2', {}, 'Priser som används'),
    h('table', {}, h('thead', {}, h('tr', {}, ['Modell', 'In', 'Ut', 'Cache läs', 'Cache skriv'].map(t => h('th', {}, t)))),
      h('tbody', {}, c.prices.map(p => h('tr', {}, h('td', {}, p.model),
        ...[p.input, p.output, p.cacheRead, p.cacheWrite].map(v => h('td', {}, `$${v}`)))))),
    h('p', { class: 'item-meta' }, 'USD per miljon token. Ändras under pluggis.costs i application.yml, växelkursen med PLUGGIS_USD_SEK. Kostnadsloggningen startade när den här fliken kom till – äldre anrop finns inte med.')));
};

function claudeBackendPanel(st) {
  const MODES = [
    ['api', 'Bara API-nyckel', 'Allt betalas per token. Snabbast, strömmad röst, inga gränser från abonnemanget.'],
    ['hybrid', 'Hybrid', `Rösten (${(st.apiPurposes || []).join(', ')}) via API-nyckeln, foto, planer, flashkort och material via abonnemanget. Faller tillbaka till API-nyckeln om abonnemanget inte går.`],
    ['claude-code', 'Bara abonnemanget', 'Allt via Claude Code på ditt Pro-abonnemang. Kostar inget extra, men rösten blir någon sekund långsammare och delar gränser med din egen användning.'],
  ];
  const ok = (b) => b ? '✅' : '❌';
  const when = (iso) => iso ? new Date(iso).toLocaleString('sv-SE', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }) : '–';
  const panel = h('section', { class: 'panel' }, h('h2', {}, 'Claude: API eller abonnemang'));
  const list = h('div', { class: 'mode-list' });
  for (const [id, title, desc] of MODES) {
    const radio = h('input', { type: 'radio', name: 'claudeMode', value: id, checked: st.mode === id });
    radio.addEventListener('change', async () => {
      const r = await run(() => api('/api/claude', { method: 'PUT', body: { mode: id } }), null, `Claude körs nu: ${title.toLowerCase()}`);
      if (r === undefined) { list.querySelector(`input[value="${st.mode}"]`).checked = true; return; }
      route();
    });
    list.append(h('label', { class: 'mode-opt' + (st.mode === id ? ' on' : '') }, radio,
      h('div', {}, h('strong', {}, title, st.configuredMode === id ? h('span', { class: 'item-meta' }, '  (som i .env)') : null),
        h('div', { class: 'item-meta' }, desc))));
  }
  const cc = st.claudeCode || {};
  panel.append(list,
    st.problems && st.problems.length ? h('p', { style: 'color:var(--red)' }, '⚠️ ', st.problems.join(' ')) : null,
    h('table', { style: 'margin-top:10px' }, h('tbody', {},
      h('tr', {}, h('td', {}, 'API-nyckel (ANTHROPIC_API_KEY)'), h('td', {}, ok(st.apiKey))),
      h('tr', {}, h('td', {}, 'Claude Code-token (CLAUDE_CODE_OAUTH_TOKEN)'), h('td', {}, ok(st.token))),
      h('tr', {}, h('td', {}, 'Claude Code-programmet'), h('td', {}, st.cliVersion ? `✅ ${st.cliVersion}` : '❌ hittas inte')),
      h('tr', {}, h('td', {}, 'Anrop via abonnemanget sedan start'), h('td', {}, `${cc.ok || 0} lyckade, ${cc.failed || 0} misslyckade`)),
      h('tr', {}, h('td', {}, 'Senast lyckat via abonnemanget'), h('td', {}, when(cc.lastOk))),
      cc.lastError ? h('tr', {}, h('td', {}, 'Senaste fel'), h('td', {}, `${when(cc.lastFailure)}: ${cc.lastError}`)) : null,
      h('tr', {}, h('td', {}, 'Föll tillbaka till API-nyckeln'), h('td', {}, `${st.fallbacks || 0} gånger sedan start`)))),
    h('p', { class: 'item-meta' }, 'Valet sparas och gäller direkt, även efter omstart. Hur mycket av Pro-abonnemangets gräns som gått åt syns inte här – det ser du i Claude-appen under Inställningar → Användning.'));
  return panel;
}

// ----- Flashkort -----

views.kort = async (main) => {
  main.append(h('h1', {}, 'Flashkort'),
    h('p', { class: 'sub' }, 'Skapa kortlekar för hand, låt Claude göra dem från ett ämne eller ett foto, och öva här eller på Tab5.'));
  const forms = h('div');
  const photoInput = h('input', { type: 'file', accept: FILE_ACCEPT, style: 'display:none', onchange: async () => {
    const file = photoInput.files[0];
    if (!file) return;
    const fd = new FormData(); fd.append('image', file);
    const r = await run(() => api(`/api/flashcards/photo?deviceId=${enc(state.deviceId)}`, { method: 'POST', form: fd }), photoBtn);
    if (r) { toast(`"${r.deck.name}": ${r.cardCount} kort`); render(); }
    photoInput.value = '';
  } });
  const photoBtn = h('button', { onclick: () => photoInput.click() }, '📎 Från fil (foto, Word, PDF)');
  main.append(h('div', { class: 'toolbar' },
    h('button', { class: 'primary', onclick: () => toggleForm(forms, generateForm) }, '✨ Generera med Claude'),
    h('button', { onclick: () => toggleForm(forms, manualForm) }, '+ Skriv egen lek'),
    photoBtn, photoInput), forms);
  const deckPanel = h('section', { class: 'panel' });
  const detail = h('div', { style: 'margin-top:16px' });
  main.append(deckPanel, detail);

  async function render() {
    const decks = await api('/api/flashcards/decks');
    if (!decks.length) { deckPanel.replaceChildren(h('div', { class: 'empty' }, 'Inga kortlekar än.')); return; }
    deckPanel.replaceChildren(h('table', {},
      h('thead', {}, h('tr', {}, ['Lek', 'Ämne', 'Kort', 'Att repetera', 'Sitter', ''].map(t => h('th', {}, t)))),
      h('tbody', {}, decks.map(d => h('tr', {},
        h('td', {}, d.deck.name), h('td', {}, d.deck.subject || '–'), h('td', {}, d.cards),
        h('td', {}, d.dueNow), h('td', {}, d.learned),
        h('td', { style: 'white-space:nowrap' },
          h('button', { class: 'small', onclick: () => showDeck(d.deck) }, 'Kort'), ' ',
          h('button', { class: 'small primary', onclick: () => quiz(d.deck) }, 'Öva'), ' ',
          h('button', { class: 'small', onclick: () => exportDeck(d.deck) }, 'Anki'), ' ',
          h('button', { class: 'small', title: 'Börja om: alla kort till fack 1', onclick: async () => {
            if (!confirm(`Nollställa framstegen i "${d.deck.name}"? Alla kort börjar om i fack 1.`)) return;
            const r = await run(() => api(`/api/flashcards/decks/${d.deck.id}/reset`, { method: 'POST' }), null, 'Kortleken börjar om');
            if (r) render();
          } }, '↺'), ' ',
          h('button', { class: 'small danger', onclick: async () => {
            if (!confirm(`Ta bort leken "${d.deck.name}"?`)) return;
            await run(() => api(`/api/flashcards/decks/${d.deck.id}`, { method: 'DELETE' }), null, 'Borttagen');
            detail.replaceChildren(); render();
          } }, '✕')))))));
  }

  function generateForm() {
    const topic = h('input', { placeholder: 'T.ex. Engelska glosor kapitel 3, eller Fotosyntesen' });
    const subject = h('input', { placeholder: 'Ämne' });
    const count = h('input', { type: 'number', value: 12, min: 3, max: 40 });
    const source = h('textarea', { rows: 5, placeholder: 'Valfritt: klistra in ordlistan eller anteckningarna som korten ska bygga på' });
    return h('div', { class: 'panel', style: 'margin-bottom:16px' }, h('h2', {}, 'Generera kortlek'),
      h('div', { class: 'row' }, h('div', {}, h('label', {}, 'Område'), topic), h('div', {}, h('label', {}, 'Ämne'), subject),
        h('div', {}, h('label', {}, 'Antal kort'), count)),
      h('label', {}, 'Material'), source,
      h('div', { class: 'toolbar', style: 'margin-top:12px' },
        h('button', { class: 'primary', onclick: async (e) => {
          if (!topic.value.trim() && !source.value.trim()) return toast('Ange område eller material', true);
          const r = await run(() => api(`/api/flashcards/generate?deviceId=${enc(state.deviceId)}`, { method: 'POST',
            body: { topic: topic.value, subject: subject.value, sourceText: source.value, count: Number(count.value) } }), e.target);
          if (r) { toast(`"${r.deck.name}": ${r.cardCount} kort`); forms.replaceChildren(); render(); showDeck(r.deck); }
        } }, 'Generera'),
        h('button', { onclick: () => forms.replaceChildren() }, 'Avbryt')));
  }

  function manualForm() {
    const name = h('input', { placeholder: 'T.ex. Tyska glosor v.48' });
    const subject = h('input', { placeholder: 'Ämne' });
    const cards = h('textarea', { rows: 8, placeholder: 'En rad per kort: framsida ; baksida ; ledtråd (valfri)\nhund ; der Hund\nkatt ; die Katze ; feminin' });
    return h('div', { class: 'panel', style: 'margin-bottom:16px' }, h('h2', {}, 'Egen kortlek'),
      h('div', { class: 'row' }, h('div', {}, h('label', {}, 'Namn'), name), h('div', {}, h('label', {}, 'Ämne'), subject)),
      h('label', {}, 'Kort'), cards,
      h('div', { class: 'toolbar', style: 'margin-top:12px' },
        h('button', { class: 'primary', onclick: async (e) => {
          const parsed = lines(cards.value).map(l => l.split(/\s*[;\t]\s*/))
            .filter(p => p.length >= 2).map(([front, back, hint]) => ({ front, back, hint: hint || null }));
          if (!name.value.trim() || !parsed.length) return toast('Ange namn och minst ett kort (fram ; bak)', true);
          const deck = await run(() => api('/api/flashcards/decks', { method: 'POST',
            body: { name: name.value, subject: subject.value || null, cards: parsed } }), e.target, 'Sparad');
          if (deck) { forms.replaceChildren(); render(); showDeck(deck); }
        } }, 'Spara'),
        h('button', { onclick: () => forms.replaceChildren() }, 'Avbryt')));
  }

  async function showDeck(deck) {
    const cards = await api(`/api/flashcards/decks/${deck.id}/cards`);
    const tbody = h('tbody');
    const row = (c) => {
      const front = h('input', { value: c.front }), back = h('input', { value: c.back }), hint = h('input', { value: c.hint || '' });
      return h('tr', {}, h('td', {}, front), h('td', {}, back), h('td', {}, hint), h('td', {}, `fack ${c.box}`),
        h('td', { style: 'white-space:nowrap' },
          h('button', { class: 'small', onclick: (e) => run(() => api(`/api/flashcards/cards/${c.id}`, { method: 'PUT',
            body: { front: front.value, back: back.value, hint: hint.value } }), e.target, 'Sparat') }, 'Spara'), ' ',
          h('button', { class: 'small danger', onclick: async (e) => {
            const r = await run(() => api(`/api/flashcards/cards/${c.id}`, { method: 'DELETE' }), e.target);
            if (r !== undefined) e.target.closest('tr').remove();
          } }, '✕')));
    };
    cards.forEach(c => tbody.append(row(c)));
    const nf = h('input', { placeholder: 'Framsida' }), nb = h('input', { placeholder: 'Baksida' }), nh = h('input', { placeholder: 'Ledtråd' });
    detail.replaceChildren(h('section', { class: 'panel' }, h('h2', {}, `${deck.name} – ${cards.length} kort`),
      h('table', {}, h('thead', {}, h('tr', {}, ['Framsida', 'Baksida', 'Ledtråd', '', ''].map(t => h('th', {}, t)))), tbody,
        h('tfoot', {}, h('tr', {}, h('td', {}, nf), h('td', {}, nb), h('td', {}, nh), h('td'),
          h('td', {}, h('button', { class: 'small primary', onclick: async (e) => {
            if (!nf.value.trim() || !nb.value.trim()) return;
            const added = await run(() => api(`/api/flashcards/decks/${deck.id}/cards`, { method: 'POST',
              body: [{ front: nf.value, back: nb.value, hint: nh.value || null }] }), e.target);
            if (added) { added.forEach(c => tbody.append(row(c))); nf.value = nb.value = nh.value = ''; nf.focus(); }
          } }, '+ Lägg till')))))));
    detail.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  async function exportDeck(deck) {
    const res = await run(() => api(`/api/flashcards/decks/${deck.id}/export.tsv`, { raw: true }));
    if (!res || !res.ok) return;
    const blob = await res.blob();
    const a = h('a', { href: URL.createObjectURL(blob), download: `${deck.name}.tsv` });
    document.body.append(a); a.click(); a.remove();
  }

  async function quiz(deck) {
    const area = h('div', { class: 'quiz-card' });
    const controls = h('div', { class: 'toolbar', style: 'justify-content:center' });
    const status = h('div', { class: 'item-meta', style: 'text-align:center' });
    let score = { done: 0, right: 0 };
    detail.replaceChildren(h('section', { class: 'panel' }, h('h2', {}, `Öva: ${deck.name}`), area, controls, status));
    detail.scrollIntoView({ behavior: 'smooth', block: 'start' });

    async function next() {
      const res = await api(`/api/flashcards/decks/${deck.id}/next?practiceAnyway=true&deviceId=${enc(state.deviceId)}`, { raw: true });
      if (res.status === 404) { area.replaceChildren('Inga kort i leken.'); controls.replaceChildren(); return; }
      const card = await res.json();
      area.replaceChildren(card.front, card.hint ? h('div', { class: 'item-meta', style: 'font-size:14px;margin-top:8px' }, '') : null);
      const hintEl = area.querySelector('.item-meta');
      controls.replaceChildren(
        card.hint ? h('button', { onclick: () => { if (hintEl) hintEl.textContent = `Ledtråd: ${card.hint}`; } }, 'Ledtråd') : null,
        h('button', { class: 'primary', onclick: () => reveal(card) }, 'Visa svar'));
    }
    function reveal(card) {
      area.append(h('div', { class: 'answer' }, card.back));
      controls.replaceChildren(
        h('button', { onclick: () => grade(card, false) }, '✗ Fel'),
        h('button', { class: 'primary', onclick: () => grade(card, true) }, '✓ Rätt'));
    }
    async function grade(card, correct) {
      await run(() => api(`/api/flashcards/cards/${card.id}/grade?deviceId=${enc(state.deviceId)}`, { method: 'POST', body: { correct } }));
      score.done++; if (correct) score.right++;
      status.textContent = `${score.right} rätt av ${score.done}`;
      next();
    }
    next();
  }

  await render();
};

// ----- Poäng -----

views.poang = async (main) => {
  main.append(h('h1', {}, 'Poäng och veckopeng'));
  const base = `/api/devices/${enc(state.deviceId)}/rewards`;
  const [week, events, payouts] = await Promise.all([api(base), api(`${base}/events`), api(`${base}/payouts`)]);
  const perPoint = Number(week.sekPerPoint || 0);
  main.append(h('p', { class: 'sub' },
    perPoint > 0 ? `${Math.round(1 / perPoint)} poäng = 1 kr` : 'Ingen växelkurs satt', week.weeklyCapSek ? ` · tak ${kr(week.weeklyCapSek)} per vecka` : '',
    ' · ändras med PLUGGIS_SEK_PER_POINT och PLUGGIS_WEEKLY_CAP_SEK'));
  main.append(h('div', { class: 'grid cols-4' },
    kpi('Poäng denna vecka', week.weekPoints, `vecka från ${fmtDay(week.weekStart)}`),
    kpi('Intjänat', kr(week.earnedSek)), kpi('Utbetalt', kr(week.paidSek)), kpi('Kvar att betala', kr(week.unpaidSek)),
    kpi('Streak', `${week.streakDays} d`)));

  const bonusPts = h('input', { type: 'number', min: 1, value: 20 });
  const bonusReason = h('input', { placeholder: 'T.ex. Bra jobbat på provet!' });
  const payAmount = h('input', { type: 'number', step: '0.5', placeholder: String(week.unpaidSek) });
  main.append(h('div', { class: 'grid cols-2', style: 'margin-top:16px' },
    h('section', { class: 'panel' }, h('h2', {}, 'Ge bonuspoäng'),
      h('div', { class: 'row' }, h('div', {}, h('label', {}, 'Poäng'), bonusPts), h('div', {}, h('label', {}, 'Anledning'), bonusReason)),
      h('div', { class: 'toolbar', style: 'margin-top:12px' }, h('button', { class: 'primary', onclick: async (e) => {
        const r = await run(() => api(`${base}/bonus`, { method: 'POST', body: { points: Number(bonusPts.value), reason: bonusReason.value || null } }), e.target, 'Bonus utdelad');
        if (r) route();
      } }, 'Ge bonus'))),
    h('section', { class: 'panel' }, h('h2', {}, 'Markera utbetalning'),
      h('label', {}, 'Belopp (tomt = hela det obetalda)'), payAmount,
      h('div', { class: 'toolbar', style: 'margin-top:12px' }, h('button', { class: 'primary', onclick: async (e) => {
        const amount = payAmount.value ? Number(payAmount.value) : null;
        const r = await run(() => api(`${base}/payouts`, { method: 'POST', body: { amountSek: amount, note: 'Utbetalt via webben' } }), e.target, 'Utbetalning sparad');
        if (r) route();
      } }, 'Markera utbetalt')))));

  main.append(h('div', { class: 'grid cols-2', style: 'margin-top:16px' },
    h('section', { class: 'panel' }, h('h2', {}, 'Senaste poängen'),
      events.length ? h('table', {}, h('tbody', {}, events.map(e => h('tr', {},
        h('td', {}, fmtDay(e.awardedOn)), h('td', {}, e.reason), h('td', { style: 'text-align:right;font-weight:600' }, `+${e.points}`),
        h('td', { style: 'width:1%' }, h('button', { class: 'small danger', title: 'Ta bort de här poängen', onclick: async (ev) => {
          if (!confirm(`Ta bort ${e.points} poäng (${e.reason || 'poäng'})?`)) return;
          const r = await run(() => api(`${base}/events/${e.id}`, { method: 'DELETE' }), ev.target, 'Borttaget');
          if (r !== undefined) route();
        } }, '✕'))))))
        : h('div', { class: 'empty' }, 'Inga poäng än.')),
    h('section', { class: 'panel' }, h('h2', {}, 'Utbetalningar'),
      payouts.length ? h('table', {}, h('tbody', {}, payouts.map(p => h('tr', {},
        h('td', {}, new Date(p.paidAt).toLocaleDateString('sv-SE')), h('td', {}, `vecka ${fmtDay(p.weekStart)}`),
        h('td', { style: 'text-align:right' }, kr(p.amountSek))))))
        : h('div', { class: 'empty' }, 'Inga utbetalningar än.'))));

  // Nollställ
  const alsoPayouts = h('input', { type: 'checkbox' });
  const reset = (scope) => async (e) => {
    const what = scope === 'all' ? 'ALLA poäng (även streaken)' : 'den här veckans poäng';
    if (!confirm(`Nollställa ${what}${alsoPayouts.checked ? ' och utbetalningarna' : ''}? Det går inte att ångra.`)) return;
    const r = await run(() => api(`${base}/reset`, { method: 'POST', body: { scope, payouts: alsoPayouts.checked } }), e.target);
    if (r) { toast(`Nollställt – ${r.removed} poänghändelser borttagna`); route(); }
  };
  main.append(h('section', { class: 'panel', style: 'margin-top:16px' }, h('h2', {}, 'Nollställ'),
    h('p', { class: 'item-meta' }, 'Till exempel efter att ha testat, eller för att börja om en ny period. Enskilda poäng tar du bort med ✕ ovan.'),
    h('label', { style: 'display:flex;gap:6px;align-items:center' }, alsoPayouts, 'Ta även bort utbetalningarna för samma period'),
    h('div', { class: 'toolbar', style: 'margin-top:10px' },
      h('button', { onclick: reset('week') }, 'Nollställ veckans poäng'),
      h('button', { class: 'danger', onclick: reset('all') }, 'Nollställ alla poäng'))));
};

// ----- Tab5-simulator -----

views.simulator = async (main) => {
  main.append(h('h1', {}, 'Tab5-simulator'),
    h('p', { class: 'sub' }, `Testa coachen som om du satt vid Tab5. Ansluter som "${state.deviceId}" – riktiga Tab5 och elevchatten med samma id ser samma samtal.`));

  const stateLine = h('div', { class: 'state' }, 'Inte ansluten');
  const timer = h('div', { class: 'timerbar', style: 'display:none' }, h('div', { style: 'width:100%' }));
  const timerText = h('div', { class: 'state', style: 'display:none' });
  const say = h('div', { class: 'say' }, 'Tryck Anslut för att börja.');
  const cardBox = h('div');
  const screen = h('div', { class: 'screen' }, stateLine, timer, timerText, cardBox, say);
  const log = h('div', { class: 'log' });
  const input = h('input', { placeholder: 'Säg något till coachen… (Enter för att skicka)', onkeydown: (e) => { if (e.key === 'Enter') sendText(); } });
  const photoInput = h('input', { type: 'file', accept: 'image/*', style: 'display:none' });
  const connectBtn = h('button', { class: 'primary', onclick: () => (state.ws ? disconnect() : connect()) }, 'Anslut');
  const speakBox = h('input', { type: 'checkbox', checked: state.speak, onchange: () => { state.speak = speakBox.checked; store.set('lc.speak', state.speak ? '1' : '0'); } });
  const Recognition = window.SpeechRecognition || window.webkitSpeechRecognition;
  const micBtn = Recognition ? h('button', { onclick: listen, title: 'Prata (webbläsarens taligenkänning)' }, '🎤') : null;
  const serverVoice = h('input', { type: 'checkbox', checked: store.get('lc.serverVoice', '0') === '1',
    onchange: () => { store.set('lc.serverVoice', serverVoice.checked ? '1' : '0'); sendHello(); } });
  const pttBtn = h('button', { title: 'Håll inne och prata – skickas till serverns KB-Whisper' }, '🎙 Håll och prata');
  let recorder = null, chunks = [], pendingAudio = null;
  const audioQueue = [];
  let playing = false;

  const press = (button, gesture) => send({ type: 'button', button, gesture });
  main.append(h('div', { class: 'sim' },
    h('div', {},
      h('div', { class: 'device' }, screen,
        h('div', { class: 'hw-buttons' },
          h('button', { class: 'hw blue', onclick: () => press('main', 'click'), title: 'Börja / hjälp' }, 'Blå'),
          h('button', { class: 'hw red', onclick: () => press('photo', 'click'), title: 'Fota uppgift' }, 'Röd'))),
      h('div', { class: 'toolbar', style: 'margin-top:10px;justify-content:center' },
        h('button', { class: 'small', onclick: () => press('main', 'long') }, 'Blå långt (avsluta)'),
        h('button', { class: 'small', onclick: () => press('main', 'double') }, 'Blå dubbel (hjälp)'),
        h('button', { class: 'small', onclick: () => press('photo', 'long') }, 'Röd långt (kort från foto)')),
      h('div', { class: 'sim-input' }, input, micBtn, h('button', { onclick: sendText }, 'Skicka')),
      h('div', { class: 'toolbar', style: 'margin-top:10px' }, connectBtn, pttBtn,
        h('label', { style: 'display:flex;gap:6px;align-items:center;margin:0' }, speakBox, 'Läs upp (webbläsaren)'),
        h('label', { style: 'display:flex;gap:6px;align-items:center;margin:0' }, serverVoice, 'Serverns röst (KB-Whisper + Piper)'),
        photoInput)),
    h('div', {}, h('h3', {}, 'Meddelanden'), log)));

  function logLine(dir, obj) {
    log.append(h('div', { class: dir }, `${dir === 'in' ? '←' : '→'} ${JSON.stringify(obj)}`));
    log.scrollTop = log.scrollHeight;
  }
  function send(obj) {
    if (!state.ws || state.ws.readyState !== 1) return toast('Inte ansluten', true);
    state.ws.send(JSON.stringify(obj));
    logLine('out', obj);
  }
  function sendText() {
    const text = input.value.trim();
    if (!text) return;
    send({ type: 'text', text });
    input.value = '';
  }
  function sendHello() {
    if (state.ws && state.ws.readyState === 1) send({ type: 'hello', audio: serverVoice.checked, audioFormat: 'webm' });
  }

  // Tryck-och-prata: spelar in i webbläsaren och skickar som binär ram (webm/opus) till servern.
  async function startRecording() {
    if (!state.ws || state.ws.readyState !== 1) return toast('Inte ansluten', true);
    if (!navigator.mediaDevices || !window.MediaRecorder) return toast('Webbläsaren kan inte spela in', true);
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      chunks = [];
      recorder = new MediaRecorder(stream);
      recorder.ondataavailable = (e) => { if (e.data.size) chunks.push(e.data); };
      recorder.onstop = async () => {
        stream.getTracks().forEach(t => t.stop());
        const blob = new Blob(chunks, { type: recorder.mimeType || 'audio/webm' });
        if (blob.size < 2000) return;
        state.ws.send(await blob.arrayBuffer());
        logLine('out', { binary: `${Math.round(blob.size / 1024)} kB ljud` });
      };
      recorder.start();
      pttBtn.textContent = '● Spelar in…';
    } catch {
      toast('Fick inte använda mikrofonen', true);
    }
  }
  function stopRecording() {
    if (recorder && recorder.state === 'recording') recorder.stop();
    pttBtn.textContent = '🎙 Håll och prata';
  }
  pttBtn.addEventListener('pointerdown', startRecording);
  pttBtn.addEventListener('pointerup', stopRecording);
  pttBtn.addEventListener('pointerleave', stopRecording);

  function playNext() {
    if (playing || !audioQueue.length) return;
    playing = true;
    const url = URL.createObjectURL(audioQueue.shift());
    const a = new Audio(url);
    a.onended = a.onerror = () => { URL.revokeObjectURL(url); playing = false; playNext(); };
    a.play().catch(() => { playing = false; });
  }

  function speakText(text) {
    if (serverVoice.checked) return; // servern läser upp
    if (!state.speak || !window.speechSynthesis) return;
    const u = new SpeechSynthesisUtterance(text);
    u.lang = 'sv-SE';
    const voice = speechSynthesis.getVoices().find(v => v.lang && v.lang.startsWith('sv'));
    if (voice) u.voice = voice;
    speechSynthesis.cancel();
    speechSynthesis.speak(u);
  }
  function listen() {
    const rec = new Recognition();
    rec.lang = 'sv-SE';
    rec.interimResults = false;
    micBtn.textContent = '…';
    rec.onresult = (e) => { input.value = e.results[0][0].transcript; sendText(); };
    rec.onend = () => { micBtn.textContent = '🎤'; };
    rec.onerror = () => toast('Kunde inte höra – testa igen', true);
    rec.start();
  }

  function connect() {
    const proto = location.protocol === 'https:' ? 'wss' : 'ws';
    const url = `${proto}://${location.host}/ws/device?deviceId=${enc(state.deviceId)}&token=${enc(state.token)}`;
    const ws = new WebSocket(url);
    ws.binaryType = 'arraybuffer';
    state.ws = ws;
    stateLine.textContent = 'Ansluter…';
    ws.onopen = () => { sendHello(); stateLine.textContent = 'Ansluten – redo'; connectBtn.textContent = 'Koppla ner'; say.textContent = 'Tryck på blå knappen för att börja, eller skriv något.'; };
    ws.onclose = (e) => {
      stateLine.textContent = e.code === 1008 ? 'Nekad – kontrollera token' : 'Frånkopplad';
      connectBtn.textContent = 'Anslut';
      if (state.ws === ws) state.ws = null;
    };
    ws.onmessage = (e) => {
      if (typeof e.data !== 'string') {
        // Binär ram = ljudet till det senaste "audio"-meddelandet
        audioQueue.push(new Blob([e.data], { type: 'audio/wav' }));
        logLine('in', { binary: `${Math.round(e.data.byteLength / 1024)} kB ljud`, sentence: pendingAudio && pendingAudio.text });
        pendingAudio = null;
        playNext();
        return;
      }
      let msg;
      try { msg = JSON.parse(e.data); } catch { return; }
      logLine('in', msg);
      handle(msg);
    };
  }
  function disconnect() { if (state.ws) state.ws.close(); }

  function handle(msg) {
    switch (msg.type) {
      case 'thinking':
        say.replaceChildren(h('span', { class: 'thinking' }, 'Tänker'));
        break;
      case 'say':
        say.textContent = msg.text;
        speakText(msg.text);
        break;
      case 'reminder':
        say.replaceChildren(h('strong', {}, '🔔 '), msg.text);
        speakText(msg.text);
        break;
      case 'timer': {
        const pct = msg.totalSeconds ? Math.round(100 * msg.remainingSeconds / msg.totalSeconds) : 0;
        timer.style.display = timerText.style.display = '';
        timer.firstChild.style.width = pct + '%';
        timerText.textContent = `${msg.task} · ${Math.ceil(msg.remainingSeconds / 60)} min kvar`;
        break;
      }
      case 'session':
        stateLine.textContent = { started: `Pass igång: ${msg.task} (${msg.minutes} min)`, time_up: 'Tiden är ute!', finished: 'Passet är avslutat' }[msg.state] || msg.state;
        if (msg.state === 'finished') { timer.style.display = timerText.style.display = 'none'; }
        if (msg.state === 'started') { timer.style.display = timerText.style.display = ''; timer.firstChild.style.width = '100%'; }
        break;
      case 'card':
        cardBox.replaceChildren(h('div', { class: 'card' }, msg.front, msg.hint ? h('div', { class: 'hint' }, 'Tips finns – fråga om ledtråd') : null));
        break;
      case 'card_answer':
        cardBox.replaceChildren(h('div', { class: 'card ' + (msg.correct ? 'right' : 'wrong') }, msg.back,
          h('div', { class: 'hint' }, `${msg.correct ? 'Rätt!' : 'Inte riktigt'} · fack ${msg.box}`)));
        break;
      case 'points':
        screen.append(h('div', { class: 'points-pop' }, `+${msg.delta}`));
        setTimeout(() => screen.querySelector('.points-pop')?.remove(), 1900);
        break;
      case 'take_photo':
        say.textContent = 'Välj en bild (i riktiga Tab5 räknar den ner och fotar)…';
        photoInput.onchange = async () => {
          const file = photoInput.files[0];
          if (!file) return;
          const fd = new FormData(); fd.append('image', file);
          say.replaceChildren(h('span', { class: 'thinking' }, 'Läser bilden'));
          await run(() => api(`${msg.uploadPath}?deviceId=${enc(state.deviceId)}`, { method: 'POST', form: fd }));
          photoInput.value = '';
        };
        photoInput.click();
        break;
      case 'heard':
        say.replaceChildren(h('em', {}, `Du sa: ”${msg.text}”`), h('br'), h('span', { class: 'thinking' }, 'Tänker'));
        break;
      case 'audio':
        pendingAudio = msg;
        break;
      case 'hello_ack':
      case 'audio_end':
      case 'pong':
        break;
      case 'error':
        toast(msg.message, true);
        break;
      default:
        break;
    }
  }

  // Lämnar man vyn kopplas simulatorn ner, så att riktiga Tab5 kan ta över igen.
  state.leaveView = disconnect;
};

// ----- Logg -----

views.logg = async (main) => {
  const level = h('select', {}, ['DEBUG', 'INFO', 'WARN', 'ERROR'].map(l => h('option', { value: l }, l)));
  level.value = store.get('lc.logLevel', 'INFO');
  const search = h('input', { placeholder: 'Sök i loggen…', style: 'max-width:260px' });
  const pause = h('input', { type: 'checkbox' });
  const view = h('div', { class: 'logview', role: 'log', 'aria-live': 'off' });
  const status = h('span', { class: 'item-meta' }, '');
  let after = 0;
  let timer = null;
  let searchTimer = null;

  const fmtTime = (iso) => {
    const d = new Date(iso);
    return d.toLocaleTimeString('sv-SE', { hour12: false }) + '.' + String(d.getMilliseconds()).padStart(3, '0');
  };

  function reset() {
    after = 0;
    view.replaceChildren();
    poll();
  }

  async function poll() {
    if (pause.checked) return;
    try {
      const q = search.value.trim();
      const data = await api(`/api/logs?after=${after}&level=${level.value}&limit=500${q ? '&q=' + enc(q) : ''}`);
      const atBottom = view.scrollHeight - view.scrollTop - view.clientHeight < 40;
      for (const l of data.lines) {
        view.append(h('span', { class: 'logline ' + l.level },
          h('span', { class: 't' }, fmtTime(l.time)), ' ',
          h('span', { class: 'lv' }, l.level), ' ',
          h('span', { class: 'lg' }, l.logger), '  ',
          l.message));
      }
      while (view.childElementCount > 2000) view.firstChild.remove();
      after = Math.max(after, data.last);
      if (atBottom && data.lines.length) view.scrollTop = view.scrollHeight;
      status.textContent = `${view.childElementCount} rader · uppdaterad ${new Date().toLocaleTimeString('sv-SE')}`;
    } catch (e) {
      status.textContent = e.message === '401' ? 'Ange token under Inställningar' : `Fel: ${e.message}`;
    }
  }

  level.addEventListener('change', () => { store.set('lc.logLevel', level.value); reset(); });
  search.addEventListener('input', () => { clearTimeout(searchTimer); searchTimer = setTimeout(reset, 300); });
  pause.addEventListener('change', () => { if (!pause.checked) poll(); });

  main.append(h('h1', {}, 'Logg'),
    h('p', { class: 'sub' }, 'Backendens senaste 2 000 loggrader, uppdateras varannan sekund. Ljudtjänstens logg syns med docker compose logs speech.'),
    h('div', { class: 'toolbar' },
      h('label', { style: 'margin:0' }, 'Nivå '), level, search,
      h('label', { style: 'display:flex;gap:6px;align-items:center;margin:0' }, pause, 'Pausa'),
      h('button', { onclick: () => view.replaceChildren() }, 'Rensa vyn'),
      h('button', { onclick: () => { view.scrollTop = view.scrollHeight; } }, '↓ Till slutet'),
      h('span', { class: 'spacer' }), status),
    view);

  await poll();
  view.scrollTop = view.scrollHeight;
  timer = setInterval(poll, 2000);
  state.leaveView = () => clearInterval(timer);
};

// ----- Inställningar -----

views.installningar = async (main) => {
  const token = h('input', { type: 'password', value: state.token, placeholder: 'PLUGGIS_DEVICE_TOKEN' });
  const result = h('div', { class: 'item-meta', style: 'margin-top:10px' });
  main.append(h('h1', {}, 'Inställningar'),
    h('section', { class: 'panel' },
      h('h2', {}, 'Anslutning'),
      h('label', {}, 'Token (samma som PLUGGIS_DEVICE_TOKEN)'), token,
      h('div', { class: 'toolbar', style: 'margin-top:12px' },
        h('button', { class: 'primary', onclick: async (e) => {
          state.token = token.value.trim();
          store.set('lc.token', state.token);
          const r = await run(() => api('/api/devices'), e.target);
          if (r) result.textContent = `Sparat. Anslutna enheter: ${r.connected.length ? r.connected.join(', ') : 'inga'}`;
          await loadStudents();
          pollDevice();
        } }, 'Spara och testa')),
      result,
      h('p', { class: 'item-meta' }, 'Token sparas bara i den här webbläsaren.')),
    studentsPanel(),
    studentPanel(),
    planningPanel(),
    googlePanel(),
    voicePanel(),
    h('section', { class: 'panel', style: 'margin-top:16px' },
      h('h2', {}, 'iOS-genväg: skärmdump från InfoMentor'),
      h('ol', {},
        h('li', {}, 'Genvägar → ny genväg → slå på "Visa i delningsblad", ta emot Bilder.'),
        h('li', {}, 'Lägg till "Hämta innehåll från URL": ', h('code', {}, `${location.origin}/api/homework/photo?deviceId=${state.deviceId}`)),
        h('li', {}, 'Metod POST, Rubriker: X-Pluggis-Token = din token, Begärans brödtext: Formulär, fält "image" = Genvägsindata.'),
        h('li', {}, 'Lägg till "Visa resultat". Dela sedan skärmdumpen av veckoplanen till genvägen.'))));
};

function studentsPanel() {
  const panel = h('section', { class: 'panel', style: 'margin-top:16px' }, h('h2', {}, 'Elever'),
    h('div', { class: 'empty' }, h('span', { class: 'spinner' })));
  async function paint() {
    let list;
    try { list = await api('/api/students'); } catch (e) { panel.replaceChildren(h('h2', {}, 'Elever'), h('div', { class: 'empty' }, e.message)); return; }
    state.students = list;
    const chatUrl = `${location.origin}/chat.html`;
    const rows = list.map(s => {
      const name = h('input', { value: s.name || '', placeholder: 'Namn' });
      const device = h('input', { value: s.deviceId || '', placeholder: 'Enhets-id' });
      const link = `${chatUrl}#k=${s.code}`;
      return h('div', { class: 'student-row' },
        h('div', { class: 'row' },
          h('div', {}, h('label', {}, 'Namn'), name),
          h('div', {}, h('label', {}, 'Enhets-id (Tab5)'), device),
          h('div', {}, h('label', {}, 'Kod till mobilchatten'), h('div', { class: 'code' }, s.code))),
        h('div', { class: 'toolbar', style: 'margin-top:8px' },
          h('button', { class: 'small primary', onclick: async (e) => {
            const r = await run(() => api(`/api/students/${s.id}`, { method: 'PUT', body: { name: name.value, deviceId: device.value } }), e.target, 'Sparat');
            if (r) { await loadStudents(); paint(); }
          } }, 'Spara'),
          h('button', { class: 'small', onclick: async () => { try { await navigator.clipboard.writeText(link); toast('Länk med kod kopierad'); } catch { toast(link); } } }, '📋 Kopiera länk'),
          navigator.share ? h('button', { class: 'small', onclick: () => navigator.share({ title: 'Pluggis', text: `Din kod till Pluggis: ${s.code}`, url: link }).catch(() => {}) }, '📤 Dela') : null,
          h('button', { class: 'small', onclick: async (e) => {
            if (!confirm(`Ny kod för ${s.name || 'eleven'}? Den gamla slutar fungera.`)) return;
            const r = await run(() => api(`/api/students/${s.id}/code`, { method: 'POST' }), e.target, 'Ny kod');
            if (r) paint();
          } }, '🔄 Ny kod'),
          h('span', { class: 'spacer' }),
          list.length > 1 ? h('button', { class: 'small danger', onclick: async (e) => {
            if (!confirm(`Ta bort ${s.name || 'eleven'} med alla läxor, planer och flashkort? Det går inte att ångra.`)) return;
            const r = await run(() => api(`/api/students/${s.id}`, { method: 'DELETE' }), e.target, 'Borttagen');
            if (r !== undefined) { await loadStudents(); paint(); route(); }
          } }, 'Ta bort') : null));
    });
    const newName = h('input', { placeholder: 'Namn, t.ex. Lisa' });
    const newDevice = h('input', { placeholder: 'Enhets-id (valfritt)' });
    panel.replaceChildren(h('h2', {}, 'Elever'),
      h('p', { class: 'item-meta' }, 'Varje elev har egna läxor, plan, flashkort, kalender, ton och poäng. Välj elev uppe till vänster. ',
        'Eleven öppnar ', h('code', {}, chatUrl), ' på mobilen och skriver in sin kod (eller får länken med koden).'),
      ...rows,
      h('h3', { style: 'margin-top:16px' }, 'Lägg till elev'),
      h('div', { class: 'row' }, h('div', {}, h('label', {}, 'Namn'), newName), h('div', {}, h('label', {}, 'Enhets-id (valfritt)'), newDevice)),
      h('p', { class: 'item-meta' }, 'Enhets-id är det id elevens Tab5 ansluter med. Har eleven ingen Tab5 kan du lämna det tomt.'),
      h('div', { class: 'toolbar' }, h('button', { class: 'primary', onclick: async (e) => {
        if (!newName.value.trim()) return toast('Ange ett namn', true);
        const r = await run(() => api('/api/students', { method: 'POST', body: { name: newName.value, deviceId: newDevice.value } }), e.target, 'Elev tillagd');
        if (r) { await loadStudents(); paint(); }
      } }, '+ Lägg till elev')));
  }
  paint();
  return panel;
}

function googlePanel() {
  const panel = h('section', { class: 'panel', style: 'margin-top:16px' }, h('h2', {}, 'Google Kalender'),
    h('div', { class: 'empty' }, h('span', { class: 'spinner' })));
  async function paint(st) {
    if (!st) {
      try { st = await api('/api/google-calendar'); } catch (e) { panel.replaceChildren(h('h2', {}, 'Google Kalender'), h('div', { class: 'empty' }, e.message)); return; }
    }
    const who = (state.students.find(x => String(x.id) === String(state.studentId)) || {}).name || 'eleven';
    const body = [h('h2', {}, `Google Kalender för ${who}`),
      h('p', { class: 'item-meta' }, 'Planen läggs in som ett pass per dag vid pluggtiden, med dagens moment i beskrivningen och en notis när det är dags. Avbockningar syns i kalendern efter en liten stund.')];
    if (!st.enabled) {
      body.push(h('div', { class: 'empty', style: 'text-align:left' },
        'Inte påslaget än. Skapa ett tjänstekonto i Google Cloud, ladda ner JSON-nyckeln och lägg den som ',
        h('code', {}, 'secrets/google-sa.json'), ' bredvid docker-compose.yml. Starta sedan om (', h('code', {}, 'docker compose up -d'), '). Se README → Google Kalender.'));
      panel.replaceChildren(...body);
      return;
    }
    const cal = h('input', { value: st.calendarId || '', placeholder: 'xxxx@group.calendar.google.com eller elevens gmail-adress' });
    body.push(
      h('label', {}, '1. Dela kalendern med den här adressen (”Gör ändringar i händelser”)'),
      h('div', { class: 'toolbar' }, h('code', { class: 'sa' }, st.serviceAccountEmail),
        h('button', { class: 'small', onclick: async () => { try { await navigator.clipboard.writeText(st.serviceAccountEmail); toast('Kopierad'); } catch { toast(st.serviceAccountEmail); } } }, '📋')),
      h('label', {}, '2. Kalender-id (Inställningar för kalendern → Integrera kalender)'), cal,
      h('div', { class: 'toolbar', style: 'margin-top:12px' },
        h('button', { class: 'primary', onclick: async (e) => {
          const r = await run(() => api('/api/google-calendar', { method: 'PUT', body: { calendarId: cal.value } }), e.target);
          if (r) { toast(`Synkat: ${r.result.created} nya, ${r.result.updated} ändrade, ${r.result.deleted} borttagna`); paint(r); }
        } }, st.calendarId ? 'Spara och synka' : 'Koppla och synka'),
        st.calendarId ? h('button', { onclick: async (e) => {
          const r = await run(() => api('/api/google-calendar/sync', { method: 'POST' }), e.target);
          if (r) { toast(`Synkat: ${r.result.created} nya, ${r.result.updated} ändrade, ${r.result.deleted} borttagna`); paint(r); }
        } }, '🔄 Synka nu') : null,
        h('span', { class: 'spacer' }),
        st.calendarId ? h('button', { class: 'danger', onclick: async (e) => {
          if (!confirm('Koppla bort kalendern? Kommande pluggpass tas bort ur den.')) return;
          const r = await run(() => api('/api/google-calendar', { method: 'DELETE' }), e.target, 'Bortkopplad');
          if (r) paint(r);
        } }, 'Koppla bort') : null),
      st.lastError ? h('p', { class: 'item-meta', style: 'color:var(--red)' }, '⚠️ ', st.lastError)
        : st.lastSync ? h('p', { class: 'item-meta' }, `Senast synkad ${new Date(st.lastSync).toLocaleString('sv-SE')}`) : null,
      (st.upcoming || []).length ? h('div', {}, h('label', {}, 'Närmaste passen i kalendern'),
        h('ul', { class: 'gcal-list' }, st.upcoming.map(ev => h('li', {},
          h('span', { class: 'item-meta' }, new Date(ev.start).toLocaleString('sv-SE', { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' })), ' ',
          ev.link ? h('a', { href: ev.link, target: '_blank', rel: 'noopener' }, ev.summary) : ev.summary))))
        : st.calendarId ? h('p', { class: 'item-meta' }, 'Inga kommande pass i kalendern – finns det en plan de närmaste tre veckorna?') : null);
    panel.replaceChildren(...body);
  }
  paint();
  return panel;
}

function planningPanel() {
  const who = () => (state.students.find(x => String(x.id) === String(state.studentId)) || {}).name;
  const panel = h('section', { class: 'panel', style: 'margin-top:16px' }, h('h2', {}, 'Planering'),
    h('div', { class: 'empty' }, h('span', { class: 'spinner' })));
  const DAYS = [['MONDAY', 'Mån'], ['TUESDAY', 'Tis'], ['WEDNESDAY', 'Ons'], ['THURSDAY', 'Tor'], ['FRIDAY', 'Fre'], ['SATURDAY', 'Lör'], ['SUNDAY', 'Sön']];
  (async () => {
    let s;
    try { s = await api('/api/student'); } catch (e) { panel.replaceChildren(h('h2', {}, 'Planering'), h('div', { class: 'empty' }, e.message)); return; }
    const minutes = h('input', { type: 'range', min: '10', max: '120', step: '5', value: String(s.maxMinutesPerDay) });
    const label = h('strong', {}, `${s.maxMinutesPerDay} min`);
    minutes.addEventListener('input', () => { label.textContent = `${minutes.value} min`; });
    const boxes = DAYS.map(([id, txt]) => {
      const cb = h('input', { type: 'checkbox', checked: s.studyDays.includes(id) });
      cb.dataset.day = id;
      return h('label', { class: 'daybox' }, cb, txt);
    });
    const replan = h('input', { type: 'checkbox', checked: true });
    const time = h('input', { type: 'time', value: s.studyTime || '16:30', step: '300' });
    const cue = h('input', { value: s.studyCue || '', placeholder: 'T.ex. efter mellanmålet', maxlength: '80' });
    panel.replaceChildren(h('h2', {}, `Planering för ${who() || s.name || 'eleven'}`),
      h('label', {}, 'Max plugg per dag (alla läxor och prov tillsammans): ', label), minutes,
      h('p', { class: 'item-meta' }, 'För årskurs 8 brukar 20–45 minuter vara lagom en vanlig dag. Inför prov kan planen lägga mer på de sista dagarna, men aldrig över max.'),
      h('label', {}, 'Pluggdagar'), h('div', { class: 'days' }, boxes),
      h('div', { class: 'row', style: 'margin-top:8px' },
        h('div', {}, h('label', {}, 'Pluggtid'), time),
        h('div', {}, h('label', {}, 'När (om-så-plan)'), cue)),
      h('p', { class: 'item-meta' }, 'Bestäm gärna tillsammans med eleven: "När jag ätit mellanmål, då tar jag fram boken." Påminnelsen kommer vid pluggtiden och en knuff två timmar senare; passet i Google Kalender läggs då.'),
      h('label', { style: 'display:flex;gap:6px;align-items:center;margin-top:10px' }, replan, 'Gör om befintliga planer med de nya inställningarna'),
      h('div', { class: 'toolbar', style: 'margin-top:12px' },
        h('button', { class: 'primary', onclick: async (e) => {
          const days = boxes.map(b => b.querySelector('input')).filter(c => c.checked).map(c => c.dataset.day);
          if (!days.length) return toast('Välj minst en pluggdag', true);
          const r = await run(() => api('/api/student', { method: 'PUT', body: { maxMinutesPerDay: Number(minutes.value), studyDays: days, studyTime: time.value, studyCue: cue.value } }), e.target);
          if (!r) return;
          s = r;
          if (replan.checked) {
            await run(() => api('/api/plan/replan-all', { method: 'POST' }), null, 'Sparat – planerna görs om (tar en liten stund)');
          } else toast('Sparat – gäller nya planer');
        } }, 'Spara')));
  })();
  return panel;
}

function studentPanel() {
  const panel = h('section', { class: 'panel', style: 'margin-top:16px' }, h('h2', {}, 'Eleven och coachen'),
    h('div', { class: 'empty' }, h('span', { class: 'spinner' })));
  (async () => {
    let s;
    try { s = await api('/api/student'); } catch (e) { panel.replaceChildren(h('h2', {}, 'Eleven och coachen'), h('div', { class: 'empty' }, e.message)); return; }
    const name = h('input', { value: s.name || '', placeholder: 'T.ex. Fred', maxlength: '40' });
    const tone = h('select', {}, s.tones.map(t => h('option', { value: t.id }, t.label)));
    tone.value = s.tone;
    const toneHelp = h('p', { class: 'item-meta' });
    const showHelp = () => { toneHelp.textContent = (s.tones.find(t => t.id === tone.value) || {}).instruction || ''; };
    tone.addEventListener('change', showHelp); showHelp();
    const extra = h('textarea', { rows: 3, maxlength: '600',
      placeholder: 'Valfritt. T.ex. "Han gillar fotboll – använd gärna fotbollsexempel. Påminn om att dricka vatten."' }, s.extra || '');
    const who = (state.students.find(x => String(x.id) === String(state.studentId)) || {}).name;
    panel.replaceChildren(h('h2', {}, `Coachen för ${who || s.name || 'eleven'}`),
      h('div', { class: 'row' },
        h('div', {}, h('label', {}, 'Elevens namn'), name),
        h('div', {}, h('label', {}, 'Coachens ton'), tone)),
      toneHelp,
      h('label', {}, 'Egna önskemål till coachen'), extra,
      h('p', { class: 'item-meta' }, 'Läggs till efter coachens fasta regler. Coachen ger fortfarande inga färdiga svar, oavsett vad som står här.'),
      h('div', { class: 'toolbar', style: 'margin-top:12px' },
        h('button', { class: 'primary', onclick: async (e) => {
          const r = await run(() => api('/api/student', { method: 'PUT', body: { name: name.value, tone: tone.value, extra: extra.value } }), e.target, 'Sparat – gäller från nästa svar');
          if (r) s = r;
        } }, 'Spara')));
  })();
  return panel;
}

function voicePanel() {
  const panel = h('section', { class: 'panel', style: 'margin-top:16px' }, h('h2', {}, 'Röst'),
    h('div', { class: 'empty' }, h('span', { class: 'spinner' }), ' Hämtar röster…'));
  (async () => {
    let s;
    try { s = await api('/api/speech'); } catch (e) {
      panel.replaceChildren(h('h2', {}, 'Röst'), h('div', { class: 'empty' }, e.message === '401' ? 'Ange token först.' : e.message));
      return;
    }
    if (!s.enabled) {
      panel.replaceChildren(h('h2', {}, 'Röst'), h('p', { class: 'item-meta' },
        'Ljudtjänsten är inte påslagen. Starta med docker compose (eller sätt PLUGGIS_SPEECH_URL) så kan du välja röst här.'));
      return;
    }
    const voices = Array.isArray(s.voices) ? s.voices : [];
    const select = h('select', {}, voices.map(v => h('option', { value: v.id },
      `${v.name.charAt(0).toUpperCase() + v.name.slice(1)}${v.quality ? ` (${v.quality})` : ''}${v.default ? ' – standard' : ''}`)));
    select.value = s.voice || (voices.find(v => v.default) || voices[0] || {}).id || '';
    const speed = h('input', { type: 'range', min: '0.7', max: '1.4', step: '0.05', value: String(s.speed || 1) });
    const speedLabel = h('span', { class: 'item-meta' }, `${Math.round((s.speed || 1) * 100)} %`);
    speed.addEventListener('input', () => { speedLabel.textContent = `${Math.round(Number(speed.value) * 100)} %`; });
    const sample = h('input', { value: 'Hej! Jag är Pluggis. Ska vi ta första uppgiften tillsammans?' });
    let audio = null;
    async function listen(e) {
      const res = await run(() => api('/api/speech/preview', { method: 'POST', raw: true,
        body: { text: sample.value, voice: select.value, speed: Number(speed.value) } }), e.target);
      if (!res || !res.ok) { if (res) toast('Kunde inte läsa upp', true); return; }
      if (audio) audio.pause();
      audio = new Audio(URL.createObjectURL(await res.blob()));
      audio.play();
    }
    panel.replaceChildren(h('h2', {}, 'Röst'),
      s.error ? h('p', { class: 'item-meta' }, s.error) : null,
      h('div', { class: 'row' },
        h('div', {}, h('label', {}, 'Röst'), select),
        h('div', {}, h('label', {}, 'Tempo '), speed, ' ', speedLabel)),
      h('label', {}, 'Provmening'), sample,
      h('div', { class: 'toolbar', style: 'margin-top:12px' },
        h('button', { onclick: listen }, '▶ Lyssna'),
        h('button', { class: 'primary', onclick: (e) => run(() => api('/api/speech', { method: 'PUT',
          body: { voice: select.value, speed: Number(speed.value) } }), e.target, 'Rösten sparad') }, 'Spara')),
      h('p', { class: 'item-meta' }, 'Gäller Tab5 och simulatorn med "Serverns röst". Röster: Piper (svenska).'));
  })();
  return panel;
}

// ---------- Router ----------

async function route() {
  if (state.leaveView) { try { state.leaveView(); } catch { /* ignore */ } state.leaveView = null; }
  const view = (location.hash || '#oversikt').slice(1);
  const render = views[view] || views.oversikt;
  document.querySelectorAll('#tabs a').forEach(a => a.classList.toggle('active', a.dataset.view === view));
  const main = $('#main');
  main.replaceChildren(h('div', { class: 'empty' }, h('span', { class: 'spinner' }), ' Laddar…'));
  const fresh = h('div');
  try {
    await render(fresh);
    main.replaceChildren(fresh);
  } catch (e) {
    main.replaceChildren(h('section', { class: 'panel' }, h('h2', {}, 'Kunde inte ladda'),
      h('p', {}, e.message === '401' ? 'Ange token under ' : (e.message || 'Okänt fel'), e.message === '401' ? h('a', { href: '#installningar' }, 'Inställningar') : '')));
  }
}

async function pollDevice() {
  const el = $('#deviceStatus');
  try {
    const r = await fetch('/api/devices', { headers: state.token ? { 'X-Pluggis-Token': state.token } : {} });
    if (!r.ok) throw new Error();
    const data = await r.json();
    const on = data.connected.includes(state.deviceId);
    el.classList.toggle('on', on);
    el.textContent = on ? '● Tab5 ansluten' : '○ Tab5 inte ansluten';
  } catch {
    el.classList.remove('on');
    el.textContent = '○ –';
  }
}

// ---------- Elever ----------

/** Hämtar elever och fyller elevväljaren. Vald elev styr läxor, plan, kort, poäng och Tab5-id. */
async function loadStudents() {
  const sel = $('#studentSel');
  try {
    state.students = await api('/api/students');
  } catch {
    state.students = [];
  }
  if (!state.students.length) { sel.hidden = true; return; }
  let current = state.students.find(s => String(s.id) === String(state.studentId)) || state.students[0];
  selectStudent(current, false);
  sel.replaceChildren(...state.students.map(s => h('option', { value: s.id }, s.name || `Elev ${s.id}`)));
  sel.value = String(current.id);
  sel.hidden = state.students.length < 2;
}

function selectStudent(s, rerender = true) {
  state.studentId = String(s.id);
  state.deviceId = s.deviceId;
  store.set('lc.student', state.studentId);
  store.set('lc.device', state.deviceId);
  state.homework = [];
  if (rerender) { route(); pollDevice(); }
}

$('#studentSel').addEventListener('change', (e) => {
  const s = state.students.find(x => String(x.id) === e.target.value);
  if (s) selectStudent(s);
});

window.addEventListener('hashchange', route);
loadStudents().then(() => { route(); pollDevice(); });
setInterval(pollDevice, 30000);
