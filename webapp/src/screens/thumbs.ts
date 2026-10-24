import { api } from '../tg/rpc'
import { cachedThumb } from '../store'

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
