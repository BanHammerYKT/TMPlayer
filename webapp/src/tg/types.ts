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
