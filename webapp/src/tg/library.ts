// Worker only: the chat list, and each opened chat's videos paged in from Telegram and arranged
// by the app's own rules (WebRules' screen and shelf) once per change, the answer kept until the
// chat, the order, the size limits or the words change. The page asks again on every scroll and
// gets the kept answer.
import type { Message, Peer, TelegramClient } from '@mtcute/web'
import { rules, type ShelfTile, type Video } from '../rules'
import { itemOf, rawOf, thumbnails, videoId } from './media'
import type { ChatItem, ChatPage, ChatView, Found, Tile, VideoItem } from './types'

const PAGE = 100
const VIDEO_FILTER = { _: 'inputMessagesFilterVideo' } as const
const DOC_FILTER = { _: 'inputMessagesFilterDocument' } as const
/** Chats kept arranged at once; the oldest opened goes first. */
const KEEP_CHATS = 6

interface Feed {
    filter: typeof VIDEO_FILTER | typeof DOC_FILTER
    next: number | undefined
    done: boolean
}

interface ChatState {
    id: number
    title: string
    self: boolean
    photo: string | null
    protected: boolean
    feeds: Feed[]
    videos: Map<string, Video>
    items: Map<string, VideoItem>
    archives: Map<number, string>
    selfDestructing: number
    /** Bumped each time videos come in, so a kept arrangement knows it is stale. */
    version: number
    loading: Promise<void> | null
    kept: { version: number; view: string; page: ChatPage } | null
}

const chats = new Map<number, ChatState>()

export function forgetAll() {
    chats.clear()
    dialogs = null
    dialogsLoading = null
    searches.clear()
}

async function openState(tg: TelegramClient, chatId: number): Promise<ChatState> {
    const peer = await tg.getPeer(chatId)
    const small = peer.photo?.small ?? null
    if (small) thumbnails.set(small.uniqueFileId, small)
    return {
        photo: small?.uniqueFileId ?? null,
        id: chatId,
        title: peer.displayName,
        self: peer.type === 'user' && peer.isSelf,
        protected: !!(peer.raw as { noforwards?: boolean }).noforwards,
        feeds: [VIDEO_FILTER, DOC_FILTER].map((filter) => ({ filter, next: undefined, done: false })),
        videos: new Map(),
        items: new Map(),
        archives: new Map(),
        selfDestructing: 0,
        version: 0,
        loading: null,
        kept: null,
    }
}

/** The next hundred of each kind (videos, files) the chat has not sent yet, through the rules. */
async function loadPage(tg: TelegramClient, s: ChatState): Promise<void> {
    const r = await rules()
    const pages = await Promise.all(
        s.feeds
            .filter((f) => !f.done)
            .map(async (f) => {
                const res = await tg.searchMessages({ chatId: s.id, filter: f.filter, limit: PAGE, offset: f.next })
                f.next = res.next
                f.done = res.next === undefined || res.length === 0
                return res as Message[]
            }),
    )
    const byId = new Map<number, Message>()
    for (const msg of pages.flat()) byId.set(msg.id, msg)
    const raws = []
    for (const msg of byId.values()) {
        const raw = rawOf(msg, s.protected)
        if (!raw) continue
        if (raw.kind === 'document' && raw.fileName && !raw.withheld) {
            const archive = r.archive(raw.fileName, raw.mimeType)
            if (archive) {
                s.archives.set(msg.id, raw.fileName)
                continue
            }
        }
        raws.push(raw)
    }
    const screened = r.screen(raws)
    s.selfDestructing += screened.selfDestructing
    for (const v of screened.videos) {
        const msg = byId.get(v.messageId)
        const item = msg && itemOf(msg, v.title)
        if (!item) continue
        s.videos.set(v.id, v)
        s.items.set(v.id, item)
    }
    s.version++
}

/** Runs one load at a time per chat: a second Refresh or scroll while one runs waits for it. */
function load(s: ChatState, run: () => Promise<void>): Promise<void> {
    s.loading ??= run().finally(() => (s.loading = null))
    return s.loading
}

