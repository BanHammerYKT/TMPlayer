import { createEffect, createSignal, For, onCleanup, onMount, Show } from 'solid-js'
import { A } from '@solidjs/router'
import { api } from '../tg/rpc'
import { cachedThumb } from '../store'
import { warmDataCentres, warmFile } from '../stream/reader'
import type { VideoItem } from '../tg/types'

export function size(bytes: number): string {
    if (bytes >= 1e9) return `${(bytes / 1e9).toFixed(1)} GB`
    return `${Math.max(1, Math.round(bytes / 1e6))} MB`
}

export function length(s: number): string {
    if (!s) return ''
    const h = Math.floor(s / 3600)
    const m = Math.round((s % 3600) / 60)
    return h ? `${h} h ${m} min` : `${m} min`
}

export const watchPath = (v: Pick<VideoItem, 'chatId' | 'messageId'>) => `/watch/${v.chatId}/${v.messageId}`

// Thumbnails: a few at a time, only for cards on screen, kept in the Cache API and for the session.
const thumbs = new Map<string, Promise<string>>()
let thumbsActive = 0
const thumbQueue: (() => void)[] = []
function loadThumb(key: string): Promise<string> {
    let p = thumbs.get(key)
    if (!p) {
        p = new Promise<void>((r) => (thumbsActive < 4 ? (thumbsActive++, r()) : thumbQueue.push(r)))
            .then(() => cachedThumb(key, () => api.thumb(key)))
            .then((blob) => URL.createObjectURL(blob))
            .catch((e) => {
                thumbs.delete(key)
                throw e
            })
            .finally(() => {
                const next = thumbQueue.shift()
                if (next) next()
                else thumbsActive--
            })
        thumbs.set(key, p)
    }
    return p
}

function Thumb(props: { thumb: string | null }) {
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

export function VideoList(props: { items: VideoItem[] }) {
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
    onCleanup(() => clearTimeout(hoverTimer))
    return (
        <ul class="videos">
            <For each={props.items}>
                {(v) => (
                    <li>
                        <A
                            class="video"
                            href={watchPath(v)}
                            state={{ video: v }}
                            onPointerEnter={() => intent(v)}
                            onPointerLeave={() => clearTimeout(hoverTimer)}
                            onFocus={() => intent(v)}
                            onPointerDown={() => warmFile(v)}
                        >
                            <Thumb thumb={v.thumb} />
                            <div class="meta">
                                <div class="name">{v.title}</div>
                                <div class="muted small">{[v.chat, size(v.size), length(v.duration)].filter(Boolean).join(' · ')}</div>
                            </div>
                        </A>
                    </li>
                )}
            </For>
        </ul>
    )
}
