import { createEffect, createResource, createSignal, on, onCleanup, Show } from 'solid-js'
import { useSearchParams } from '@solidjs/router'
import { api, errorText } from '../tg/rpc'
import { Search01Icon } from '@hugeicons/core-free-icons'
import { t } from '../i18n'
import { settings } from '../store'
import { Icon } from '../ui/Icon'
import { HiddenBySize, videoCard, VideoList } from './VideoList'

/** Videos across every chat. The words are in the address (`?q=`), so Back returns to the same results. */
export function Search() {
    const [params, setParams] = useSearchParams<{ q?: string }>()
    const [text, setText] = createSignal(params.q ?? '')
    const [results] = createResource(
        () => {
            const q = params.q?.trim()
            return q ? ([q, settings().sizeMin, settings().sizeMax] as const) : false
        },
        ([q, min, max]) => api.search(q, min, max),
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
    const found = () => (params.q?.trim() && !results.error ? results.latest : undefined)
    return (
        <>
            <h1 class="page-title">{t('common.search')}</h1>
            <label class="search-field">
                <Icon icon={Search01Icon} size={20} />
                <input type="search" placeholder={t('search.field_hint')} aria-label={t('search.field_hint')} value={text()} onInput={(e) => onType(e.currentTarget.value)} autofocus />
            </label>
            <Show when={results.error}>
                <p class="error">{errorText(results.error)}</p>
            </Show>
            <Show when={results.loading && !found()}>
                <p class="muted">{t('browse.finding_videos')}</p>
            </Show>
            <HiddenBySize count={found()?.hiddenBySize ?? 0} />
            <Show when={found() && !results.loading && !found()!.items.length}>
                <p class="muted">{t('search.none', { query: params.q!.trim() })}</p>
            </Show>
            <VideoList cards={found()?.items.map((v) => videoCard(v)) ?? []} />
        </>
    )
}
