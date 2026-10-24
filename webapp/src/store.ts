// What the web player keeps on this device: settings, where each video was left, what was watched
// and the starred chats, in one IndexedDB store (idb-keyval); thumbnails in the Cache API. When the
// browser refuses storage (private mode, quota full) everything still works for the session from
// memory. Sign out clears all of it.
import { createSignal } from 'solid-js'
import { clear, createStore, del, entries, get, set, type UseStore } from 'idb-keyval'
import type { Sort } from './tg/types'

export type Theme = 'system' | 'dark' | 'light' | 'black'

export interface Settings {
    theme: Theme
    /**
     * The app's size limits (SizeFilter), in bytes: smaller videos are left out of every list,
     * and larger ones unless the top is at its ceiling, which means no limit.
     */
    sizeMin: number
    sizeMax: number
    sort: Sort
    /** Taken off a saved position when a video resumes, in milliseconds; null for the app's default. */
    rewindMs: number | null
}

export interface Resume {
    chatId: number
    messageId: number
    /** Seconds into the video. */
    position: number
    /** Seconds, 0 when unknown. */
    length: number
    /** When it was left, milliseconds since 1970. */
    time: number
}

const MB = 1024 * 1024
/** SizeFilter.DEFAULT_MIN and DEFAULT_MAX: 50 MB up, no top. */
export const SIZE_DEFAULTS = { sizeMin: 50 * MB, sizeMax: 8 * 1024 * MB }
const DEFAULTS: Settings = { theme: 'system', ...SIZE_DEFAULTS, sort: 'Newest', rewindMs: null }
const THEME_KEY = 'tmplayer-theme'

let db: UseStore | null = null
try {
    db = createStore('tmplayer-app', 'kv')
} catch {
    db = null
}
/** The session's copy of everything, so a browser without storage still works until it closes. */
const memory = new Map<string, unknown>()

async function read<T>(key: string): Promise<T | undefined> {
    if (memory.has(key)) return memory.get(key) as T
    if (!db) return undefined
    try {
        const v = await get<T>(key, db)
        if (v !== undefined) memory.set(key, v)
        return v
    } catch {
        return undefined
    }
}

function write(key: string, value: unknown) {
    memory.set(key, value)
    if (db) set(key, value, db).catch((e) => console.warn('[store] not saved', key, e))
}

function remove(key: string) {
    memory.delete(key)
    if (db) del(key, db).catch(() => {})
}

async function all<T>(prefix: string): Promise<T[]> {
    return [...(await allEntries<T>(prefix)).values()]
}

async function allEntries<T>(prefix: string): Promise<Map<string, T>> {
    const out = new Map<string, T>()
    if (db) {
        try {
            for (const [k, v] of await entries<string, T>(db)) if (String(k).startsWith(prefix)) out.set(String(k), v)
        } catch {}
    }
    for (const [k, v] of memory) if (k.startsWith(prefix)) out.set(k, v as T)
    return out
}

// Settings

const [settings, setSettings] = createSignal<Settings>({ ...DEFAULTS, theme: savedTheme() })
export { settings }

/** The theme is also kept in localStorage, so index.html paints the first frame in it. */
function savedTheme(): Theme {
    try {
        const t = localStorage.getItem(THEME_KEY)
        if (t === 'dark' || t === 'light' || t === 'black' || t === 'system') return t
    } catch {}
    return DEFAULTS.theme
}

function applyTheme(theme: Theme) {
    const root = document.documentElement
    if (theme === 'system') delete root.dataset.theme
    else root.dataset.theme = theme
    // The browser's own bars take the page's ground: index.html's pair follow the device, a chosen theme pins both.
    const ground = { dark: '#0c0e10', light: '#f6fafd', black: '#000000' }
    document.querySelectorAll<HTMLMetaElement>('meta[name=theme-color]').forEach((m) => {
        m.content = theme === 'system' ? (m.media.includes('light') ? ground.light : ground.dark) : ground[theme]
    })
    try {
        if (theme === 'system') localStorage.removeItem(THEME_KEY)
        else localStorage.setItem(THEME_KEY, theme)
    } catch {}
}

