// Skärmdumpar av elevchatten i iPhone-storlek med påhittade men realistiska data.
const { chromium } = require('playwright');
const path = require('path');
const fs = require('fs');
const STATIC = process.argv[2];
const OUT = process.argv[3];
// node shots.js <static> <out> en → a student with the language English, test data in English
const EN = process.argv[4] === 'en';
const T = (sv, en) => (EN ? en : sv);

const iso = (n) => { const d = new Date(); d.setDate(d.getDate() + n); return d.toLocaleDateString('sv-SE'); };
const HW = [
  { id: 7, kind: 'TEST', title: T('Prov fotosyntes', 'Photosynthesis test'), subject: T('NO', 'Science'), dueDate: iso(4), status: 'OPEN', weight: 90,
    description: T('Utkik kap 4 s. 40–56: fotosyntes, cellandning, kretslopp', 'Science book ch. 4 p. 40–56: photosynthesis, respiration, cycles'),
    steps: T(['4.1 Fotosyntesen', '4.2 Cellandning', '4.3 Kretsloppet'], ['4.1 Photosynthesis', '4.2 Respiration', '4.3 The carbon cycle']) },
  { id: 8, kind: 'HOMEWORK', title: T('Glosor kap 5', 'Vocabulary ch. 5'), subject: T('Spanska', 'Spanish'), dueDate: iso(2), status: 'OPEN', steps: [] },
  { id: 9, kind: 'HOMEWORK', title: T('Ekvationer s. 88–90', 'Equations p. 88–90'), subject: T('Matte', 'Maths'), dueDate: iso(6), status: 'OPEN', steps: [] },
];
const item = (id, hw, task, min, day, type, done = false) => ({ id, homeworkId: hw, task, minutes: min, planDate: iso(day), type, done });
// Som planerna ser ut nu: läsmoment med sidor (🎧 Lyssna) och övningspass (▶ Starta), 15–25 min, ett block per ämne
const TODAY = [item(1, 8, T('Övningspass: glosorna kap 5', 'Practice session: vocabulary ch. 5'), 15, 0, 'PRACTICE', true),
  item(2, 7, T('Läs s. 40–45 om fotosyntesen', 'Read p. 40–45 about photosynthesis'), 20, 0, 'READ'), item(3, 7, T('Övningspass: fotosyntesens begrepp', 'Practice session: photosynthesis concepts'), 15, 0, 'PRACTICE')];
const UPCOMING = [...TODAY, item(4, 8, T('Glosförhör: glosorna kap 5', 'Vocabulary quiz: ch. 5'), 15, 1, 'PRACTICE'), item(5, 7, T('Läs s. 46–52 om cellandning', 'Read p. 46–52 about respiration'), 20, 1, 'READ'),
  item(6, 9, T('Räkna uppgift 1–8 på s. 88', 'Do exercises 1–8 on p. 88'), 20, 2, 'PRACTICE'), item(7, 7, T('Övningspass: cellandning', 'Practice session: respiration'), 15, 2, 'PRACTICE'),
  item(8, 7, T('Testprov i Pluggis', 'Mock test in Pluggis'), 20, 3, 'REVIEW')];
const PROGRESS = [{ homeworkId: 7, title: T('Prov fotosyntes', 'Photosynthesis test'), kind: 'TEST', dueDate: iso(4), doneItems: 3, totalItems: 9, percent: 33, daysLeft: 4 },
  { homeworkId: 8, title: T('Glosor kap 5', 'Vocabulary ch. 5'), kind: 'HOMEWORK', dueDate: iso(2), doneItems: 1, totalItems: 2, percent: 50, daysLeft: 2 }];
const DECKS = [
  { deck: { id: 3, name: 'Spanska glosor kap 5', subject: 'Spanska', homeworkId: 8 }, cards: 15, dueNow: 6, learned: 7 },
  { deck: { id: 5, name: '⚡ Snabbquiz: Fotosyntesen', subject: 'NO', homeworkId: 7 }, cards: 10, dueNow: 10, learned: 0 },
  { deck: { id: 6, name: '✏️ Lucktext: Fotosyntesen', subject: 'NO', homeworkId: 7 }, cards: 8, dueNow: 8, learned: 0 },
  { deck: { id: 4, name: 'NO begrepp kap 4', subject: 'NO', homeworkId: 7 }, cards: 12, dueNow: 0, learned: 9 }];
