import { createResource, For, Show } from 'solid-js'
import { A } from '@solidjs/router'
import { api, errorText } from '../tg/rpc'
import { Initial } from '../ui/PageBar'

export function Chats() {
    const [chats] = createResource(() => api.chats(60))
    return (
        <>
            <h1 class="page-title">Chats</h1>
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
                            <A class="chat-row" href={`/chat/${c.id}`}>
                                <Initial name={c.title} class="avatar" />
                                <span class="chat-name">{c.title}</span>
                            </A>
                        </li>
                    )}
                </For>
            </ul>
        </>
    )
}
