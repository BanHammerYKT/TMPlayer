import { createEffect, createResource, createSignal, For, onCleanup, onMount, Show } from 'solid-js'
import type { Dialog, Message, Peer, Thumbnail, tl } from '@mtcute/web'
import { errorText, tg } from '../tg/client'
import { warmDataCentres, warmFile } from '../stream/reader'
import { alternatives, type Storyboard, type VideoCopy } from '../tg/media'

export interface VideoItem {
    key: string
    fileId: string
    location: tl.TypeInputFileLocation
    dcId: number
    size: number
    duration: number
    title: string
    chat: string
    date: Date
    thumb: Thumbnail | null
    /** Telegram's own lighter MP4 copies, best first, when the video was posted as a video. */
    copies: VideoCopy[]
    storyboard: Storyboard | null
}

const VIDEO_NAME = /\.(mkv|mp4|m4v|mov|webm|avi|ts|m2ts|wmv|flv|3gp)$/i

/** A message's video, posted as a video or as a file, or null. Restricted messages are skipped. */
function toItem(msg: Message): VideoItem | null {
    if ((msg.raw as { restrictionReason?: unknown[] }).restrictionReason?.length) return null
    const m = msg.media
    if (!m || (m.type !== 'video' && m.type !== 'document')) return null
    if (m.type === 'video' && (m.isRound || m.isAnimation)) return null
    if (m.type === 'document' && !m.mimeType.startsWith('video/') && !VIDEO_NAME.test(m.fileName ?? '')) return null
    const name = m.fileName?.replace(/\.[^.]+$/, '').replace(/[._]+/g, ' ').trim()
    const caption = msg.text.split('\n')[0]?.trim()
    return {
        key: m.uniqueFileId,
        fileId: m.fileId,
        location: (typeof m.location === 'function' ? m.location() : m.location) as tl.TypeInputFileLocation,
        dcId: m.dcId ?? 2,
        size: m.fileSize ?? 0,
        duration: m.type === 'video' ? m.duration : 0,
        title: name || caption || 'Video',
        chat: msg.chat.displayName,
        date: msg.date,
        thumb: m.getThumbnail('m') ?? m.thumbnails[0] ?? null,
        ...alternatives(msg.raw._ === 'message' ? msg.raw.media : undefined),
    }
}

const VIDEO_FILTER = { _: 'inputMessagesFilterVideo' } as const
const DOC_FILTER = { _: 'inputMessagesFilterDocument' } as const

/** Videos across every chat, posted as videos and as files, asked for in parallel. */
async function searchAll(query: string): Promise<VideoItem[]> {
    const [videos, docs] = await Promise.all([
        tg.searchGlobal({ query, filter: VIDEO_FILTER, limit: query ? 60 : 100 }),
        tg.searchGlobal({ query, filter: DOC_FILTER, limit: query ? 60 : 100 }),
    ])
    return merge([...videos, ...docs])
}

async function chatVideos(peer: Peer): Promise<VideoItem[]> {
    const [videos, docs] = await Promise.all([
        tg.searchMessages({ chatId: peer.id, filter: VIDEO_FILTER, limit: 100 }),
        tg.searchMessages({ chatId: peer.id, filter: DOC_FILTER, limit: 100 }),
    ])
    return merge([...videos, ...docs])
}

function merge(messages: Message[]): VideoItem[] {
    const seen = new Set<string>()
    const out: VideoItem[] = []
    for (const msg of messages) {
        const item = toItem(msg)
        if (!item || seen.has(item.key)) continue
        seen.add(item.key)
        out.push(item)
    }
    return out.sort((a, b) => b.date.getTime() - a.date.getTime())
}

function size(bytes: number): string {
    if (bytes >= 1e9) return `${(bytes / 1e9).toFixed(1)} GB`
    return `${Math.max(1, Math.round(bytes / 1e6))} MB`
}

function length(s: number): string {
    if (!s) return ''
    const h = Math.floor(s / 3600)
    const m = Math.round((s % 3600) / 60)
    return h ? `${h} h ${m} min` : `${m} min`
}

// Thumbnails: a few at a time, only for cards on screen, kept for the session.
const thumbs = new Map<string, Promise<string>>()
let thumbsActive = 0
const thumbQueue: (() => void)[] = []
function loadThumb(t: Thumbnail): Promise<string> {
    let p = thumbs.get(t.fileId)
    if (!p) {
        p = new Promise<void>((r) => (thumbsActive < 4 ? (thumbsActive++, r()) : thumbQueue.push(r)))
            .then(() => tg.downloadAsBuffer(t))
            .then((bytes) => URL.createObjectURL(new Blob([bytes as BlobPart], { type: 'image/jpeg' })))
            .finally(() => {
                const next = thumbQueue.shift()
                if (next) next()
                else thumbsActive--
            })
        thumbs.set(t.fileId, p)
    }
    return p
}

