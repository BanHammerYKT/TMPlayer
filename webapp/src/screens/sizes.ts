// The size limits as the page shows them: the slider's stops and their labels, from the app's own
// SizeFilter in the worker, fetched once.
import { createResource, createRoot } from 'solid-js'
import { api } from '../tg/rpc'
import { t } from '../i18n'

const scale = createRoot(() => createResource(() => api.sizeScale())[0])
export { scale as sizeScale }

/** "50 MB", "No minimum", "No limit", as the app labels a limit. */
export function sizeLabel(bytes: number): string {
    return scale()?.stops.find((s) => s.bytes === bytes)?.label ?? ''
}

/** A list the limits emptied: "No videos here between 100 MB and 2 GB." */
export function noVideosWithin(minBytes: number, maxBytes: number): string {
    const top = scale()?.stops.at(-1)?.bytes ?? Infinity
    const min = sizeLabel(minBytes)
    const max = sizeLabel(maxBytes)
    if (minBytes > 0 && maxBytes < top) return t('browse.no_videos_between', { min, max })
    if (minBytes > 0) return t('browse.no_videos_from', { min })
    return t('browse.no_videos_up_to', { max })
}
