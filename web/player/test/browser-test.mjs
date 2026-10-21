// Plays each file through the player layer in headless Chromium and reports what happened:
//
//   node web/player/test/browser-test.mjs <base url of player-test.html> <file:size:mime> ...
//
// Each file is opened, played until it is 2 s in (or 40 s pass), seeked to 70 %, and checked
// again. The page stands TDLib in with HTTP range reads of local files (player-test.html), so the
// Service Worker, TgSource and both engines run as they do over Telegram. Needs playwright-core
// and /usr/bin/chromium-browser (never Google Chrome).
import { createRequire } from 'node:module';
import path from 'node:path';

const require = createRequire(path.join(process.cwd(), 'package.json'));
let chromium;
try {
  ({ chromium } = require('playwright-core'));
} catch (e) {
  ({ chromium } = require(process.env.PLAYWRIGHT_CORE || 'playwright-core'));
}

const [base, ...files] = process.argv.slice(2);
const browser = await chromium.launch({
  executablePath: process.env.CHROMIUM || '/usr/bin/chromium-browser',
  headless: true,
  args: ['--no-sandbox', '--autoplay-policy=no-user-gesture-required'],
});
const page = await browser.newPage({ viewport: { width: 1280, height: 720 } });
const logs = [];
page.on('console', (m) => { if (m.type() === 'error') logs.push(m.text().slice(0, 240)); });
page.on('pageerror', (e) => logs.push('pageerror: ' + e.message.slice(0, 240)));
await page.goto(base);
await page.waitForFunction(() => window.ready, null, { timeout: 30000 });
await page.evaluate(() => window.ready);
// A Service Worker only controls a page loaded after it took over.
await page.reload();
await page.waitForFunction(() => window.ready && navigator.serviceWorker.controller, null, { timeout: 30000 });

const state = () => page.evaluate(() => {
  const m = document.querySelector('#tm-player movi-player, #tm-player video');
  const root = document.querySelector('#tm-player');
  return {
    engine: m ? (m.tagName === 'VIDEO' ? 'native' : 'movi') : '',
    t: m ? Math.round(m.currentTime * 10) / 10 : 0,
    d: m ? Math.round((m.duration || 0) * 10) / 10 : 0,
    failed: root ? root.classList.contains('tm-failed') : false,
    error: root ? root.querySelector('.tm-error h2').textContent : '',
    reads: window.TmTd.reads,
    mb: Math.round(window.TmTd.bytes / 1048576 * 10) / 10,
  };
});

async function until(pred, ms) {
  const end = Date.now() + ms;
  let s = await state();
  while (Date.now() < end && !pred(s)) {
    await page.waitForTimeout(500);
    s = await state();
  }
  return s;
}

for (const spec of files) {
  const [name, size, mime] = spec.split(':');
  const chosen = await page.evaluate(([n, s, m]) => window.playFile(n, Number(s), m), [name, size, mime]);
  const started = Date.now();
  const first = await until((s) => s.t >= 2 || s.failed, 40000);
  const startMs = Date.now() - started;
  let seek = null;
  if (!first.failed && first.d > 0) {
    await page.evaluate(() => {
      const m = document.querySelector('#tm-player movi-player, #tm-player video');
      m.currentTime = m.duration * 0.7;
    });
    const target = first.d * 0.7;
    seek = await until((s) => s.t >= target + 1 || s.failed, 30000);
  }
  console.log(JSON.stringify({ name, chosen, startMs, first, seek }));
  await page.evaluate(() => window.TmPlayer.close());
  await page.waitForTimeout(500);
}
console.log('errors:', [...new Set(logs)].slice(0, 15).join('\n  '));
await browser.close();
