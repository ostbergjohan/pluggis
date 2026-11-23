// Skärmdumpar av elevchatten i iPhone-storlek med påhittade men realistiska data.
const { chromium } = require('playwright');
const path = require('path');
const fs = require('fs');
const STATIC = process.argv[2];
const OUT = process.argv[3];

const iso = (n) => { const d = new Date(); d.setDate(d.getDate() + n); return d.toLocaleDateString('sv-SE'); };
const HW = [
  { id: 7, kind: 'TEST', title: 'Prov fotosyntes', subject: 'NO', dueDate: iso(4), status: 'OPEN', weight: 90,
    description: 'Utkik kap 4 s. 40–56: fotosyntes, cellandning, kretslopp', steps: ['4.1 Fotosyntesen', '4.2 Cellandning', '4.3 Kretsloppet'] },
  { id: 8, kind: 'HOMEWORK', title: 'Glosor kap 5', subject: 'Spanska', dueDate: iso(2), status: 'OPEN', steps: [] },
  { id: 9, kind: 'HOMEWORK', title: 'Ekvationer s. 88–90', subject: 'Matte', dueDate: iso(6), status: 'OPEN', steps: [] },
];
const item = (id, hw, task, min, day, done = false) => ({ id, homeworkId: hw, task, minutes: min, planDate: iso(day), done });
const TODAY = [item(1, 8, 'Startsteg: läs glosorna högt en gång', 3, 0, true), item(2, 8, 'Öva glosor 1–15 med flashkort', 10, 0),
  item(3, 7, 'Läs rubrikerna s. 40–45', 5, 0), item(4, 7, 'Läs 4.1 Fotosyntesen och skriv 3 nyckelord', 15, 0)];
const UPCOMING = [...TODAY, item(5, 8, 'Glosförhör med Pluggis', 10, 1), item(6, 7, 'Läs 4.2 Cellandning', 15, 1), item(7, 7, 'Snabbquiz kap 4.1–4.2', 10, 1),
  item(8, 9, 'Startsteg: titta på exemplet s. 88', 3, 2), item(9, 9, 'Lös uppgift 1–6', 15, 2), item(10, 7, 'Repetera begrepp utan boken', 10, 3)];
const PROGRESS = [{ homeworkId: 7, title: 'Prov fotosyntes', kind: 'TEST', dueDate: iso(4), doneItems: 2, totalItems: 8, percent: 25, daysLeft: 4 },
  { homeworkId: 8, title: 'Glosor kap 5', kind: 'HOMEWORK', dueDate: iso(2), doneItems: 2, totalItems: 4, percent: 50, daysLeft: 2 }];
const DECKS = [
  { deck: { id: 3, name: 'Spanska glosor kap 5', subject: 'Spanska', homeworkId: 8 }, cards: 15, dueNow: 6, learned: 7 },
  { deck: { id: 5, name: '⚡ Snabbquiz: Fotosyntesen', subject: 'NO', homeworkId: 7 }, cards: 10, dueNow: 10, learned: 0 },
  { deck: { id: 6, name: '✏️ Lucktext: Fotosyntesen', subject: 'NO', homeworkId: 7 }, cards: 8, dueNow: 8, learned: 0 },
  { deck: { id: 4, name: 'NO begrepp kap 4', subject: 'NO', homeworkId: 7 }, cards: 12, dueNow: 0, learned: 9 }];
const HISTORY = [
  { k: 'me', t: 'Jag vill börja plugga nu' },
  { k: 'bot', t: 'Grymt! Först på listan är spanska glosor – de ska sitta på onsdag. Ska vi köra 10 minuter med flashkorten?' },
  { k: 'me', t: 'Ja kör' },
  { k: 'note', t: '⏱ Pass startat: Spanska glosor, 10 min' },
  { k: 'bot', t: 'Första ordet: hur säger man "biblioteket" på spanska?' },
  { k: 'me', t: 'la biblioteca' },
  { k: 'bot', t: 'Helt rätt! Nästa: "jag brukar äta frukost".' },
];

const json = (route, body) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
const quizCard = { id: 91, kind: 'CHOICE', front: 'Vad behöver växter för att göra socker?', back: 'Ljus, vatten och koldioxid',
  wrongOptions: ['Jord, salt och syre', 'Mörker och kväve', 'Socker och vatten'], hint: null, box: 1 };
const clozeCard = { id: 92, kind: 'CLOZE', front: 'Det gröna färgämnet ___ fångar solljuset i bladen.', back: 'klorofyll',
  wrongOptions: ['glukos', 'stärkelse', 'cellulosa'], hint: null, box: 1 };
