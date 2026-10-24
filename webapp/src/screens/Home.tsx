import { createResource, Show } from 'solid-js'
import { api, errorText } from '../tg/rpc'
import { t } from '../i18n'
import { settings } from '../store'
import { HiddenBySize, videoCard, VideoList } from './VideoList'

/** For now the latest videos across every chat; the app's rows (Continue, Recently added, starred chats) come in session 6. */
export function Home() {
    const [latest] = createResource(
        () => [settings().sizeMin, settings().sizeMax] as const,
        ([min, max]) => api.search('', min, max),
    )
    const found = () => (latest.error ? null : latest.latest)
    return (
        <>
            <h1 class="page-title">Home</h1>
            <h2 class="row-title">{t('home.row_recent_all')}</h2>
            <Show when={latest.error}>
                <p class="error">{errorText(latest.error)}</p>
            </Show>
            <Show when={latest.loading && !found()}>
                <p class="muted">{t('browse.finding_videos')}</p>
            </Show>
            <HiddenBySize count={found()?.hiddenBySize ?? 0} />
            <Show when={found() && !found()!.items.length}>
                <p class="muted">{t('home.first_body')}</p>
            </Show>
            <VideoList cards={found()?.items.map((v) => videoCard(v)) ?? []} />
        </>
    )
}
