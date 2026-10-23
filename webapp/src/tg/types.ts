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