export async function loadSettings() {
    const [saved, stars] = await Promise.all([read<Partial<Settings>>('settings'), starred()])
    setSettings({ ...DEFAULTS, ...saved })
    setStarredIds(new Set(stars))
    applyTheme(settings().theme)
}

export function setSetting<K extends keyof Settings>(key: K, value: Settings[K]) {
    const next = { ...settings(), [key]: value }
    setSettings(next)
    write('settings', next)
    if (key === 'theme') applyTheme(value as Theme)
}

// Where each video was left, and what was watched

const id = (chatId: number, messageId: number) => `${chatId}:${messageId}`

export function getResume(chatId: number, messageId: number): Promise<Resume | undefined> {
    return read<Resume>(`resume:${id(chatId, messageId)}`)
}

/** The position, or, near the end, the video as watched and its position dropped. */
export function saveResume(r: Omit<Resume, 'time'>) {
    const key = id(r.chatId, r.messageId)
    if (r.length > 0 && r.position > r.length - 30) {
        remove(`resume:${key}`)
        write(`watched:${key}`, Date.now())
    } else if (r.position > 10) {
        write(`resume:${key}`, { ...r, position: Math.floor(r.position), time: Date.now() })
    }
}

export function dropResume(chatId: number, messageId: number) {
    remove(`resume:${id(chatId, messageId)}`)
}

/** Videos left part way, the latest first: the Continue row. */
export async function resumes(): Promise<Resume[]> {
    return (await all<Resume>('resume:')).sort((a, b) => b.time - a.time)
}

/** Video ids ("chatId:messageId") finished, for the Unwatched order and a show's next episode. */
export async function watchedIds(): Promise<string[]> {
    return [...(await allEntries('watched:')).keys()].map((k) => k.slice('watched:'.length))
}

export async function isWatched(chatId: number, messageId: number): Promise<boolean> {
    return (await read(`watched:${id(chatId, messageId)}`)) !== undefined
}

// Starred chats

/** The starred chats, live, for every star drawn. */
const [starredIds, setStarredIds] = createSignal<ReadonlySet<number>>(new Set())
export { starredIds }

export function setStarred(chatId: number, on: boolean) {
    const next = new Set(starredIds())
    if (on) {
        next.add(chatId)
        write(`star:${chatId}`, { chatId, time: Date.now() })
    } else {
        next.delete(chatId)
        remove(`star:${chatId}`)
    }
    setStarredIds(next)
}

export async function starred(): Promise<number[]> {
    return (await all<{ chatId: number; time: number }>('star:')).sort((a, b) => a.time - b.time).map((s) => s.chatId)
}

// Thumbnails

const THUMBS = 'tmplayer-thumbs'

/** A thumbnail's JPEG from the Cache API, or from `fetch` (and then kept there). */
export async function cachedThumb(key: string, fetch: () => Promise<ArrayBuffer>): Promise<Blob> {
    const url = `/thumb/${encodeURIComponent(key)}`
    const cache = await globalThis.caches?.open(THUMBS).catch(() => undefined)
    const hit = await cache?.match(url).catch(() => undefined)
    if (hit) return hit.blob()
    const blob = new Blob([await fetch()], { type: 'image/jpeg' })
    cache?.put(url, new Response(blob)).catch(() => {})
    return blob
}

/** Sign out: settings, positions, watched, stars and thumbnails go; the theme falls back to the system's. */
export async function clearAll() {
    memory.clear()
    if (db) await clear(db).catch(() => {})
    await globalThis.caches?.delete(THUMBS).catch(() => {})
    setSettings({ ...DEFAULTS })
    setStarredIds(new Set<number>())
    applyTheme(DEFAULTS.theme)
}