const HISTORY = [
  { k: 'me', t: T('Vad ska jag göra idag?', 'What should I do today?') },
  { k: 'bot', t: T('Glosorna är redan klara – snyggt! Kvar idag: läs s. 40–45 om fotosyntesen (20 min) och sedan ett övningspass på begreppen (15 min). Provet är om fyra dagar. Börjar vi med läsningen?',
    'Your vocabulary is already done – nice! Left today: read p. 40–45 about photosynthesis (20 min) and then a practice session on the concepts (15 min). The test is in four days. Shall we start with the reading?') },
  { k: 'me', t: T('Ja men jag orkar inte läsa', "Ok but I can't be bothered to read") },
  { k: 'bot', t: T('Då lyssnar vi i stället. Tryck 🎧 Lyssna på läsmomentet i planen, så läser jag sidorna för dig. Säg till när du är klar!',
    "Then let's listen instead. Tap 🎧 Listen on the reading task in the plan and I'll read the pages to you. Tell me when you're done!") },
  { k: 'note', t: T('⏱ Pass startat: Läs s. 40–45 om fotosyntesen, 20 min', '⏱ Session started: Read p. 40–45 about photosynthesis, 20 min') },
]

const EXAM_SV = { id: 'x', title: 'Testprov: Fotosyntes och cellandning', points: 10, maxPoints: 13, percent: 77,
  review: ['Klorofyll', 'Cellandning'], reviewDeckId: 77, items: [
    { n: 1, type: 'choice', question: 'Vad behöver växter för att göra socker?', yourAnswer: 'Ljus, vatten och koldioxid', answer: 'Ljus, vatten och koldioxid', points: 1, max: 1 },
    { n: 2, type: 'cloze', question: 'Det gröna färgämnet ___ fångar solljuset.', yourAnswer: 'glukos', answer: 'klorofyll', points: 0, max: 1, topic: 'Klorofyll' },
    { n: 3, type: 'open', question: 'Varför behöver vi växter för att kunna andas?', yourAnswer: 'för att de gör syre', answer: 'Vid fotosyntesen bildas syre som vi andas in', points: 2, max: 2, feedback: 'Precis – växterna bildar syre vid fotosyntesen.' },
    { n: 4, type: 'open', question: 'Vad händer vid cellandningen?', yourAnswer: 'cellerna andas', answer: 'Socker och syre blir energi, koldioxid och vatten', points: 1, max: 2, feedback: 'Bra början! Det som saknas är att socker och syre ger energi.', topic: 'Cellandning' }] };
const EXAM_EN = { id: 'x', title: 'Mock test: Photosynthesis and respiration', points: 10, maxPoints: 13, percent: 77,
  review: ['Chlorophyll', 'Respiration'], reviewDeckId: 77, items: [
    { n: 1, type: 'choice', question: 'What do plants need to make sugar?', yourAnswer: 'Light, water and carbon dioxide', answer: 'Light, water and carbon dioxide', points: 1, max: 1 },
    { n: 2, type: 'cloze', question: 'The green pigment ___ captures the sunlight.', yourAnswer: 'glucose', answer: 'chlorophyll', points: 0, max: 1, topic: 'Chlorophyll' },
    { n: 3, type: 'open', question: 'Why do we need plants to be able to breathe?', yourAnswer: 'because they make oxygen', answer: 'Photosynthesis makes the oxygen we breathe in', points: 2, max: 2, feedback: 'Exactly – plants make oxygen during photosynthesis.' },
    { n: 4, type: 'open', question: 'What happens during respiration?', yourAnswer: 'the cells breathe', answer: 'Sugar and oxygen become energy, carbon dioxide and water', points: 1, max: 2, feedback: 'Good start! What is missing is that sugar and oxygen give energy.', topic: 'Respiration' }] };
const json = (route, body) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
const quizCard = { id: 91, kind: 'CHOICE', front: T('Vad behöver växter för att göra socker?', 'What do plants need to make sugar?'), back: T('Ljus, vatten och koldioxid', 'Light, water and carbon dioxide'),
  wrongOptions: T(['Jord, salt och syre', 'Mörker och kväve', 'Socker och vatten'], ['Soil, salt and oxygen', 'Darkness and nitrogen', 'Sugar and water']), hint: null, box: 1 };
const clozeCard = { id: 92, kind: 'CLOZE', front: 'Det gröna färgämnet ___ fångar solljuset i bladen.', back: 'klorofyll',
  wrongOptions: ['glukos', 'stärkelse', 'cellulosa'], hint: null, box: 1 };
