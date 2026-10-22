import type { tl } from '@mtcute/web'
import { tg } from '../tg/client'

const KiB = 1024
const MiB = 1024 * KiB
const phone = matchMedia('(pointer: coarse)').matches
/** Bytes of file kept in memory across every open file; the head and tail are never evicted. */
const MEMORY_BUDGET = (phone ? 48 : 128) * MiB
/** Chunks fetched at once per file: about 3 to 4 MiB in flight, near Telegram's own web clients. */
const PARALLEL = 4

interface Job {
    index: number
    urgent: boolean
    abort: AbortController
    promise: Promise<Uint8Array>
    resolve: (b: Uint8Array) => void
    reject: (e: unknown) => void
    started: boolean
}

/**
 * Random access to one Telegram file, in 512 KiB chunks at offsets that are multiples of it, so no
 * request crosses a 1 MiB boundary. Measured 2026-10-22 on a free account (DC 4, about 3 MB/s
 * overall): four 512 KiB requests at once gave 3.15 MB/s with a median wait of 0.6 s; 1 MiB
 * requests 2.3 MB/s and 1.6 s; eight at once was slower either way. Safari still gets up to
 * 2 MiB per Service Worker answer, joined from several chunks.
 *
 * What the player waits on goes first; read-ahead covers about 30 s of the file's bitrate (6 to
 * 16 MiB) and is dropped when the player seeks elsewhere, pauses for long or closes.
 */
export class TgFile {
    readonly chunk: number
    readonly chunks: number
    private cache = new Map<number, Uint8Array>()
    private jobs = new Map<number, Job>()
    private closed = false
    private readAheadBytes: number
    /** Set by the player: read-ahead runs only while it plays (or is about to). */
    wantAhead = true
    /** Bytes received, for the details panel. */
    received = 0

    constructor(
        private location: tl.TypeInputFileLocation,
        private dcId: number,
        readonly size: number,
        durationSec: number,
    ) {
        this.chunk = 512 * KiB
        this.chunks = Math.ceil(size / this.chunk)
        const bitrate = durationSec > 0 ? size / durationSec : 1 * MiB
        this.readAheadBytes = Math.min(16 * MiB, Math.max(6 * MiB, bitrate * 30))
        files.add(this)
    }

    /** Fetches the first chunks and the last one together: the MP4 index or the MKV Cues often sit at the end. */
    warmUp() {
        this.get(0, true)
        const tail = this.size > 256 * MiB ? 2 : 1
        for (let i = Math.max(1, this.chunks - tail); i < this.chunks; i++) this.get(i, true)
        for (let i = 1; i < Math.min(4, this.chunks - tail); i++) this.get(i, false)
    }

    /** Bytes [start, end) of the file. */
    async read(start: number, end: number): Promise<Uint8Array> {
        end = Math.min(end, this.size)
        if (start >= end) return new Uint8Array(0)
        const first = Math.floor(start / this.chunk)
        const last = Math.floor((end - 1) / this.chunk)
        this.dropFarJobs(first, last)
        const parts = await Promise.all(Array.from({ length: last - first + 1 }, (_, k) => this.get(first + k, true)))
        this.ahead(last + 1)
        if (parts.length === 1) {
            const off = start - first * this.chunk
            return parts[0].subarray(off, off + (end - start))
        }
        const out = new Uint8Array(end - start)
        let at = 0
        for (let k = 0; k < parts.length; k++) {
            const base = (first + k) * this.chunk
            const from = Math.max(start, base) - base
            const to = Math.min(end, base + parts[k].length) - base
            out.set(parts[k].subarray(from, to), at)
            at += to - from
        }
        return out
    }

    close() {
        this.closed = true
        for (const job of this.jobs.values()) job.abort.abort()
        this.jobs.clear()
        this.cache.clear()
        files.delete(this)
    }

    private ahead(from: number) {
        if (!this.wantAhead || this.closed) return
        const count = Math.ceil(this.readAheadBytes / this.chunk)
        for (let i = from; i < Math.min(this.chunks, from + count); i++) this.get(i, false)
    }

    /** A seek: read-ahead not started and far from the new place is dropped, so the wait goes first. */
    private dropFarJobs(first: number, last: number) {
        const keep = Math.ceil(this.readAheadBytes / this.chunk)
        for (const job of this.jobs.values()) {
            // Read-ahead far from the new place goes, even mid-flight: its slot is needed for the wait.
            if (job.urgent) continue
            if (job.index < first || job.index > last + keep) {
                job.abort.abort()
                this.jobs.delete(job.index)
                job.reject(new DOMException('dropped', 'AbortError'))
            }
        }
    }

    private get(index: number, urgent: boolean): Promise<Uint8Array> {
        const cached = this.cache.get(index)
        if (cached) {
            this.cache.delete(index)
            this.cache.set(index, cached)
            return Promise.resolve(cached)
        }
        const existing = this.jobs.get(index)
        if (existing) {
            if (urgent && !existing.urgent) existing.urgent = true
            this.pump()
            return existing.promise
        }
        let resolve!: (b: Uint8Array) => void
        let reject!: (e: unknown) => void
        const promise = new Promise<Uint8Array>((res, rej) => {
            resolve = res
            reject = rej
        })
        // Read-ahead nobody waits on must not surface as an unhandled rejection when dropped.
        promise.catch(() => {})
        this.jobs.set(index, { index, urgent, abort: new AbortController(), promise, resolve, reject, started: false })
        this.pump()
        return promise
    }

    private pump() {
        if (this.closed) return
        let running = 0
        for (const job of this.jobs.values()) if (job.started) running++
        while (running < PARALLEL) {
            const next = this.pickNext()
            if (!next) return
            next.started = true
            running++
            this.fetch(next)
        }
    }

