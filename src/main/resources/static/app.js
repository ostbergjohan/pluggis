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
      type: 'checkbox', checked: item.done, disabled: item.done,
      onchange: async () => {
        const ok = await run(() => api(`/api/plan/items/${item.id}/done?deviceId=${enc(state.deviceId)}`, { method: 'POST' }),
          null, 'Bra jobbat! +3 poäng');
        if (ok !== undefined) { row.classList.add('done'); box.disabled = true; } else box.checked = false;
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
  const photoInput = h('input', { type: 'file', accept: FILE_ACCEPT, style: 'display:none',
    onchange: () => {
      const file = photoInput.files[0];
      photoInput.value = '';
      if (file) forms.replaceChildren(uploadForm(file));
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
          await api(`/api/homework/${created.id}`, { method: 'PUT', body: { kind: v.kind, replan: false } });
          if (v.dueDate && (v.description || v.steps.length)) {
            await run(() => api(`/api/plan/homework/${created.id}`, { method: 'POST' }), null, 'Sparad och planerad');
          } else toast('Sparad');
          state.openHomeworkId = created.id;
          forms.replaceChildren();
          render();
        } }, 'Spara'),
        h('button', { onclick: () => forms.replaceChildren() }, 'Avbryt')));
  }

  function uploadForm(file) {
    const target = h('select', {}, h('option', { value: '' }, 'Ny läxa eller prov – läs av allt i filen'),
      state.homework.filter(x => x.status === 'OPEN' && x.kind !== 'OTHER' && !(x.dueDate && daysUntil(x.dueDate) < 0))
        .sort((a, b) => (a.dueDate || '9999').localeCompare(b.dueDate || '9999'))
        .map(x => h('option', { value: x.id }, `${x.kind === 'TEST' ? 'Prov' : 'Läxa'}: ${x.title}${x.dueDate ? ` (${fmtDay(x.dueDate)})` : ''}`)));
    const cards = h('input', { type: 'checkbox' });
    const cardsRow = h('label', { style: 'display:none;gap:6px;align-items:center' }, cards, 'Gör också flashkort av materialet');
    target.addEventListener('change', () => { cardsRow.style.display = target.value ? 'flex' : 'none'; });
    return h('div', { class: 'panel', style: 'margin-bottom:16px' }, h('h2', {}, `Ladda upp: ${file.name}`),
      h('label', {}, 'Vad är det här?'), target, cardsRow,
      h('p', { class: 'item-meta' }, 'Koppla till en läxa eller ett prov: Claude läser vad som ingår (avsnitt, sidor, begrepp), lägger in det och gör om planen.'),
      h('div', { class: 'toolbar', style: 'margin-top:12px' },
        h('button', { class: 'primary', onclick: async (e) => {
          const fd = new FormData();
          fd.append('file', file);
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
  const el = h('div', {},
    h('div', { class: 'row' },
      h('div', {}, h('label', {}, 'Titel'), title),
      h('div', {}, h('label', {}, 'Ämne'), subject),
      h('div', {}, h('label', {}, 'Typ'), kind),
      h('div', {}, h('label', {}, 'Datum (inlämning/prov)'), h('div', { style: 'display:flex;gap:6px;align-items:center' }, due, noDate))),
    h('label', {}, 'Vad som ingår'), description,
    h('label', {}, 'Avsnitt / delsteg'), steps);
  return {
    el,
    values: () => ({
      title: title.value.trim(), subject: subject.value.trim(), kind: kind.value,
      dueDate: due.value || null, clearDueDate: !due.value && !!x.dueDate,
      description: description.value.trim(), steps: lines(steps.value),
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
      x.status === 'DONE' ? h('span', { class: 'tag done' }, 'klar') : null,
      h('div', { class: 'item-meta' }, meta)));

  async function fill() {
    const f = homeworkFields(x);
    const planBox = h('div', {}, h('span', { class: 'spinner' }));
    const matBox = h('div', {}, h('span', { class: 'spinner' }));
    const matInput = h('input', { type: 'file', accept: FILE_ACCEPT, style: 'display:none', onchange: async () => {
      const file = matInput.files[0];
      matInput.value = '';
      if (!file) return;
      const cards = confirm('Ska Pluggis också göra flashkort av materialet?');
      const fd = new FormData(); fd.append('file', file);
      matBox.replaceChildren(h('span', { class: 'spinner' }), ' Claude läser materialet…');
      const r = await run(() => api(`/api/homework/upload?homeworkId=${x.id}&flashcards=${cards}&deviceId=${enc(state.deviceId)}`,
        { method: 'POST', form: fd }));
      if (r) {
        toast([`${r.sections.length} avsnitt inlagda`, r.replanned ? 'ny plan gjord' : null,
          r.deckId ? `${r.cardCount} flashkort` : null, r.note].filter(Boolean).join(' · '));
        state.openHomeworkId = x.id;
        rerender();
      } else showMaterial();
    } });
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
      h('h3', { style: 'margin-top:16px' }, 'Material'), matBox, matInput,
      h('h3', { style: 'margin-top:16px' }, 'Plan'), planBox);
    item.append(body);
    showMaterial();

    async function showMaterial() {
      const list = await api(`/api/homework/${x.id}/material`);
      if (!list.length) {
        matBox.replaceChildren(h('div', { class: 'empty' }, 'Inget material än. Ladda upp en skärmdump, Word-fil, PDF eller text så läser Claude vad som ingår.'));
        return;
      }
      matBox.replaceChildren(...list.map(m => h('div', { class: 'check' },
        h('span', { class: 'tag' }, { IMAGE: 'Bild', PDF: 'PDF', WORD: 'Word', TEXT: 'Text' }[m.kind] || m.kind),
        h('div', { style: 'flex:1' }, h('div', { class: 'task' }, m.filename || 'fil'),
          m.summary ? h('div', { class: 'item-meta' }, m.summary) : null),
        h('button', { class: 'small danger', onclick: async (e) => {
          const r = await run(() => api(`/api/homework/material/${m.id}`, { method: 'DELETE' }), e.target);
          if (r !== undefined) showMaterial();
        } }, '✕'))));
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
  main.append(h('h1', {}, 'Plan'), h('p', { class: 'sub' }, 'Det som är planerat de kommande tre veckorna. Bocka av när det är gjort.'));
  const [today, upcoming] = await Promise.all([api('/api/plan/today'), api('/api/plan/upcoming?days=21'), loadHomework(true)]);
  const overdue = today.filter(t => t.overdue);
  if (overdue.length) {
    main.append(h('section', { class: 'panel', style: 'margin-bottom:16px' }, h('h2', {}, 'Ligger efter'), planChecklist(overdue, true)));
  }
  const panel = h('section', { class: 'panel' });
  if (!upcoming.length) panel.append(h('div', { class: 'empty' }, 'Inget planerat. Gå till Läxor & prov, fyll i vad som ingår och tryck Planera.'));
  for (const [day, items] of groupByDay(upcoming)) {
    panel.append(h('div', { class: 'day-head' }, `${fmtLong(day)}${day === todayIso() ? ' – idag' : ''}`),
      planChecklist(items.map(i => ({ item: i, homeworkTitle: homeworkTitle(i.homeworkId) })), true));
  }
  main.append(panel);
};

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
        h('td', {}, fmtDay(e.awardedOn)), h('td', {}, e.reason), h('td', { style: 'text-align:right;font-weight:600' }, `+${e.points}`)))))
        : h('div', { class: 'empty' }, 'Inga poäng än.')),
    h('section', { class: 'panel' }, h('h2', {}, 'Utbetalningar'),
      payouts.length ? h('table', {}, h('tbody', {}, payouts.map(p => h('tr', {},
        h('td', {}, new Date(p.paidAt).toLocaleDateString('sv-SE')), h('td', {}, `vecka ${fmtDay(p.weekStart)}`),
        h('td', { style: 'text-align:right' }, kr(p.amountSek))))))
        : h('div', { class: 'empty' }, 'Inga utbetalningar än.'))));
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
