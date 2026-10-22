// Streams Telegram files to the <video> element: /stream/<key>?size=&mime= answers range requests
// with a real 206, at most 2 MiB at a time, from the page that holds the Telegram connection.
const MAX_ANSWER = 2 * 1024 * 1024
const READ_TIMEOUT = 30_000

self.addEventListener('install', () => self.skipWaiting())
self.addEventListener('activate', (event) => event.waitUntil(self.clients.claim()))

self.addEventListener('fetch', (event) => {
    const url = new URL(event.request.url)
    if (url.origin !== self.location.origin || !url.pathname.startsWith('/stream/')) return
    event.respondWith(stream(event, url))
})

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
