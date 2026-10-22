import { TelegramClient, TelegramWorkerPort } from '@mtcute/web'

const worker = new Worker(new URL('./worker.ts', import.meta.url), { type: 'module', name: 'telegram' })

export const tg = new TelegramClient({ client: new TelegramWorkerPort({ worker }) })

// Local testing only: the client in the console, and every raw update logged.
if (location.hostname === 'localhost') {
    ;(window as unknown as { tg: TelegramClient }).tg = tg
    tg.onServerUpdate.add((u) => console.debug('[tg update]', u._, 'update' in u ? (u.update as { _: string })._ : ''))
}

/** The signed in user, or null when this browser has no session yet. */
export async function currentUser() {
    try {
        return await tg.getMe()
    } catch (e) {
        if (isUnauthorized(e)) return null
        throw e
    }
}

export function isUnauthorized(e: unknown): boolean {
    const text = String((e as { code?: number; text?: string })?.text ?? e)
    return (e as { code?: number })?.code === 401 || /AUTH_KEY_UNREGISTERED|SESSION_REVOKED|USER_DEACTIVATED|AUTH_KEY_INVALID/.test(text)
}

export function errorText(e: unknown): string {
    const err = e as { text?: string; message?: string; seconds?: number }
    if (err?.text?.startsWith('FLOOD_WAIT') && err.seconds) return `Telegram asks to wait ${err.seconds} s.`
    return err?.text ?? err?.message ?? String(e)
}
