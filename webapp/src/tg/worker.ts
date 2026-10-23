// The Telegram connection: MTProto, crypto, the session and every file location live here, off the
// main thread. The page talks to it through `api` below by message (./rpc.ts), and never loads
// mtcute itself.
import { BaseTelegramClient, TelegramClient, type Message, type User } from '@mtcute/web'
import { rules } from '../rules'
import { files, merge, thumbnails, toItem } from './media'
import type { ChatItem, Me, QrEvent, RpcError, VideoItem } from './types'

const base = new BaseTelegramClient({
    apiId: __TG_API_ID__,
    apiHash: __TG_API_HASH__,
    storage: 'tmplayer',
    initConnectionOptions: {
        deviceModel: 'TMPlayer Web',
        appVersion: '2.0',
        systemVersion: navigator.userAgent.includes('iPhone') ? 'iOS' : 'Web',
    },
    network: {
        // Three download connections per DC, as Telegram's own web clients use for a free
        // account; more only invites flood waits.
        connectionCount: (kind, _dc, isPremium) => {
            if (kind === 'download') return isPremium ? 6 : 3
            if (kind === 'downloadSmall') return 2
            if (kind === 'upload') return 2
            return 0
        },
        inactivityTimeout: 300_000,
    },
})
const tg = new TelegramClient({ client: base })

// The app's rules start loading beside the connection, so the first list does not wait for them.
const rulesStarted = performance.now()
rules().then(
    (r) => {
        if (import.meta.env.DEV) {
            const ms = Math.round(performance.now() - rulesStarted)
            console.info(`[rules] ready in ${ms} ms`, JSON.stringify(r.parse('Harbour.Notes.S01E02.1080p.mkv', null)))
        }
    },
    (e) => console.warn('[rules] not loaded', e),
)

/** What a call gets besides its arguments: its abort signal, and a way to talk to the page while it runs. */
export interface Ctx {
    signal: AbortSignal
    /** Sends the page an event for this call. */
    emit(data: unknown): void
    /** Sends the page an event and waits for its answer. */
    ask<T>(data: unknown): Promise<T>
}

function isUnauthorized(e: unknown): boolean {
    const text = String((e as { text?: string })?.text ?? e)
    return (e as { code?: number })?.code === 401 || /AUTH_KEY_UNREGISTERED|SESSION_REVOKED|USER_DEACTIVATED|AUTH_KEY_INVALID/.test(text)
}

const me = (u: User): Me => ({ id: u.id, name: u.displayName })

const VIDEO_FILTER = { _: 'inputMessagesFilterVideo' } as const
const DOC_FILTER = { _: 'inputMessagesFilterDocument' } as const
const warmedDcs = new Set<number>()

/** Bytes cut out of a larger buffer, as their own buffer so they can be handed over without a copy. */
const own = (b: Uint8Array): ArrayBuffer => (b.byteOffset === 0 && b.byteLength === b.buffer.byteLength ? b.buffer : b.slice().buffer) as ArrayBuffer

