import { createResource, Show } from 'solid-js'
import { api, errorText } from '../tg/rpc'
import { VideoList } from './VideoList'

/** For now the latest videos across every chat; the app's rows (Continue, Recently added, starred chats) come in session 6. */
export function Home() {
    const [latest] = createResource(() => api.search(''), { initialValue: [] })
    return (
        <>
            <Show when={latest.error}>
                <p class="error">{errorText(latest.error)}</p>
            </Show>
            <Show when={latest.loading && !latest.error && !latest.latest.length}>
                <p class="muted">Looking…</p>
            </Show>
            <Show when={!latest.loading && !latest.error && !latest().length}>
                <p class="muted">No videos found.</p>
            </Show>
            <VideoList items={latest.error ? [] : latest.latest} />
        </>
    )
}