const done = (s: ChatState) => s.feeds.every((f) => f.done)

/** The episode a show's card plays: the one started, else the first not watched, else the first. */
function upNext(tile: ShelfTile, watched: Set<string>, started: Set<string>): Video | null {
    const episodes = tile.seasons.flatMap((season) => season.episodes.map((e) => e.video))
    return episodes.find((v) => started.has(v.id)) ?? episodes.find((v) => !watched.has(v.id)) ?? episodes[0] ?? tile.video ?? null
}

function arrange(s: ChatState, view: ChatView): ChatPage {
    const key = JSON.stringify(view)
    if (s.kept && s.kept.version === s.version && s.kept.view === key) return s.kept.page
    const r = rulesNow!
    const shelf = r.shelf([...s.videos.values()], view.sort, view.minBytes, view.maxBytes, view.watched, view.started)
    const watched = new Set(view.watched)
    const started = new Set(view.started)
    let tiles: Tile[] = []
    for (const t of shelf.tiles) {
        const video = t.kind === 'show' ? upNext(t, watched, started) : t.video
        const item = video && s.items.get(video.id)
        if (!item) continue
        tiles.push({
            key: t.key,
            kind: t.kind === 'show' ? 'show' : 'file',
            title: t.title,
            item,
            seasons: t.seasons.length,
            episodes: t.seasons.reduce((n, season) => n + season.episodes.length, 0),
            copies: Math.max(0, t.copies.length - 1),
            parts: t.parts.length,
            pieces: t.pieces.length,
            extra: t.extra ?? null,
        })
    }
    const query = view.query.trim()
    if (query) {
        const texts = tiles.map((t) => (t.kind === 'show' ? t.title : `${t.title} ${s.videos.get(videoId(t.item))?.fileName ?? ''}`))
        const order = r.rank(texts, query)
        tiles = order.map((i) => tiles[i])
    }
    const page: ChatPage = {
        title: s.title,
        self: s.self,
        photo: s.photo,
        tiles,
        hiddenBySize: shelf.hiddenBySize,
        loaded: s.videos.size,
        done: done(s),
        archives: [...s.archives.entries()].sort((a, b) => b[0] - a[0]).map(([, name]) => name),
        selfDestructing: s.selfDestructing,
    }
    s.kept = { version: s.version, view: key, page }
    return page
}

const opening = new Map<number, Promise<ChatState>>()

/** A chat read again from its newest message; a second open or Refresh while one runs waits for it. */
function fresh(tg: TelegramClient, chatId: number): Promise<ChatState> {
    let p = opening.get(chatId)
    if (!p) {
        p = (async () => {
            const s = await openState(tg, chatId)
            await loadPage(tg, s)
            return s
        })().finally(() => opening.delete(chatId))
        opening.set(chatId, p)
    }
    return p
}

let rulesNow: Awaited<ReturnType<typeof rules>> | null = null

/**
 * A chat's grid. `open` loads its first page if it has none yet, `more` the next one, `refresh`
 * starts over from Telegram, `view` only arranges again (another order, size limits or words).
 */
export async function chatPage(tg: TelegramClient, chatId: number, view: ChatView, mode: 'open' | 'more' | 'refresh' | 'view'): Promise<ChatPage> {
    rulesNow = await rules()
    let s = mode === 'refresh' ? undefined : chats.get(chatId)
    if (!s) s = await fresh(tg, chatId)
    else if (mode === 'more' && !done(s)) {
        const st = s
        await load(st, () => loadPage(tg, st))
    } else if (s.loading) await s.loading
    // Most recently used last, so the oldest is the one let go.
    chats.delete(chatId)
    chats.set(chatId, s)
    while (chats.size > KEEP_CHATS) chats.delete(chats.keys().next().value!)
    return arrange(s, view)
}

// The chat list

