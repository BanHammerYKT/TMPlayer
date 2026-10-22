// TMPlayer Web's Service Worker: two jobs.
//
// 1. Streaming for the browser's own <video> (the "browser decoder" path: MP4 and WebM the
//    browser plays itself, which keeps picture in picture, AirPlay, Cast and the lock screen).
//    It answers one URL, stream/<fileId>?size=<bytes>&mime=<type> (and stream/j?ids=&sizes= for
//    a video cut into pieces), with byte ranges read from TDLib in the page that asked: the worker
//    has no TDLib of its own, so each read is a message to that page over a MessageChannel.
// 2. The app shell offline: the page, its scripts, the wasm and the text font are kept, so the
//    installed app opens without a connection (and says so) instead of the browser's error page.
'use strict';

const SHELL = 'tm-shell-v1';
const RUNTIME = 'tm-runtime-v1';
const MAX_CHUNK = 2 * 1024 * 1024;
const SHELL_FILES = [
  './',
  'index.html',
  'app.css',
  'storage-keeper.js',
  'td-bridge.js',
  'web-host.js',
  'tdweb.js',
  'tmweb.js',
  'manifest.webmanifest',
  'favicon.svg',
  'icon-192.png',
  'icon-512.png',
  'fonts/NotoSans.ttf',
  'i18n/en.json',
  'splash-words.js',
];

// A write the site's quota refused: the pages' storage keeper (storage-keeper.js) frees room.
let lastFull = 0;
function full(e) {
  const now = Date.now();
  if (now - lastFull < 10000) return;
  lastFull = now;
  const error = String((e && (e.name || e.message)) || e);
  self.clients.matchAll({ type: 'window', includeUncontrolled: true })
    .then((all) => all.forEach((c) => c.postMessage({ type: 'tm-storage-full', error: error })))
    .catch(() => {});
}

function keep(cache, request, response) {
  return cache.put(request, response).catch(full);
}

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(SHELL)
      .then((cache) => Promise.all(SHELL_FILES.map((f) => cache.add(f).catch(() => {}))))
      .then(() => self.skipWaiting()),
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== SHELL && k !== RUNTIME).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

// --- streaming -------------------------------------------------------------------------------

/** Turns a Range header into [start, end] inclusive, or null for none; 'bad' when unsatisfiable. */
function parseRange(header, size) {
  if (!header) return null;
  const m = /^bytes=(\d*)-(\d*)$/.exec(header.trim());
  if (!m || (m[1] === '' && m[2] === '')) return 'bad';
  let start;
  let end;
  if (m[1] === '') {
    const n = Number(m[2]);
    if (n === 0) return 'bad';
    start = Math.max(0, size - n);
    end = size - 1;
  } else {
    start = Number(m[1]);
    end = m[2] === '' ? size - 1 : Math.min(Number(m[2]), size - 1);
  }
  if (start >= size || start > end) return 'bad';
  return [start, end];
}

async function pageFor(event) {
  if (event.clientId) {
    const c = await self.clients.get(event.clientId);
    if (c) return c;
  }
  const all = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
  return all.find((c) => c.visibilityState === 'visible') || all[0] || null;
}

function askPage(client, request) {
  return new Promise((resolve, reject) => {
    const channel = new MessageChannel();
    const timer = setTimeout(() => reject(new Error('the page did not answer')), 60000);
    channel.port1.onmessage = (e) => {
      clearTimeout(timer);
      if (e.data && e.data.error) reject(new Error(e.data.error));
      else resolve(e.data.buffer);
    };
    client.postMessage(Object.assign({ type: 'tm-read' }, request), [channel.port2]);
  });
}

