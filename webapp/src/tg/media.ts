// Worker only: turns Telegram messages into the page's plain items and remembers where each file's
// bytes are, so the page can ask for them by key.
import type { Message, Thumbnail, tl } from '@mtcute/web'
import type { FileRef, Storyboard, VideoCopy, VideoItem } from './types'

/** Where a Telegram document's bytes are. */
export interface FileLoc {
    location: tl.TypeInputFileLocation
    dcId: number
    size: number
}

/** Every file the page has been told about, by key; refreshed each time a list names it again. */
export const files = new Map<string, FileLoc>()
export const thumbnails = new Map<string, Thumbnail>()

function docRef(d: tl.RawDocument): FileRef {
    const key = `doc${d.id.toString()}`
    files.set(key, {
        location: { _: 'inputDocumentFileLocation', id: d.id, accessHash: d.accessHash, fileReference: d.fileReference, thumbSize: '' },
        dcId: d.dcId,
        size: d.size,
    })
    return { key, size: d.size }
}

/** The copies and storyboard Telegram made for a video posted as a video (its `alt_documents`). */
function alternatives(media: tl.TypeMessageMedia | undefined): { copies: VideoCopy[]; storyboard: Storyboard | null } {
    const alts = media?._ === 'messageMediaDocument' ? (media.altDocuments ?? []) : []
    const copies: VideoCopy[] = []
    let sprite: FileRef | null = null
    let map: FileRef | null = null
    for (const a of alts) {
        if (a._ !== 'document') continue
        if (a.mimeType === 'video/mp4') {
            const v = a.attributes.find((t): t is tl.RawDocumentAttributeVideo => t._ === 'documentAttributeVideo')
            copies.push({ ...docRef(a), width: v?.w ?? 0, height: v?.h ?? 0, codec: v?.videoCodec ?? null })
        } else if (a.mimeType === 'application/x-tgstoryboard') sprite = docRef(a)
        else if (a.mimeType === 'application/x-tgstoryboardmap') map = docRef(a)
    }
    copies.sort((a, b) => b.height - a.height || (a.codec === 'avc1' ? -1 : 1))
    return { copies, storyboard: sprite && map ? { sprite, map } : null }
}

const VIDEO_NAME = /\.(mkv|mp4|m4v|mov|webm|avi|ts|m2ts|wmv|flv|3gp)$/i

/** A message's video, posted as a video or as a file, or null. Restricted messages are skipped. */
export function toItem(msg: Message): VideoItem | null {
    if ((msg.raw as { restrictionReason?: unknown[] }).restrictionReason?.length) return null
    const m = msg.media
    if (!m || (m.type !== 'video' && m.type !== 'document')) return null
    if (m.type === 'video' && (m.isRound || m.isAnimation)) return null
    if (m.type === 'document' && !m.mimeType.startsWith('video/') && !VIDEO_NAME.test(m.fileName ?? '')) return null
    const name = m.fileName?.replace(/\.[^.]+$/, '').replace(/[._]+/g, ' ').trim()
    const caption = msg.text.split('\n')[0]?.trim()
    const key = m.uniqueFileId
    const size = m.fileSize ?? 0
    files.set(key, {
        location: (typeof m.location === 'function' ? m.location() : m.location) as tl.TypeInputFileLocation,
        dcId: m.dcId ?? 2,
        size,
    })
    const thumb = m.getThumbnail('m') ?? m.thumbnails[0] ?? null
    if (thumb) thumbnails.set(thumb.uniqueFileId, thumb)
    return {
        key,
        size,
        chatId: msg.chat.id,
        messageId: msg.id,
        duration: m.type === 'video' ? m.duration : 0,
        title: name || caption || 'Video',
        chat: msg.chat.displayName,
        date: msg.date.getTime(),
        thumb: thumb?.uniqueFileId ?? null,
        ...alternatives(msg.raw._ === 'message' ? msg.raw.media : undefined),
    }
}

/** Items from several searches: each file once, newest first. */
export function merge(messages: Message[]): VideoItem[] {
    const seen = new Set<string>()
    const out: VideoItem[] = []
    for (const msg of messages) {
        const item = toItem(msg)
        if (!item || seen.has(item.key)) continue
        seen.add(item.key)
        out.push(item)
    }
    return out.sort((a, b) => b.date - a.date)
}