function Thumb(props: { thumb: Thumbnail | null }) {
    let el!: HTMLDivElement
    const [src, setSrc] = createSignal<string | null>(null)
    onMount(() => {
        if (!props.thumb) return
        const io = new IntersectionObserver((entries) => {
            if (!entries.some((e) => e.isIntersecting)) return
            io.disconnect()
            loadThumb(props.thumb!).then(setSrc, () => {})
        })
        io.observe(el)
        onCleanup(() => io.disconnect())
    })
    return (
        <div class="thumb" ref={el}>
            <Show when={src()}>
                <img src={src()!} alt="" decoding="async" />
            </Show>
        </div>
    )
}

function VideoList(props: { items: VideoItem[]; onPlay: (v: VideoItem) => void }) {
    // Once a list has drawn, connect to the data centres its files live in.
    let dcTimer = 0
    createEffect(() => {
        const items = props.items
        clearTimeout(dcTimer)
        if (items.length) dcTimer = window.setTimeout(() => warmDataCentres(items.slice(0, 20)), 1500)
    })
    onCleanup(() => clearTimeout(dcTimer))
    // A card pointed at for 250 ms, or pressed, starts fetching its file's head and tail.
    let hoverTimer = 0
    const intent = (v: VideoItem) => {
        clearTimeout(hoverTimer)
        hoverTimer = window.setTimeout(() => warmFile(v), 250)
    }
    return (
        <ul class="videos">
            <For each={props.items}>
                {(v) => (
                    <li>
                        <button
                            class="video"
                            onClick={() => props.onPlay(v)}
                            onPointerEnter={() => intent(v)}
                            onPointerLeave={() => clearTimeout(hoverTimer)}
                            onFocus={() => intent(v)}
                            onPointerDown={() => warmFile(v)}
                        >
                            <Thumb thumb={v.thumb} />
                            <div class="meta">
                                <div class="name">{v.title}</div>
                                <div class="muted small">
                                    {[v.chat, size(v.size), length(v.duration)].filter(Boolean).join(' · ')}
                                </div>
                            </div>
                        </button>
                    </li>
                )}
            </For>
        </ul>
    )
}

/** A bare picker for trying the player: recent videos, search across chats, and one chat's videos. */
export function Pick(props: { onPlay: (v: VideoItem) => void; onSignOut: () => void }) {
    const [tab, setTab] = createSignal<'videos' | 'chats'>('videos')
    const [query, setQuery] = createSignal('')
    const [chat, setChat] = createSignal<Peer | null>(null)
    let debounce = 0
    const [typed, setTyped] = createSignal('')

    const [results] = createResource(typed, (q) => searchAll(q.trim()), { initialValue: [] })
    const [dialogs] = createResource(
        () => tab() === 'chats',
        async () => {
            const out: Dialog[] = []
            for await (const d of tg.iterDialogs({ limit: 60 })) out.push(d)
            return out
        },
    )
    const [inChat] = createResource(chat, chatVideos)

    const onType = (v: string) => {
        setQuery(v)
        clearTimeout(debounce)
        debounce = window.setTimeout(() => setTyped(v), 350)
    }

    return (
        <main class="pick">
            <header>
                <div class="brand">TMPlayer</div>
                <nav class="tabs">
                    <button classList={{ on: tab() === 'videos' }} onClick={() => (setTab('videos'), setChat(null))}>Videos</button>
                    <button classList={{ on: tab() === 'chats' }} onClick={() => setTab('chats')}>Chats</button>
                </nav>
                <button class="link" onClick={props.onSignOut}>Sign out</button>
            </header>
            <Show when={tab() === 'videos'}>
                <input class="search" type="search" placeholder="Search videos in your chats" value={query()} onInput={(e) => onType(e.currentTarget.value)} autofocus />
                <Show when={results.error}>
                    <p class="error">{errorText(results.error)}</p>
                </Show>
                <Show when={results.loading && !results.latest.length}>
                    <p class="muted">Looking…</p>
                </Show>
                <Show when={!results.loading && !results.error && !results().length}>
                    <p class="muted">No videos found.</p>
                </Show>
                <VideoList items={results.latest} onPlay={props.onPlay} />
            </Show>
            <Show when={tab() === 'chats'}>
                <Show
                    when={chat()}
                    fallback={
                        <>
                            <Show when={dialogs.loading}>
                                <p class="muted">Loading chats…</p>
                            </Show>
                            <ul class="chats">
                                <For each={dialogs()}>
                                    {(d) => (
                                        <li>
                                            <button onClick={() => setChat(d.peer)}>{d.peer.displayName}</button>
                                        </li>
                                    )}
                                </For>
                            </ul>
                        </>
                    }
                >
                    <button class="link" onClick={() => setChat(null)}>‹ All chats</button>
                    <h2>{chat()!.displayName}</h2>
                    <Show when={inChat.loading}>
                        <p class="muted">Loading videos…</p>
                    </Show>
                    <Show when={inChat.error}>
                        <p class="error">{errorText(inChat.error)}</p>
                    </Show>
                    <Show when={!inChat.loading && inChat() && !inChat()!.length}>
                        <p class="muted">No videos in this chat.</p>
                    </Show>
                    <VideoList items={inChat() ?? []} onPlay={props.onPlay} />
                </Show>
            </Show>
        </main>
    )
}
