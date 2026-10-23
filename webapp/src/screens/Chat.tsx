import { createResource, Show } from 'solid-js'
import { useParams } from '@solidjs/router'
import { api, errorText } from '../tg/rpc'
import { Initial, PageBar } from '../ui/PageBar'
import { VideoList } from './VideoList'

export function Chat() {
    const params = useParams<{ chatId: string }>()
    const [chat] = createResource(() => Number(params.chatId), (id) => api.chatVideos(id))
    const title = () => (chat.error ? '' : (chat()?.title ?? ''))
    return (
        <>
            <PageBar title={title()} picture={<Initial name={title()} class="avatar compact" />} />
            <Show when={chat.loading}>
                <p class="muted">Loading videos…</p>
            </Show>
            <Show when={chat.error}>
                <p class="error">{errorText(chat.error)}</p>
            </Show>
            <Show when={!chat.loading && !chat.error && chat() && !chat()!.items.length}>
                <p class="muted">No videos in this chat.</p>
            </Show>
            <VideoList items={chat.error ? [] : (chat()?.items ?? [])} showChat={false} />
        </>
    )
}
