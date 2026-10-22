// The Telegram connection: MTProto, crypto and the session live here, off the main thread.
import { BaseTelegramClient, TelegramWorker } from '@mtcute/web'

const client = new BaseTelegramClient({
    apiId: __TG_API_ID__,
    apiHash: __TG_API_HASH__,
    storage: 'tmplayer',
    initConnectionOptions: {
        deviceModel: 'TMPlayer Web',
        appVersion: '2.0',
        systemVersion: navigator.userAgent.includes('iPhone') ? 'iOS' : 'Web',
    },
    network: {
        // Three download connections per DC, as Telegram's own web clients use for a free
        // account; more only invites flood waits.
        connectionCount: (kind, _dc, isPremium) => {
            if (kind === 'download') return isPremium ? 6 : 3
            if (kind === 'downloadSmall') return 2
            if (kind === 'upload') return 2
            return 0
        },
        inactivityTimeout: 300_000,
    },
})

new TelegramWorker({ client }).mount()