let nextCard = quizCard;
let exTurn = 0;

async function api(route) {
  const url = new URL(route.request().url());
  const p = url.pathname + url.search;
  if (url.pathname === '/api/chat/me') return json(route, { deviceId: 'fred', name: 'Fred', serverVoice: true, pointsEnabled: true, version: 'x', language: EN ? 'en' : 'sv' });
  if (url.pathname.endsWith('/rewards/events')) return json(route, [
    { points: 10, reason: 'Pluggpass 10 min', awardedOn: iso(0), kind: 'SESSION_MINUTES' },
    { points: 5, reason: 'Förklarade Fotosyntesen för Pluggis', awardedOn: iso(0), kind: 'BONUS' },
    { points: 3, reason: 'Klart: Startsteg: läs glosorna högt en gång', awardedOn: iso(0), kind: 'PLAN_ITEM_DONE' },
    { points: 9, reason: 'Streak 3 dagar', awardedOn: iso(-1), kind: 'STREAK' },
    { points: 5, reason: 'Hela dagens plan klar! 🎉', awardedOn: iso(-1), kind: 'BONUS' }]);
  if (url.pathname.includes('/rewards')) return json(route, { weekPoints: 86, allTimePoints: 412, streakDays: 3, earnedSek: 8.6, unpaidSek: 8.6, sekPerPoint: 0.1, weeklyCapSek: 50, moneyEnabled: true });
  if (url.pathname === '/api/plan/today') return json(route, TODAY.filter(i => !i.done || true).map(i => ({ item: i, homeworkTitle: HW.find(h => h.id === i.homeworkId).title, overdue: false })));
  if (url.pathname === '/api/plan/upcoming') return json(route, UPCOMING);
  if (url.pathname === '/api/plan/progress') return json(route, PROGRESS);
  if (url.pathname === '/api/homework') return json(route, HW);
  if (url.pathname.match(/^\/api\/homework\/\d+\/material$/)) return json(route, [
    { id: 31, homeworkId: 7, filename: 'utkik-s40.jpg', page: 1, bookPage: 40, bookPageTo: 41, summary: T('Fotosyntesen – ljus, vatten och koldioxid blir socker och syre', 'Photosynthesis – light, water and carbon dioxide become sugar and oxygen'), text: T('Fotosyntesen…', 'Photosynthesis…'), hasImage: false, headings: T(['4.1 Fotosyntes', 'Vad behöver växten?', 'Klorofyll fångar ljuset'], ['4.1 Photosynthesis', 'What does the plant need?', 'Chlorophyll captures light']) },
    { id: 32, homeworkId: 7, filename: 'utkik-s42.jpg', page: 2, bookPage: 42, bookPageTo: 43, summary: T('Cellandning – socker och syre ger energi', 'Respiration – sugar and oxygen give energy'), text: T('Cellandning…', 'Respiration…'), hasImage: false, headings: T(['4.2 Cellandning', 'Energi till cellerna'], ['4.2 Respiration', 'Energy for the cells']) }]);
  if (url.pathname === '/api/flashcards/decks/4/cards' || url.pathname === '/api/flashcards/decks/5/cards') return json(route, [
    { id: 1, front: 'Klorofyll', back: 'Grönt ämne som fångar ljus' }, { id: 2, front: 'Glukos', back: 'Sockret växten bildar' },
    { id: 3, front: 'Koldioxid', back: 'Gas som växten tar upp' }, { id: 4, front: 'Cellandning', back: 'Socker + syre ger energi' },
    { id: 5, front: 'Klyvöppning', back: 'Litet hål i bladet' }, { id: 6, front: 'Fotosyntes', back: 'Ljus blir socker och syre' }]);
  if (url.pathname.match(/\/api\/flashcards\/decks\/\d+\/cards/)) return json(route, []);
  if (url.pathname === '/api/games/order') return json(route, { note: null, sequences: [{ title: 'Från ljus till socker', prompt: 'Sätt fotosyntesens steg i rätt ordning',
    steps: ['Bladet fångar solljus', 'Rötterna tar upp vatten', 'Koldioxid kommer in genom bladen', 'Socker bildas', 'Syre släpps ut'],
    why: 'Ljuset ger energin som behövs för att göra socker av vatten och koldioxid – syret blir över.' }] });
  if (url.pathname.startsWith('/api/games/done')) return json(route, { ok: true });
  if (url.pathname === '/api/flashcards/decks') return json(route, DECKS);
  if (url.pathname === '/api/flashcards/quiz') {   // varje övning görs ny: snabbquiz = lek 5, lucktext = lek 6
    const b = JSON.parse(route.request().postData() || '{}');
    return json(route, { deck: { id: b.mode === 'cloze' ? 6 : 5, name: b.mode === 'cloze' ? T('Lucktext: Fotosyntes', 'Fill in the gap: Photosynthesis') : T('Snabbquiz: Fotosyntes', 'Quick quiz: Photosynthesis') }, cardCount: 10, note: null });
  }
  if (url.pathname.match(/\/api\/flashcards\/decks\/6\/next/)) return json(route, clozeCard);
  if (url.pathname.match(/\/api\/flashcards\/decks\/\d+\/next/)) return json(route, nextCard);
  if (url.pathname.match(/\/grade$/)) return json(route, { id: 1 });
  if (url.pathname === '/api/exam/start') return json(route, { id: '99999999-2222-3333-4444-555555555555', title: T('Testprov: Fotosyntes och cellandning', 'Mock test: Photosynthesis and respiration'), maxPoints: 13, note: null,
    questions: [{ n: 1, type: 'choice', question: quizCard.front, choices: [quizCard.wrongOptions[0], quizCard.back, quizCard.wrongOptions[1], quizCard.wrongOptions[2]] },
      ...Array.from({ length: 9 }, (_, k) => ({ n: k + 2, type: k === 2 ? 'open' : 'choice', question: 'Fråga ' + (k + 2), choices: k === 2 ? null : ['a', 'b', 'c', 'd'] })),
      { n: 11, type: 'open', question: T('Varför behöver vi växter för att kunna andas?', 'Why do we need plants to be able to breathe?'), choices: null }] });
  if (url.pathname.includes('/api/exam/')) return json(route, EN ? EXAM_EN : EXAM_SV);
  if (url.pathname === '/api/explain/start') return json(route, { id: '11111111-2222-3333-4444-555555555555', concept: T('Fotosyntesen', 'Photosynthesis'),
    reply: T('Jag fattar inte hur växter kan äta ljus. Kan du förklara fotosyntesen för mig?', "I don't get how plants can eat light. Can you explain photosynthesis to me?"), done: false, covered: [], missing: [] });
  if (url.pathname.includes('/api/explain/')) {
    exTurn++;
    return json(route, exTurn < 2
      ? { id: 'x', concept: T('Fotosyntesen', 'Photosynthesis'), reply: T('Okej, men vad blir det av ljuset och vattnet sen? Försvinner det bara?', 'Okay, but what happens to the light and the water then? Do they just disappear?'), done: false, covered: [], missing: [] }
      : { id: 'x', concept: T('Fotosyntesen', 'Photosynthesis'), reply: T('Tack! Nu fattar jag att växterna gör socker – och att vi får syret. Snyggt förklarat!', 'Thanks! Now I get that plants make sugar – and that we get the oxygen. Nicely explained!'), done: true,
        covered: T(['Växter använder solljus som energi', 'De tar upp vatten och koldioxid', 'Det blir socker och syre'], ['Plants use sunlight as energy', 'They take in water and carbon dioxide', 'It becomes sugar and oxygen']),
        missing: T(['Det sker i klorofyllet i bladen'], ['It happens in the chlorophyll in the leaves']) });
  }
  if (url.pathname === '/api/speech/preview') return route.fulfill({ status: 503, body: '' });
  return route.fulfill({ status: 404, body: '{}' });
}

