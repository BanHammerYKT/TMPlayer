import { createEffect, createResource, createSignal, on, onCleanup, Show } from 'solid-js'
import { useSearchParams } from '@solidjs/router'
import { api, errorText } from '../tg/rpc'
import { VideoList } from './VideoList'

/** Videos across every chat. The words are in the address (`?q=`), so Back returns to the same results. */
export function Search() {
    const [params, setParams] = useSearchParams<{ q?: string }>()
    const [text, setText] = createSignal(params.q ?? '')
    const [results] = createResource(
        () => params.q?.trim() || false,
        (q) => api.search(q),
        { initialValue: [] },
    )
    let debounce = 0
    let typing = false
    onCleanup(() => clearTimeout(debounce))
    const onType = (v: string) => {
        setText(v)
        typing = true
        clearTimeout(debounce)
        // Typing replaces the entry instead of adding one per word.
        debounce = window.setTimeout(() => {
            typing = false
            setParams({ q: v || undefined }, { replace: true })
        }, 350)
    }
    // Back or Forward to other words puts them in the box, unless the box is being typed in.
    createEffect(on(() => params.q, (q) => !typing && setText(q ?? ''), { defer: true }))
    return (
        <>
            <input class="search" type="search" placeholder="Search videos in your chats" value={text()} onInput={(e) => onType(e.currentTarget.value)} autofocus />
            <Show when={results.error}>
                <p class="error">{errorText(results.error)}</p>
            </Show>
            <Show when={results.loading && !results.error && !results.latest.length}>
                <p class="muted">Looking…</p>
            </Show>
            <Show when={params.q?.trim() && !results.loading && !results.error && !results().length}>
                <p class="muted">No videos found.</p>
            </Show>
            <VideoList items={params.q?.trim() && !results.error ? results.latest : []} />
        </>
    )
}