export const api = {
    /**
     * Who this browser's saved session belongs to, or null when it has none, read from storage
     * without asking Telegram, so the page paints at once. `whoami` then checks with Telegram.
     */
    async me(_: Ctx): Promise<Me | null> {
        await tg.prepare()
        const self = await tg.storage.self.fetch()
        return self ? { id: self.userId, name: null } : null
    },

    /** The signed in user as Telegram has it now; a 401 (ended on another device) signs the page out. */
    async whoami(_: Ctx): Promise<Me | null> {
        try {
            return me(await tg.getMe())
        } catch (e) {
            if (isUnauthorized(e)) return null
            throw e
        }
    },

    async signInQr(ctx: Ctx): Promise<Me> {
        const emit = (e: QrEvent) => ctx.emit(e)
        return me(
            await tg.signInQr({
                abortSignal: ctx.signal,
                onUrlUpdated: (url, expires) => emit({ kind: 'url', url, expires: expires.getTime() }),
                onQrScanned: () => emit({ kind: 'scanned' }),
                password: async () => ctx.ask<string>({ kind: 'password', hint: await tg.getPasswordHint().catch(() => null) } satisfies QrEvent),
                invalidPasswordCallback: () => emit({ kind: 'badPassword' }),
            }),
        )
    },

    async sendCode(_: Ctx, phone: string): Promise<{ hash: string } | { me: Me }> {
        const sent = await tg.sendCode({ phone })
        return 'phoneCodeHash' in sent ? { hash: sent.phoneCodeHash } : { me: me(sent as User) }
    },

    async signIn(_: Ctx, phone: string, hash: string, code: string): Promise<Me> {
        return me(await tg.signIn({ phone, phoneCodeHash: hash, phoneCode: code }))
    },

    async passwordHint(_: Ctx): Promise<string | null> {
        return tg.getPasswordHint().catch(() => null)
    },

    async checkPassword(_: Ctx, password: string): Promise<Me> {
        return me(await tg.checkPassword(password))
    },

    async logOut(_: Ctx): Promise<void> {
        await tg.logOut().catch(() => {})
        files.clear()
        thumbnails.clear()
        warmedDcs.clear()
    },

    async chats(_: Ctx, limit: number): Promise<ChatItem[]> {
        const out: ChatItem[] = []
        for await (const d of tg.iterDialogs({ limit })) out.push({ id: d.peer.id, title: d.peer.displayName })
        return out
    },

    /** Videos across every chat, posted as videos and as files, asked for in parallel. */
    async search(_: Ctx, query: string): Promise<VideoItem[]> {
        const [videos, docs] = await Promise.all([
            tg.searchGlobal({ query, filter: VIDEO_FILTER, limit: query ? 60 : 100 }),
            tg.searchGlobal({ query, filter: DOC_FILTER, limit: query ? 60 : 100 }),
        ])
        return merge([...videos, ...docs])
    },

    async chatVideos(_: Ctx, chatId: number): Promise<{ title: string; items: VideoItem[] }> {
        const [peer, videos, docs] = await Promise.all([
            tg.getPeer(chatId),
            tg.searchMessages({ chatId, filter: VIDEO_FILTER, limit: 100 }),
            tg.searchMessages({ chatId, filter: DOC_FILTER, limit: 100 }),
        ])
        return { title: peer.displayName, items: merge([...videos, ...docs]) }
    },

    /** One message's video, for a watch page opened by its address. Null when it is gone or not a video. */
    async video(_: Ctx, chatId: number, messageId: number): Promise<VideoItem | null> {
        const [msg] = await tg.getMessages(chatId, messageId)
        return msg ? toItem(msg as Message) : null
    },

    /** Whether this worker knows where a file is (a page reloaded on a list item's state may outlive it). */
    async known(_: Ctx, key: string): Promise<boolean> {
        return files.has(key)
    },

    async thumb(_: Ctx, key: string): Promise<ArrayBuffer> {
        const t = thumbnails.get(key)
        if (!t) throw new Error('gone')
        return own(await tg.downloadAsBuffer(t))
    },

    /** Bytes [offset, offset + limit) of a file, `limit` being a power of two that does not cross 1 MiB. */
    async chunk(ctx: Ctx, key: string, offset: number, limit: number): Promise<ArrayBuffer> {
        const f = files.get(key)
        if (!f) throw new Error('gone')
        for (;;) {
            try {
                const res = await tg.call(
                    { _: 'upload.getFile', location: f.location, offset, limit },
                    { dcId: f.dcId, kind: 'download', abortSignal: ctx.signal, floodSleepThreshold: 30 },
                )
                if (res._ !== 'upload.file') throw new Error('Telegram sent a CDN redirect, which browsers cannot follow.')
                const want = Math.min(limit, f.size - offset)
                return own(res.bytes.length > want ? res.bytes.subarray(0, want) : res.bytes)
            } catch (e) {
                const migrate = /^FILE_MIGRATE_(\d+)$/.exec((e as { text?: string }).text ?? '')
                if (!migrate) throw e
                f.dcId = Number(migrate[1])
            }
        }
    },

    /**
     * Opens the connection to each data centre holding the listed files (one 4 KB read), so the
     * first Play does not pay for the handshake. Once per data centre per session.
     */
    async warmDcs(_: Ctx, keys: string[]): Promise<void> {
        for (const key of keys) {
            const f = files.get(key)
            if (!f || warmedDcs.has(f.dcId)) continue
            warmedDcs.add(f.dcId)
            const dc = f.dcId
            tg.call({ _: 'upload.getFile', location: f.location, offset: 0, limit: 4096 }, { dcId: dc, kind: 'download' }).catch(() => warmedDcs.delete(dc))
        }
    },
}

export type Api = typeof api

// Messages from the page: a call, an abort of one, or the answer to a call's question.
type In =
    | { id: number; method: keyof Api; args: unknown[] }
    | { abort: number }
    | { reply: number; ask: number; value: unknown }

const running = new Map<number, { abort: AbortController; asks: Map<number, (v: unknown) => void> }>()

function errorOf(e: unknown): RpcError {
    const err = e as { message?: string; text?: string; code?: number; seconds?: number }
    return { message: err?.message ?? String(e), text: err?.text, code: err?.code, seconds: err?.seconds }
}

self.onmessage = async (ev: MessageEvent<In>) => {
    const msg = ev.data
    if ('abort' in msg) return running.get(msg.abort)?.abort.abort()
    if ('reply' in msg) {
        const call = running.get(msg.reply)
        call?.asks.get(msg.ask)?.(msg.value)
        call?.asks.delete(msg.ask)
        return
    }
    const { id, method, args } = msg
    const abort = new AbortController()
    const asks = new Map<number, (v: unknown) => void>()
    running.set(id, { abort, asks })
    let askId = 0
    const ctx: Ctx = {
        signal: abort.signal,
        emit: (data) => postMessage({ id, event: data }),
        ask: <T>(data: unknown) =>
            new Promise<T>((resolve) => {
                const n = ++askId
                asks.set(n, resolve as (v: unknown) => void)
                postMessage({ id, event: data, ask: n })
            }),
    }
    try {
        const fn = api[method] as (ctx: Ctx, ...a: unknown[]) => Promise<unknown>
        if (!fn) throw new Error(`No such call: ${method}`)
        const value = await fn(ctx, ...args)
        postMessage({ id, value }, value instanceof ArrayBuffer ? [value] : [])
    } catch (e) {
        postMessage({ id, error: errorOf(e) })
    } finally {
        running.delete(id)
    }
}