let nextCard = quizCard;
let exTurn = 0;

async function api(route) {
  const url = new URL(route.request().url());
  const p = url.pathname + url.search;
  if (url.pathname === '/api/chat/me') return json(route, { deviceId: 'fred', name: 'Fred', serverVoice: true, pointsEnabled: true, version: 'x' });
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
    { id: 31, homeworkId: 7, filename: 'utkik-s40.jpg', page: 40, summary: 'Fotosyntesen – ljus, vatten och koldioxid blir socker och syre', text: 'Fotosyntesen…', hasImage: false },
    { id: 32, homeworkId: 7, filename: 'utkik-s42.jpg', page: 42, summary: 'Cellandning – socker och syre ger energi', text: 'Cellandning…', hasImage: false }]);
  if (url.pathname === '/api/flashcards/decks') return json(route, DECKS);
  if (url.pathname.match(/\/api\/flashcards\/decks\/6\/next/)) return json(route, clozeCard);
  if (url.pathname.match(/\/api\/flashcards\/decks\/\d+\/next/)) return json(route, nextCard);
  if (url.pathname.match(/\/grade$/)) return json(route, { id: 1 });
  if (url.pathname === '/api/explain/start') return json(route, { id: '11111111-2222-3333-4444-555555555555', concept: 'Fotosyntesen',
    reply: 'Jag fattar inte hur växter kan äta ljus. Kan du förklara fotosyntesen för mig?', done: false, covered: [], missing: [] });
  if (url.pathname.includes('/api/explain/')) {
    exTurn++;
    return json(route, exTurn < 2
      ? { id: 'x', concept: 'Fotosyntesen', reply: 'Okej, men vad blir det av ljuset och vattnet sen? Försvinner det bara?', done: false, covered: [], missing: [] }
      : { id: 'x', concept: 'Fotosyntesen', reply: 'Tack! Nu fattar jag att växterna gör socker – och att vi får syret. Snyggt förklarat!', done: true,
        covered: ['Växter använder solljus som energi', 'De tar upp vatten och koldioxid', 'Det blir socker och syre'], missing: ['Det sker i klorofyllet i bladen'] });
  }
  if (url.pathname === '/api/speech/preview') return route.fulfill({ status: 503, body: '' });
  return route.fulfill({ status: 404, body: '{}' });
}

(async () => {
  const browser = await chromium.launch({ executablePath: '/usr/bin/google-chrome', args: ['--no-sandbox'] });
  const ctx = await browser.newContext({
    viewport: { width: 390, height: 844 }, deviceScaleFactor: 3, isMobile: true, hasTouch: true, locale: 'sv-SE',
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
  await shot('1-chatt.png');
  await tab('plan'); await shot('2-plan.png');
  await tab('laxor'); await shot('3-laxor.png');
  await page.click('text=Prov fotosyntes'); await page.waitForTimeout(400);
  await shot('4-prov.png');
  await tab('kort'); await shot('5-ova.png');
  // Snabbquiz: välj rätt svar
  nextCard = quizCard;
  await page.click('text=⚡ Snabbquiz: Fotosyntesen'); await page.waitForTimeout(400);
  await shot('6-snabbquiz.png');
  await page.click('.choice:has-text("Ljus, vatten och koldioxid")'); await page.waitForTimeout(300);
  await shot('7-snabbquiz-ratt.png');
  // Lucktext
  await tab('kort'); await page.click('text=✏️ Lucktext: Fotosyntesen'); await page.waitForTimeout(400);
  await page.click('.choice:has-text("glukos")'); await page.waitForTimeout(300);
  await shot('8-lucktext.png');
  // Förklara för Pluggis
  await tab('kort');
  await page.click('.quiz-btn:has-text("Förklara")'); await page.waitForTimeout(400);
  await page.fill('.ex-input textarea', 'Växterna tar solljus och vatten från rötterna och koldioxid från luften, och så gör de typ mat av det.');
  await page.click('.ex-input button'); await page.waitForTimeout(400);
  await shot('9-forklara.png');
  await page.fill('.ex-input textarea', 'Det blir socker som växten använder, och syre som kommer ut i luften. Det är därför vi kan andas!');
  await page.click('.ex-input button'); await page.waitForTimeout(500);
  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
  await shot('10-forklara-klart.png');
  await tab('poang'); await shot('11-poang.png');
  console.log(errs.length ? 'ERRORS ' + errs.join(' | ') : 'OK');
  await browser.close();
})();
