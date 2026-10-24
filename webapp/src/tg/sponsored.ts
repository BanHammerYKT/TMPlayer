// Worker only: Telegram's sponsored messages in channels, kept shown as its terms ask (AGENTS.md,
// "Telegram's terms"), with the same calls as the apps' SponsoredMessageRepository: loaded with
// the channel, counted as viewed once seen, a press told to Telegram before the link opens, and
// a report with Telegram's own reasons. The random ids stay here; the page names each by `id`.
import { _messageMediaFromTl, Photo, type FileLocation, type TelegramClient, type tl } from '@mtcute/web'
import { thumbnails } from './media'
import type { SponsoredItem, SponsoredReport } from './types'

const known = new Map<string, Uint8Array>()
const viewed = new Set<string>()

export function forgetSponsored() {
    known.clear()
    viewed.clear()
}

const hex = (b: Uint8Array) => Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('')

/** A picture small enough for the card: Telegram's 320 px size, else whatever it has. */
function pictureOf(m: tl.RawSponsoredMessage): FileLocation | null {
    const media = m.photo?._ === 'photo' ? new Photo(m.photo) : m.media ? _messageMediaFromTl(null, m.media) : null
    if (!media || !('getThumbnail' in media)) return null
    const thumbs = media as { getThumbnail(type: string): FileLocation | null; thumbnails: readonly FileLocation[] }
    return thumbs.getThumbnail('m') ?? thumbs.getThumbnail('x') ?? thumbs.thumbnails[0] ?? null
}

/** The channel's sponsored messages and Telegram's spacing for them; none for a chat that is not a channel, or on failure. */
export async function loadSponsored(tg: TelegramClient, chatId: number): Promise<{ items: SponsoredItem[]; between: number }> {
    try {
        const res = await tg.call({ _: 'messages.getSponsoredMessages', peer: await tg.resolvePeer(chatId) })
        if (res._ !== 'messages.sponsoredMessages') return { items: [], between: 0 }
        const items = res.messages.map((m): SponsoredItem => {
            const id = hex(m.randomId)
            known.set(id, m.randomId)
            const picture = pictureOf(m)
            const thumb = picture ? `sp${id}` : null
            if (picture && thumb) thumbnails.set(thumb, picture)
            return {
                id,
                recommended: !!m.recommended,
                title: m.title,
                text: m.message,
                buttonText: m.buttonText,
                sponsorInfo: m.sponsorInfo ?? '',
                additionalInfo: m.additionalInfo ?? '',
                url: m.url,
                canReport: !!m.canReport,
                thumb,
            }
        })
        return { items, between: Math.max(0, res.postsBetween ?? 0) }
    } catch {
        return { items: [], between: 0 }
    }
}

/** Counts it as seen, once, which is how Telegram counts its impressions. */
export async function viewSponsored(tg: TelegramClient, id: string): Promise<void> {
    const randomId = known.get(id)
    if (!randomId || viewed.has(id)) return
    viewed.add(id)
    await tg.call({ _: 'messages.viewSponsoredMessage', randomId }).catch(() => viewed.delete(id))
}

export async function clickSponsored(tg: TelegramClient, id: string, media: boolean): Promise<void> {
    const randomId = known.get(id)
    if (randomId) await tg.call({ _: 'messages.clickSponsoredMessage', randomId, media }).catch(() => {})
}

/** A report: first with no reason, which Telegram answers with its reasons, then with the one picked. */
export async function reportSponsored(tg: TelegramClient, id: string, option: number[]): Promise<SponsoredReport> {
    const randomId = known.get(id)
    if (!randomId) return { kind: 'unavailable' }
    try {
        const res = await tg.call({ _: 'messages.reportSponsoredMessage', randomId, option: new Uint8Array(option) })
        if (res._ === 'channels.sponsoredMessageReportResultChooseOption') {
            return { kind: 'options', title: res.title, options: res.options.map((o) => ({ text: o.text, option: Array.from(o.option) })) }
        }
        return { kind: res._ === 'channels.sponsoredMessageReportResultAdsHidden' ? 'hidden' : 'reported' }
    } catch (e) {
        return { kind: /PREMIUM_ACCOUNT_REQUIRED/.test(String((e as { text?: string })?.text)) ? 'premium' : 'unavailable' }
    }
}
