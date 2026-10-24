import { createEffect, createResource, createSignal, onCleanup, onMount, Show } from 'solid-js'
import { A } from '@solidjs/router'
import { api } from '../tg/rpc'
import { cachedThumb, resumes } from '../store'
import { warmDataCentres, warmFile } from '../stream/reader'
import { t } from '../i18n'
import { cardLayout, VirtualGrid } from '../ui/VirtualGrid'
import type { Tile, VideoItem } from '../tg/types'

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
export function loadThumb(key: string): Promise<string> {
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

/**
 * The art of a portrait card, as the app draws a video's: the frame across the card's width, the
 * same frame blurred to fill the 2:3 card round it, and the name on a shade at its foot.
 */
function CardArt(props: { thumb: string | null; title: string; progress?: number }) {
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
        <div class="card-art" ref={el}>
            <Show when={src()}>
                <img class="card-fill" src={src()!} alt="" decoding="async" />
                <img class="card-frame" src={src()!} alt="" decoding="async" />
            </Show>
            <div class="card-shade" />
            <div class="card-art-title">{props.title}</div>
            <Show when={props.progress}>
                <div class="card-progress">
                    <div style={{ width: `${Math.min(100, props.progress! * 100)}%` }} />
                </div>
            </Show>
        </div>
    )
}

/** One portrait card: what it is called, what it plays and its line of facts. */
export interface CardData {
    key: string
    title: string
    item: VideoItem
    facts: string
}

const dot = '  ·  '

/** A video on its own: its chat (where cards come from several), size and length. */
export function videoCard(v: VideoItem, showChat = true): CardData {
    return { key: v.key, title: v.title, item: v, facts: [showChat ? v.chat : '', size(v.size), length(v.duration)].filter(Boolean).join(dot) }
}

/** A tile of a chat's shelf: a show with its seasons and episodes, or a file with its parts. */
export function tileCard(tile: Tile): CardData {
    const v = tile.item
    const facts =
        tile.kind === 'show'
            ? t('series.summary', { seasons: tile.seasons, episodes: tile.episodes })
            : [
                  tile.parts > 1 ? t('grid.parts_count', { count: tile.parts }) : size(v.size),
                  length(v.duration),
                  tile.copies ? t('search.copies', { count: tile.copies + 1 }) : '',
              ]
                  .filter(Boolean)
                  .join(dot)
    return { key: tile.key, title: tile.title, item: v, facts }
}

/** A grid of portrait cards, drawn a few rows at a time. `onEnd` asks for more near the foot. */
export function VideoList(props: { cards: CardData[]; label?: string; onEnd?: () => void }) {
    // Once a list has drawn, connect to the data centres its files live in.
    let dcTimer = 0
    createEffect(() => {
        const items = props.cards.slice(0, 20).map((c) => c.item)
        clearTimeout(dcTimer)
        if (items.length) dcTimer = window.setTimeout(() => warmDataCentres(items), 1500)
    })
    onCleanup(() => clearTimeout(dcTimer))
    // A card pointed at for 250 ms, or pressed, starts fetching its file's head and tail.
    let hoverTimer = 0
    const intent = (v: VideoItem) => {
        clearTimeout(hoverTimer)
        hoverTimer = window.setTimeout(() => warmFile(v), 250)
    }
    onCleanup(() => clearTimeout(hoverTimer))
    // Where each video was left, for the line on its card.
    const [left] = createResource(async () => new Map((await resumes()).map((r) => [`${r.chatId}:${r.messageId}`, r])))
    const progress = (v: VideoItem) => {
        const r = left()?.get(`${v.chatId}:${v.messageId}`)
        const total = r?.length || v.duration
        return r && total ? r.position / total : undefined
    }
    return (
        <VirtualGrid items={props.cards} layout={cardLayout} rowClass="cards" label={props.label} onEnd={props.onEnd}>
            {(c) => (
                <A
                    class="card"
                    href={watchPath(c.item)}
                    state={{ video: c.item }}
                    onPointerEnter={() => intent(c.item)}
                    onPointerLeave={() => clearTimeout(hoverTimer)}
                    onFocus={() => intent(c.item)}
                    onPointerDown={() => warmFile(c.item)}
                >
                    <CardArt thumb={c.item.thumb} title={c.title} progress={progress(c.item)} />
                    <div class="card-name">{c.title}</div>
                    <div class="card-facts">{c.facts}</div>
                </A>
            )}
        </VirtualGrid>
    )
}

/** "3 videos hidden by the size limits. Change the minimum and maximum size in Settings." */
export function HiddenBySize(props: { count: number }) {
    return (
        <Show when={props.count}>
            <p class="notice muted small">
                {t('browse.hidden_by_size', { count: props.count })}{' '}
                <A class="inline-link" href="/settings#size">{t('browse.size_limits_in_settings')}</A>
            </p>
        </Show>
    )
}