let dialogs: ChatItem[] | null = null
let dialogsLoading: Promise<ChatItem[]> | null = null
const dialogListeners = new Set<(chats: ChatItem[]) => void>()

function chatItem(peer: Peer, pinned: boolean): ChatItem {
    const small = peer.photo?.small ?? null
    if (small) thumbnails.set(small.uniqueFileId, small)
    const self = peer.type === 'user' && peer.isSelf
    return {
        id: peer.id,
        title: peer.displayName,
        photo: small && !self ? small.uniqueFileId : null,
        pinned,
        kind: peer.type === 'user' ? (peer.isBot ? 'bot' : 'user') : peer.chatType === 'channel' ? 'channel' : 'group',
        forum: peer.type === 'chat' && peer.isForum,
        self,
    }
}

/**
 * Every chat outside the archive, pinned first, in Telegram's order. The list arrives in pages
 * of a hundred, each sent on to `onBatch` as it comes; it is kept for the session, and
 * `refresh` asks again.
 */
export async function chatList(tg: TelegramClient, refresh: boolean, onBatch: (chats: ChatItem[]) => void): Promise<ChatItem[]> {
    if (dialogs && !refresh) return dialogs
    dialogListeners.add(onBatch)
    try {
        dialogsLoading ??= (async () => {
            const out: ChatItem[] = []
            const seen = new Set<number>()
            for await (const d of tg.iterDialogs({ archived: 'exclude', pinned: 'include' })) {
                if (seen.has(d.peer.id)) continue
                seen.add(d.peer.id)
                out.push(chatItem(d.peer, d.isPinned))
                if (out.length % PAGE === 0) for (const fn of dialogListeners) fn(out.slice())
            }
            dialogs = out
            return out
        })().finally(() => (dialogsLoading = null))
        return await dialogsLoading
    } finally {
        dialogListeners.delete(onBatch)
    }
}

/** Ids of the listed chats whose names answer `query`, best first, by the app's fuzzy search. */
export async function rankChats(query: string): Promise<number[]> {
    const r = await rules()
    const list = dialogs ?? []
    return r.rank(list.map((c) => c.title), query).map((i) => list[i].id)
}

// Search across chats, within the size limits

/** Kept for two minutes, so a change of size limits arranges again without asking Telegram. */
const searches = new Map<string, { time: number; messages: Promise<Message[]> }>()

export async function searchAll(tg: TelegramClient, query: string, minBytes: number, maxBytes: number): Promise<Found> {
    const r = await rules()
    let hit = searches.get(query)
    if (!hit || Date.now() - hit.time > 120_000) {
        const limit = query ? 60 : 100
        hit = {
            time: Date.now(),
            messages: Promise.all([
                tg.searchGlobal({ query, filter: VIDEO_FILTER, limit }),
                tg.searchGlobal({ query, filter: DOC_FILTER, limit }),
            ]).then(([a, b]) => [...a, ...b] as Message[]),
        }
        hit.messages.catch(() => searches.delete(query))
        searches.set(query, hit)
        for (const [k, v] of searches) if (Date.now() - v.time > 120_000) searches.delete(k)
    }
    const messages = await hit.messages
    const byId = new Map<string, Message>()
    const raws = []
    for (const msg of messages) {
        const raw = rawOf(msg, false)
        if (!raw) continue
        byId.set(videoId(raw), msg)
        raws.push(raw)
    }
    const items: VideoItem[] = []
    const keys = new Set<string>()
    let hiddenBySize = 0
    for (const v of r.screen(raws).videos) {
        if (!r.sizeMatches(v.size, minBytes, maxBytes)) {
            hiddenBySize++
            continue
        }
        const item = itemOf(byId.get(v.id)!, r.tileTitle(v))
        if (!item || keys.has(item.key)) continue
        keys.add(item.key)
        items.push(item)
    }
    return { items: items.sort((a, b) => b.date - a.date), hiddenBySize }
}
