const { chromium } = require('playwright');
const path = require('path'), fs = require('fs');
// node admin-mode.js <static> <out.png> [en] – the Claude panel under Cost (API key, hybrid or subscription, and the limits)
const STATIC = process.argv[2], OUT = process.argv[3], EN = process.argv[4] === 'en';
(async () => {
  const browser = await chromium.launch({ executablePath: '/usr/bin/google-chrome', args: ['--no-sandbox'] });
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, deviceScaleFactor: 2, locale: EN ? 'en-GB' : 'sv-SE', colorScheme: 'dark' });
  await ctx.addInitScript((en) => { localStorage.setItem('pluggis.admin.lang', en ? 'en' : 'sv'); }, EN);
  const at = (h) => new Date(Date.now() + h * 3600e3).toISOString();
  const json = (r, b) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(b) });
  await ctx.route('**/*', (route) => {
    const u = new URL(route.request().url());
    if (u.pathname === '/api/claude') return json(route, { mode: 'hybrid', configuredMode: 'hybrid', apiKey: true, token: true, cliVersion: '2.1.4 (Claude Code)',
      apiPurposes: ['coach'], claudeCode: { ok: 42, failed: 1, lastOk: new Date().toISOString(), lastError: null }, fallbacks: 1, problems: [],
      subscription: { status: 'allowed', type: 'five_hour', utilization: 0.42, resetsAt: at(3), updatedAt: at(-0.2), near: false, reached: false,
        usage: { fiveHour: { percent: 42, resetsAt: at(3) }, sevenDay: { percent: 63, resetsAt: at(60) } } } });
    if (u.pathname === '/api/costs') return json(route, { usdSek: 10, backend: 'hybrid', monthName: 'november', today: { sek: 0.42, calls: 12, subscriptionSek: 1.1 },
      month: { sek: 6.3, calls: 380, subscriptionSek: 24.5 }, azure: { characters: 41000, free: 500000 },
      days: Array.from({ length: 30 }, (_, i) => ({ day: '2026-11-' + String(i + 1).padStart(2, '0'), sek: Math.random() * 0.6, subscriptionSek: Math.random() * 1.5 })),
      purposes: [{ purpose: 'coach', label: 'Coachen (röst och chatt)', models: ['claude-haiku-5-5'], calls: 300, inputTokens: 900000, outputTokens: 60000, sek: 5.1, subscriptionSek: 0 }],
      students: [], prices: [{ model: 'claude-haiku-5-5', input: 0.1, output: 0.5, cacheRead: 0.01, cacheWrite: 0.125 }] });
    if (u.pathname === '/api/students') return json(route, [{ id: 1, name: 'Fred', code: 'K7M2Q', deviceId: 'fred' }]);
    if (u.pathname.startsWith('/api/')) return json(route, []);
    const f = path.join(STATIC, u.pathname === '/admin' || u.pathname === '/' ? 'admin.html' : u.pathname);
    if (!fs.existsSync(f)) return route.fulfill({ status: 404, body: '' });
    const t = f.endsWith('.js') ? 'text/javascript' : f.endsWith('.css') ? 'text/css' : f.endsWith('.html') ? 'text/html' : 'application/octet-stream';
    return route.fulfill({ status: 200, contentType: t + '; charset=utf-8', body: fs.readFileSync(f) });
  });
  const page = await ctx.newPage();
  await page.goto('http://pluggis.local:8080/admin#kostnad'); await page.waitForTimeout(800);
  await page.locator('section.panel', { has: page.locator('.mode-list') }).screenshot({ path: OUT });
  await browser.close();
})();
