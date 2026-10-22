import type { tl } from '@mtcute/web'

/** Where a Telegram document's bytes are. */
export interface FileLoc {
    key: string
    location: tl.TypeInputFileLocation
    dcId: number
    size: number
}

/** One of Telegram's own re-encoded MP4 copies of a video (H.264 or AV1, 720p and 480p). */
export interface VideoCopy extends FileLoc {
    width: number
    height: number
    /** `avc1`, `av01`... as Telegram names it, or null when it does not say. */
    codec: string | null
}

/** Telegram's scrub preview: a JPEG sprite and a map of `time,x,y` per frame. */
export interface Storyboard {
    sprite: FileLoc
    map: FileLoc
}

export function docLoc(d: tl.RawDocument): FileLoc {
    return {
        key: `doc${d.id.toString()}`,
        location: { _: 'inputDocumentFileLocation', id: d.id, accessHash: d.accessHash, fileReference: d.fileReference, thumbSize: '' },
        dcId: d.dcId,
        size: d.size,
    }
}

/** The copies and storyboard Telegram made for a video posted as a video (its `alt_documents`). */
export function alternatives(media: tl.TypeMessageMedia | undefined): { copies: VideoCopy[]; storyboard: Storyboard | null } {
    const alts = media?._ === 'messageMediaDocument' ? (media.altDocuments ?? []) : []
    const copies: VideoCopy[] = []
    let sprite: FileLoc | null = null
    let map: FileLoc | null = null
    for (const a of alts) {
        if (a._ !== 'document') continue
        if (a.mimeType === 'video/mp4') {
            const v = a.attributes.find((t): t is tl.RawDocumentAttributeVideo => t._ === 'documentAttributeVideo')
            copies.push({ ...docLoc(a), width: v?.w ?? 0, height: v?.h ?? 0, codec: v?.videoCodec ?? null })
        } else if (a.mimeType === 'application/x-tgstoryboard') sprite = docLoc(a)
        else if (a.mimeType === 'application/x-tgstoryboardmap') map = docLoc(a)
    }
    copies.sort((a, b) => b.height - a.height || (a.codec === 'avc1' ? -1 : 1))
    return { copies, storyboard: sprite && map ? { sprite, map } : null }
}
