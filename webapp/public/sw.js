// Two jobs. Streams Telegram files to the <video> element: /stream/<key>?size=&mime= answers range
// requests with a real 206, at most 2 MiB at a time, from the page that holds the Telegram
// connection. And keeps the app's own files, so it opens without a connection and at once: the
// build writes VERSION and SHELL (vite.config.ts, offlineShell), so every release is a new worker.
// A new worker waits until the page says the user chose Reload (src/install.ts), so nothing changes
// under a video that is playing.
const VERSION = 'dev'
const SHELL = []
const MAX_ANSWER = 2 * 1024 * 1024
const READ_TIMEOUT = 30_000
const CACHE = `tmplayer-shell-${VERSION}`

self.addEventListener('install', (event) => {
    if (VERSION === 'dev') {
        self.skipWaiting()
        return
    }
    event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(SHELL.map((path) => new Request(path, { cache: 'reload' })))))
})

self.addEventListener('activate', (event) => {
    event.waitUntil(
        (async () => {
            // Older versions' files go; the thumbnails' cache (src/store.ts) stays.
            for (const name of await caches.keys()) if (name.startsWith('tmplayer-shell-') && name !== CACHE) await caches.delete(name)
            await self.clients.claim()
        })(),
    )
})

self.addEventListener('message', (event) => {
    if (event.data?.type === 'SKIP_WAITING') self.skipWaiting()
})

self.addEventListener('fetch', (event) => {
    const request = event.request
    const url = new URL(request.url)
    if (url.origin !== self.location.origin || request.method !== 'GET') return
    if (url.pathname.startsWith('/stream/')) return event.respondWith(stream(event, url))
    if (VERSION === 'dev') return
    // Every address is the same page: the router picks the screen.
    if (request.mode === 'navigate') return event.respondWith(fromCache('/', request))
    if (SHELL.includes(url.pathname) || url.pathname.startsWith('/assets/')) event.respondWith(fromCache(url.pathname, request))
})

/**
 * The kept copy, else the network. Built files have the hash of their content in their name, so a
 * copy never goes stale; files left out of SHELL (the sound decoders, loaded only for some videos)
 * are kept the first time they are fetched.
 */
async function fromCache(path, request) {
    const cache = await caches.open(CACHE)
    const hit = await cache.match(path)
    if (hit) return hit
    const response = await fetch(request)
    if (response.ok && path.startsWith('/assets/')) cache.put(path, response.clone()).catch(() => {})
    return response
}

async function stream(event, url) {
    const key = decodeURIComponent(url.pathname.slice('/stream/'.length))
    const size = Number(url.searchParams.get('size'))
    const mime = url.searchParams.get('mime') || 'video/mp4'
    const range = /bytes=(\d*)-(\d*)/.exec(event.request.headers.get('range') || '')
    let start = range && range[1] ? Number(range[1]) : 0
    let end = range && range[2] ? Number(range[2]) : size - 1
    if (range && !range[1] && range[2]) {
        // A suffix range: the last N bytes.
        start = Math.max(0, size - Number(range[2]))
        end = size - 1
    }
    if (!(size > 0) || start >= size) {
        return new Response(null, { status: 416, headers: { 'Content-Range': `bytes */${size}` } })
    }
    end = Math.min(end, size - 1, start + MAX_ANSWER - 1)

    const client = await pickClient(event)
    if (!client) return new Response(null, { status: 503 })
    let answer
    try {
        answer = await ask(client, { type: 'read', key, start, end })
    } catch {
        return new Response(null, { status: 504 })
    }
    if (answer.error) return new Response(null, { status: answer.error === 'gone' ? 410 : 502 })
    const bytes = answer.bytes
    return new Response(bytes, {
        status: 206,
        headers: {
            'Content-Type': mime,
            'Content-Length': String(bytes.byteLength),
            'Content-Range': `bytes ${start}-${start + bytes.byteLength - 1}/${size}`,
            'Accept-Ranges': 'bytes',
            'Cache-Control': 'no-store',
        },
    })
}

async function pickClient(event) {
    if (event.clientId) {
        const client = await self.clients.get(event.clientId)
        if (client) return client
    }
    const all = await self.clients.matchAll({ type: 'window' })
    return all.find((c) => c.focused) || all[0] || null
}

function ask(client, message) {
    return new Promise((resolve, reject) => {
        const channel = new MessageChannel()
        const timer = setTimeout(() => reject(new Error('timeout')), READ_TIMEOUT)
        channel.port1.onmessage = (ev) => {
            clearTimeout(timer)
            resolve(ev.data)
        }
        client.postMessage(message, [channel.port2])
    })
}