    private pickNext(): Job | null {
        let best: Job | null = null
        for (const job of this.jobs.values()) {
            if (job.started) continue
            if (!best || (job.urgent && !best.urgent) || (job.urgent === best.urgent && job.index < best.index)) best = job
        }
        return best
    }

    private async fetch(job: Job) {
        const offset = job.index * this.chunk
        const limit = Math.min(this.chunk, this.size - offset)
        const startedAt = performance.now()
        for (let attempt = 0; ; attempt++) {
            try {
                // One aligned request per chunk; Telegram returns fewer bytes for the file's tail.
                const res = await tg.call(
                    { _: 'upload.getFile', location: this.location, offset, limit: this.chunk },
                    { dcId: this.dcId, kind: 'download', abortSignal: job.abort.signal, floodSleepThreshold: 30 },
                )
                if (res._ !== 'upload.file') throw new Error('Telegram sent a CDN redirect, which browsers cannot follow.')
                const out = res.bytes.length > limit ? res.bytes.subarray(0, limit) : res.bytes
                if (location.hostname === 'localhost') console.debug('[read]', job.index, job.urgent ? 'wait' : 'ahead', Math.round(performance.now() - startedAt), 'ms')
                const at = out.length
                this.received += at
                this.jobs.delete(job.index)
                this.store(job.index, out)
                job.resolve(this.cache.get(job.index) ?? out)
                break
            } catch (e) {
                const migrate = /^FILE_MIGRATE_(\d+)$/.exec((e as { text?: string }).text ?? '')
                if (migrate) {
                    this.dcId = Number(migrate[1])
                    continue
                }
                if (job.abort.signal.aborted || this.closed || attempt >= 3) {
                    this.jobs.delete(job.index)
                    job.reject(e)
                    break
                }
                await new Promise((r) => setTimeout(r, 400 * (attempt + 1)))
            }
        }
        this.pump()
    }

    private store(index: number, bytes: Uint8Array) {
        this.cache.set(index, bytes)
        evict()
    }

    /** Oldest chunks first, across every open file; each file's head and tail stay. */
    evictOne(): number {
        for (const [index, bytes] of this.cache) {
            if (index === 0 || index === this.chunks - 1) continue
            this.cache.delete(index)
            return bytes.length
        }
        return 0
    }

    get cachedBytes(): number {
        let n = 0
        for (const b of this.cache.values()) n += b.length
        return n
    }
}

const files = new Set<TgFile>()

/** Files opened ahead of Play (a card pointed at or pressed), handed to the player when it opens. */
const warm = new Map<string, { file: TgFile; timer: number }>()

export interface FileRef {
    key: string
    location: tl.TypeInputFileLocation
    dcId: number
    size: number
    duration: number
}

/** Starts fetching a file's head and tail before Play is pressed; dropped after 60 s unused. */
export function warmFile(ref: FileRef) {
    const existing = warm.get(ref.key)
    if (existing) {
        clearTimeout(existing.timer)
        existing.timer = window.setTimeout(() => dropWarm(ref.key), 60_000)
        return
    }
    const file = new TgFile(ref.location, ref.dcId, ref.size, ref.duration)
    file.wantAhead = false
    file.warmUp()
    warm.set(ref.key, { file, timer: window.setTimeout(() => dropWarm(ref.key), 60_000) })
}

function dropWarm(key: string) {
    warm.get(key)?.file.close()
    warm.delete(key)
}

/** The player's file: the warmed one when there is one, else a new one. */
export function openFile(ref: FileRef): TgFile {
    const w = warm.get(ref.key)
    if (w) {
        clearTimeout(w.timer)
        warm.delete(ref.key)
        w.file.wantAhead = true
        return w.file
    }
    return new TgFile(ref.location, ref.dcId, ref.size, ref.duration)
}

/**
 * Opens the connection to each data centre holding the listed files (one 4 KB read), so the first
 * Play does not pay for the handshake. Once per data centre per session; skipped with Save-Data.
 */
const warmedDcs = new Set<number>()
export function warmDataCentres(refs: FileRef[]) {
    if ((navigator as Navigator & { connection?: { saveData?: boolean } }).connection?.saveData) return
    for (const ref of refs) {
        if (warmedDcs.has(ref.dcId)) continue
        warmedDcs.add(ref.dcId)
        tg.call({ _: 'upload.getFile', location: ref.location, offset: 0, limit: 4096 }, { dcId: ref.dcId, kind: 'download' }).catch(() => warmedDcs.delete(ref.dcId))
    }
}

function evict() {
    let total = 0
    for (const f of files) total += f.cachedBytes
    while (total > MEMORY_BUDGET) {
        let freed = 0
        for (const f of files) {
            freed = f.evictOne()
            if (freed) break
        }
        if (!freed) return
        total -= freed
    }
}

/** Files the Service Worker may read, by the key in their /stream/ URL. */
export const streams = new Map<string, TgFile>()

/** Answers the Service Worker's range reads for the `<video>` element. */
export function serveStreams() {
    if (!('serviceWorker' in navigator)) return
    navigator.serviceWorker.addEventListener('message', async (ev: MessageEvent) => {
        const msg = ev.data as { type?: string; key: string; start: number; end: number }
        if (msg?.type !== 'read') return
        const port = ev.ports[0]
        const file = streams.get(msg.key)
        if (!file) return port.postMessage({ error: 'gone' })
        try {
            const bytes = await file.read(msg.start, msg.end + 1)
            const copy = bytes.slice().buffer
            port.postMessage({ bytes: copy }, [copy])
        } catch (e) {
            port.postMessage({ error: String(e) })
        }
    })
}

if (location.hostname === 'localhost') (window as unknown as { TgFile: typeof TgFile }).TgFile = TgFile