(async () => {
  const browser = await chromium.launch({ executablePath: '/usr/bin/google-chrome', args: ['--no-sandbox'] });
  const ctx = await browser.newContext({
    viewport: { width: 390, height: 844 }, deviceScaleFactor: 3, isMobile: true, hasTouch: true, locale: EN ? 'en-GB' : 'sv-SE', colorScheme: 'dark',
    userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Mobile/15E148 Safari/604.1',
  });
  await ctx.addInitScript(({ history }) => {
    localStorage.setItem('pluggis.chat.token', 'K7M2Q');
    localStorage.setItem('pluggis.chat.device', 'fred');
    localStorage.setItem('pluggis.chat.history', JSON.stringify(history));
    localStorage.setItem('pluggis.chat.voice', 'av');
    class FakeWS { constructor() { this.readyState = 0; setTimeout(() => { this.readyState = 1; this.onopen && this.onopen(); }, 10); }
      send(d) { const m = JSON.parse(d); if (m.type === 'ping') setTimeout(() => this.onmessage && this.onmessage({ data: '{"type":"pong"}' }), 5); }
      close() { this.readyState = 3; } }
    FakeWS.OPEN = 1; window.WebSocket = FakeWS;
  }, { history: HISTORY });
  await ctx.route('**/api/**', api);
  await ctx.route('**/*', (route) => {
    const u = new URL(route.request().url());
    if (u.pathname.startsWith('/api/')) return route.fallback();
    const f = path.join(STATIC, u.pathname === '/' ? 'chat.html' : u.pathname);
    if (!fs.existsSync(f)) return route.fulfill({ status: 404, body: '' });
    const type = f.endsWith('.js') ? 'text/javascript' : f.endsWith('.css') ? 'text/css' : f.endsWith('.html') ? 'text/html' : f.endsWith('.png') ? 'image/png' : 'application/octet-stream';
    return route.fulfill({ status: 200, contentType: type + (type.startsWith('text') ? '; charset=utf-8' : ''), body: fs.readFileSync(f) });
  });
  const page = await ctx.newPage();
  const errs = []; page.on('pageerror', e => errs.push(e.message));
  const shot = async (name) => { await page.waitForTimeout(350); await page.screenshot({ path: path.join(OUT, name) }); console.log('📸', name); };
  const tab = async (t) => { await page.click(`#tabbar [data-tab=${t}]`); await page.waitForTimeout(300); };

  await page.goto('http://pluggis.local:8080/chat.html');
  await page.waitForTimeout(600);
  page.on('dialog', d => d.accept());
  // Färre bilder: det viktigaste – planen, läxorna med material och hur man övar
  await shot('1-chatt.png');
  await tab('plan'); await shot('2-plan.png');
  // Övningspass från planen: timern överst och övningarna byts av
  nextCard = quizCard;
  await page.click(EN ? '.task:has-text("Practice session: photosynthesis concepts") button:has-text("Start")' : '.task:has-text("Övningspass: fotosyntesens begrepp") button:has-text("Starta")'); await page.waitForTimeout(600);
  await page.click(`.choice:has-text("${quizCard.back}")`); await page.waitForTimeout(3500);   // notisen hinner gå
  await shot('3-ovningspass.png');
  await page.click('#runBar button:last-child'); await page.waitForTimeout(300);
  // Läxan/provet med materialet, och att man bara fotar sidorna med mobilen
  await tab('laxor'); await page.waitForTimeout(3000);
  await page.click(`text=${T('Prov fotosyntes', 'Photosynthesis test')}`); await page.waitForTimeout(400);
  await shot('4-prov.png');
  // Fotar sidorna med mobilen: väljaren öppnas, två fotade boksidor väljs och förhandsvisningen visas
  const [chooser] = await Promise.all([page.waitForEvent('filechooser'),
    page.click(EN ? 'button:has-text("Add pages or files")' : 'button:has-text("Lägg till sidor eller filer")')]);
  await chooser.setFiles([`/tmp/pages/${EN ? 'en' : 'sv'}-40.jpg`, `/tmp/pages/${EN ? 'en' : 'sv'}-42.jpg`]); await page.waitForTimeout(800);
  await shot('5-fota.png');
  await page.keyboard.press('Escape'); await page.evaluate(() => { const s = document.querySelector('#sheet'); if (s) s.hidden = true; });
  await tab('kort');
  await page.click(EN ? '.quiz-btn:has-text("Mock test")' : '.quiz-btn:has-text("Testprov")'); await page.waitForTimeout(400);
  await page.click(EN ? 'button:has-text("Start")' : 'text=Starta provet'); await page.waitForTimeout(200);
  await page.click(`.choice:has-text("${quizCard.back}")`); await page.waitForTimeout(200);
  for (let k = 0; k < 10; k++) { await page.click(EN ? 'button:has-text("Next")' : 'button:has-text("Nästa")'); await page.waitForTimeout(80); }
  await page.click(EN ? 'text=Hand in' : 'text=Lämna in'); await page.waitForTimeout(700);
  await shot('6-testprov-resultat.png');
  await tab('kort'); await page.selectOption('.quiz-sel', '7'); await page.waitForTimeout(300); await shot('7-ova.png');
  console.log(errs.length ? 'ERRORS ' + errs.join(' | ') : 'OK');
  await browser.close();
})();
