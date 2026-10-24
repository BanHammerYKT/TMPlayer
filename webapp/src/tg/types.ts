// What the Telegram worker hands the page: plain data only. File locations, access hashes and file
// references stay in the worker; the page names a file by its key.

export interface Me {
    id: number
    /** Null until Telegram has been asked. */
    name: string | null
}

/** A file the page can ask the worker for, by key. */
export interface FileRef {
    key: string
    size: number
}

/** One of Telegram's own re-encoded MP4 copies of a video (H.264 or AV1, 720p and 480p). */
export interface VideoCopy extends FileRef {
    width: number
    height: number
    /** `avc1`, `av01`... as Telegram names it, or null when it does not say. */
    codec: string | null
}

/** Telegram's scrub preview: a JPEG sprite and a map of `time,x,y` per frame. */
export interface Storyboard {
    sprite: FileRef
    map: FileRef
}

export interface VideoItem extends FileRef {
    chatId: number
    messageId: number
    duration: number
    title: string
    chat: string
    /** Milliseconds since 1970. */
    date: number
    /** Key of the thumbnail, for `thumb`. */
    thumb: string | null
    /** Telegram's own lighter MP4 copies, best first, when the video was posted as a video. */
    copies: VideoCopy[]
    storyboard: Storyboard | null
}

export interface ChatItem {
    id: number
    title: string
    /** Key of the chat's small picture, for `thumb`. */
    photo: string | null
    pinned: boolean
    kind: 'user' | 'bot' | 'group' | 'channel'
    /** A group with topics: its videos are listed across all of them. */
    forum: boolean
    /** Saved Messages, which the page names in its own words. */
    self: boolean
}

/** A chat list loaded part way: what has come so far, sent as `chats` runs. */
export interface ChatsEvent {
    chats: ChatItem[]
}

/** Video sort orders, as the app's VideoSort names them. */
export type Sort = 'Newest' | 'Oldest' | 'Name' | 'Year' | 'Size' | 'Longest' | 'Unwatched'

/** How a chat's grid is asked for: the app's order, size limits and progress, and words to look for. */
export interface ChatView {
    sort: Sort
    minBytes: number
    maxBytes: number
    /** Video ids ("chatId:messageId") finished, and started. */
    watched: string[]
    started: string[]
    query: string
}

/**
 * One card of a chat's grid, as the app's shelf groups it. A show plays `item`, its episode up
 * next; a file plays `item` itself.
 */
export interface Tile {
    key: string
    kind: 'show' | 'file'
    title: string
    item: VideoItem
    seasons: number
    episodes: number
    /** Other copies of the same video, parts it was cut into, and raw pieces that play as one. */
    copies: number
    parts: number
    pieces: number
    /** "Trailer", "Sample"... for an extra. */
    extra: string | null
}

export interface ChatPage {
    title: string
    self: boolean
    /** Key of the chat's small picture, for `thumb`. */
    photo: string | null
    tiles: Tile[]
    /** Videos the size limits left out of `tiles`. */
    hiddenBySize: number
    /** Videos loaded so far, before the limits. */
    loaded: number
    /** Every video in the chat is loaded. */
    done: boolean
    /** Names of compressed files posted in the chat, which cannot be played. */
    archives: string[]
    selfDestructing: number
}

/** A search's videos within the size limits, and how many the limits hid. */
export interface Found {
    items: VideoItem[]
    hiddenBySize: number
}

/** A country for the sign in's number, from Telegram's own list (help.getCountriesList). */
export interface Country {
    /** ISO 3166-1 alpha-2, as Telegram spells it: "IN", "GB". */
    iso: string
    name: string
    /** Every calling code that reaches it, most common first. */
    codes: DialCode[]
}

export interface DialCode {
    /** Digits, no "+". */
    code: string
    /** Area codes that mean this country where the calling code is shared ("204" for Canada under "1"). */
    prefixes: string[]
    /** How a national number is grouped, "XXX XXX XXXX"; a digit in it must match the number. */
    patterns: string[]
}

/**
 * A code Telegram sent, as mtcute's SentCode names its delivery: `app`, `sms`, `call`,
 * `flash_call`, `missed_call`, `email`, `email_required`, `fragment`, `firebase`, `sms_word`,
 * `sms_phrase`; `email_setup` for the code sent to an address given at sign in.
 */
export interface SentCode {
    hash: string
    type: string
    /** What a resend would use, or `none`. */
    next: string
    /** Seconds before a resend is allowed; 0 when Telegram does not say. */
    timeout: number
    /** 0 when unknown. */
    length: number
    /** The masked address for an email code. */
    email: string | null
    /** The start of a word or phrase sent by text. */
    beginning: string | null
}

/** Events the QR sign in sends while it runs. */
export type QrEvent =
    | { kind: 'url'; url: string; expires: number }
    | { kind: 'scanned' }
    | { kind: 'password'; hint: string | null }
    | { kind: 'badPassword' }

/** A Telegram or worker error, as it crosses to the page. */
export interface RpcError {
    message: string
    text?: string
    code?: number
    seconds?: number
}
