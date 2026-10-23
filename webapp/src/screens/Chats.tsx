import { createResource, For, Show } from 'solid-js'
import { A } from '@solidjs/router'
import { api, errorText } from '../tg/rpc'

export function Chats() {
    const [chats] = createResource(() => api.chats(60))
    return (
        <>
            <Show when={chats.loading}>
                <p class="muted">Loading chats…</p>
            </Show>
            <Show when={chats.error}>
                <p class="error">{errorText(chats.error)}</p>
            </Show>
            <ul class="chats">
                <For each={chats.error ? [] : chats()}>
                    {(c) => (
                        <li>
                            <A href={`/chat/${c.id}`}>{c.title}</A>
                        </li>
                    )}
                </For>
            </ul>
        </>
    )
}