async function stream(event, url) {
  const params = url.searchParams;
  const mime = params.get('mime') || 'video/mp4';
  let source;
  let size;
  if (url.pathname.endsWith('/stream/j')) {
    const ids = (params.get('ids') || '').split(',').map(Number);
    const sizes = (params.get('sizes') || '').split(',').map(Number);
    source = { ids: ids, sizes: sizes };
    size = sizes.reduce((a, b) => a + b, 0);
  } else {
    const id = Number(url.pathname.split('/').pop());
    size = Number(params.get('size') || 0);
    source = { ids: [id], sizes: [size] };
  }
  const base = {
    'Content-Type': mime,
    'Accept-Ranges': 'bytes',
    'Cache-Control': 'no-store',
  };
  const range = parseRange(event.request.headers.get('range'), size);
  if (range === 'bad') {
    return new Response(null, { status: 416, headers: Object.assign({ 'Content-Range': `bytes */${size}` }, base) });
  }
  const client = await pageFor(event);
  if (!client) return new Response('TMPlayer is not open', { status: 503 });
  if (range === null) {
    // No Range header: the whole file as a 200, read in chunks as the element consumes it.
    let at = 0;
    const body = new ReadableStream({
      async pull(controller) {
        if (at >= size) return controller.close();
        try {
          const buf = await askPage(client, { source: source, offset: at, length: Math.min(MAX_CHUNK, size - at) });
          if (!buf || buf.byteLength === 0) return controller.close();
          at += buf.byteLength;
          controller.enqueue(new Uint8Array(buf));
        } catch (e) {
          controller.error(e);
        }
      },
    });
    return new Response(body, { status: 200, headers: Object.assign({ 'Content-Length': String(size) }, base) });
  }
  const [start, end] = range;
  const wanted = Math.min(end - start + 1, MAX_CHUNK);
  let buf;
  try {
    buf = await askPage(client, { source: source, offset: start, length: wanted });
  } catch (e) {
    return new Response(String(e.message || e), { status: 504 });
  }
  const n = buf ? buf.byteLength : 0;
  if (n === 0) return new Response(null, { status: 504 });
  // A short read is answered as the shorter range it is; the element asks for the rest.
  return new Response(buf, {
    status: 206,
    headers: Object.assign({
      'Content-Range': `bytes ${start}-${start + n - 1}/${size}`,
      'Content-Length': String(n),
    }, base),
  });
}

// --- the shell -------------------------------------------------------------------------------

async function shell(request) {
  const url = new URL(request.url);
  const isPage = request.mode === 'navigate';
  if (isPage) {
    // The page: the network first, so a new version is picked up, the copy kept when offline.
    try {
      const fresh = await fetch(request);
      const cache = await caches.open(SHELL);
      keep(cache, 'index.html', fresh.clone());
      return fresh;
    } catch (e) {
      const cached = await caches.match('index.html');
      return cached || new Response('Offline', { status: 503 });
    }
  }
  // Content-hashed files (the wasm, the worker chunks) and fonts never change under their name.
  const immutable = /\.(wasm|ttf)$/.test(url.pathname) || /[0-9a-f]{20}/.test(url.pathname);
  const cached = await caches.match(request);
  if (cached && immutable) return cached;
  try {
    const fresh = await fetch(request);
    if (fresh.ok && request.method === 'GET' && url.origin === self.location.origin) {
      const cache = await caches.open(immutable ? RUNTIME : SHELL);
      keep(cache, request, fresh.clone());
    }
    return fresh;
  } catch (e) {
    if (cached) return cached;
    throw e;
  }
}

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);
  if (url.origin !== self.location.origin) return;
  if (url.pathname.includes('/stream/')) {
    event.respondWith(stream(event, url));
    return;
  }
  if (event.request.method !== 'GET') return;
  event.respondWith(shell(event.request));
});

// A press on a notification (web-host.js, notifyTap): the open page comes to the front and is told
// which one, or TMPlayer opens when no page is.
self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const id = event.notification.data && event.notification.data.tmTap;
  event.waitUntil((async () => {
    const all = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    const page = all.find((c) => c.focused) || all.find((c) => c.visibilityState === 'visible') || all[0];
    if (page) {
      try { await page.focus(); } catch (e) {}
      if (id) page.postMessage({ type: 'tm-notification-tap', id: id });
    } else if (self.clients.openWindow) {
      await self.clients.openWindow('./');
    }
  })());
});
