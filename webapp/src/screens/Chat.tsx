import { createEffect, createResource, createSignal, For, on, onCleanup, Show } from 'solid-js'
import { useParams, useSearchParams } from '@solidjs/router'
import { Cancel01Icon, RefreshIcon, Search01Icon, Sorting01Icon, Tick02Icon } from '@hugeicons/core-free-icons'
import { call, errorText } from '../tg/rpc'
import { t } from '../i18n'
import { resumes, settings, setSetting, watchedIds } from '../store'
import { Icon } from '../ui/Icon'
import { PageBar } from '../ui/PageBar'
import { ChatPicture } from './ChatPicture'
import { chatName, StarButton } from './Chats'
import { noVideosWithin } from './sizes'
import { HiddenBySize, tileCard, VideoList } from './VideoList'
import type { ChatPage, ChatView, RpcError, Sort } from '../tg/types'

const SORTS: { value: Sort; key: Parameters<typeof t>[0] }[] = [
    { value: 'Newest', key: 'grid.sort_newest' },
    { value: 'Oldest', key: 'grid.sort_oldest' },
    { value: 'Name', key: 'grid.sort_name' },
    { value: 'Year', key: 'grid.sort_year' },
    { value: 'Size', key: 'grid.sort_size' },
    { value: 'Longest', key: 'grid.sort_longest' },
    { value: 'Unwatched', key: 'grid.sort_unwatched' },
]

/** Pages in a row that brought no new card before the list stops paging by itself and offers a button. */
const IDLE_PAGES = 3

/** Telegram's answer for a chat that cannot be read, in the app's words. */
export function chatError(e: unknown): string {
    const text = (e as Partial<RpcError>)?.text ?? ''
    if (/^CHANNEL_PRIVATE|^CHAT_FORBIDDEN|^USER_BANNED_IN_CHANNEL/.test(text)) return t('errors.channel_private')
    if (/^PEER_ID_INVALID|^CHANNEL_INVALID|^CHAT_ID_INVALID|^USERNAME_NOT_OCCUPIED/.test(text)) return t('errors.chat_gone')
    return errorText(e)
}

/**
 * One chat's videos as the app arranges them: shows, copies and pieces folded together by its
 * rules, in the chosen order, inside the size limits, with "N hidden by the size limits" when
 * they hide any. More loads near the foot; the words to look for are in the address (`?q=`).
 */
export function Chat() {
    const params = useParams<{ chatId: string }>()
    const [search, setSearch] = useSearchParams<{ q?: string }>()
    const chatId = () => Number(params.chatId)
    const [page, setPage] = createSignal<ChatPage | null>(null)
    const [error, setError] = createSignal<unknown>(null)
    const [busy, setBusy] = createSignal<'open' | 'more' | 'refresh' | 'view' | null>(null)
    const [moreFailed, setMoreFailed] = createSignal(false)
    const [refreshWait, setRefreshWait] = createSignal<number | null>(null)
    const [idlePages, setIdlePages] = createSignal(0)
    const [searching, setSearching] = createSignal(!!search.q)
    const [progress] = createResource(async () => ({
        watched: await watchedIds(),
        started: (await resumes()).map((r) => `${r.chatId}:${r.messageId}`),
    }))
    const view = (): ChatView => ({
        sort: settings().sort,
        minBytes: settings().sizeMin,
        maxBytes: settings().sizeMax,
        watched: progress()?.watched ?? [],
        started: progress()?.started ?? [],
        query: search.q?.trim() ?? '',
    })

    let seq = 0
    const run = async (mode: 'open' | 'more' | 'refresh' | 'view') => {
        const mine = ++seq
        const id = chatId()
        const before = page()?.tiles.length ?? 0
        setBusy(mode)
        if (mode === 'more') setMoreFailed(false)
        try {
            const next = await call('chat', [id, view(), mode])
            if (mine !== seq || id !== chatId()) return
            setPage(next)
            setError(null)
            if (mode === 'more') setIdlePages(next.tiles.length > before ? 0 : idlePages() + 1)
            else if (mode !== 'view') setIdlePages(0)
        } catch (e) {
            if (mine !== seq) return
            const err = e as Partial<RpcError>
            if (mode === 'more') setMoreFailed(true)
            else if (mode === 'refresh' && err.text?.startsWith('FLOOD_WAIT') && page()) setRefreshWait(err.seconds ?? 0)
            else setError(e)
        } finally {
            if (mine === seq) setBusy(null)
        }
    }

    // A new chat (the address changed under the same page) starts afresh.
    createEffect(
        on(chatId, () => {
            setPage(null)
            setError(null)
            setIdlePages(0)
            setMoreFailed(false)
            run('open')
        }),
    )
    // Another order, size limits or words: the same videos arranged again, in the worker.
    createEffect(on(() => JSON.stringify(view()), () => page() && run('view'), { defer: true }))

    const atEnd = () => {
        const p = page()
        if (!p || p.done || busy() || moreFailed() || idlePages() >= IDLE_PAGES) return
        run('more')
    }

    let waitTimer = 0
    createEffect(() => {
        const w = refreshWait()
        clearTimeout(waitTimer)
        if (w != null) waitTimer = window.setTimeout(() => setRefreshWait(null), 4000)
    })
    onCleanup(() => clearTimeout(waitTimer))

    const title = () => (page() ? chatName(page()!) : '')
    const cards = () => page()?.tiles.map(tileCard) ?? []
    const empty = () => {
        const p = page()
        if (!p || p.tiles.length || !p.done) return null
        if (view().query) return t('browse.no_match', { query: view().query })
        if (p.hiddenBySize) return noVideosWithin(view().minBytes, view().maxBytes)
        return t('browse.no_playable_videos')
    }

    let debounce = 0
    let typing = false
    const [text, setText] = createSignal(search.q ?? '')
    onCleanup(() => clearTimeout(debounce))
    const onType = (v: string) => {
        setText(v)
        typing = true
        clearTimeout(debounce)
        debounce = window.setTimeout(() => {
            typing = false
            setSearch({ q: v || undefined }, { replace: true })
        }, 200)
    }
    createEffect(on(() => search.q, (q) => !typing && setText(q ?? ''), { defer: true }))
    const closeSearch = () => {
        setSearching(false)
        setText('')
        setSearch({ q: undefined }, { replace: true })
    }

    return (
        <>
            <PageBar
                title={title()}
                picture={<ChatPicture photo={page()?.photo ?? null} name={title()} self={page()?.self} class="avatar compact" />}
                actions={
                    <>
                        <StarButton chatId={chatId()} round />
                        <SortMenu />
                        <button
                            class="round-button"
                            type="button"
                            aria-pressed={searching()}
                            aria-label={t('browse.search_chat')}
                            title={t('browse.search_chat')}
                            onClick={() => (searching() ? closeSearch() : setSearching(true))}
                        >
                            <Icon icon={searching() ? Cancel01Icon : Search01Icon} size={22} />
                        </button>
                        <button
                            class="round-button"
                            classList={{ spinning: busy() === 'refresh' }}
                            type="button"
                            disabled={busy() === 'refresh' || busy() === 'open'}
                            aria-label={t('common.refresh')}
                            title={t('common.refresh')}
                            onClick={() => run('refresh')}
                        >
                            <Icon icon={RefreshIcon} size={22} />
                        </button>
                    </>
                }
            />
            <Show when={searching()}>
                <label class="search-field">
                    <Icon icon={Search01Icon} size={20} />
                    <input
                        type="search"
                        placeholder={t('browse.search_chat')}
                        aria-label={t('browse.search_chat')}
                        value={text()}
                        onInput={(e) => onType(e.currentTarget.value)}
                        onKeyDown={(e) => e.key === 'Escape' && closeSearch()}
                        ref={(el) => queueMicrotask(() => !search.q && el.focus())}
                    />
                </label>
            </Show>
            <Show when={refreshWait() != null}>
                <p class="notice" role="status">{t('chats.refresh_wait', { seconds: refreshWait()! })}</p>
            </Show>
            <Show when={busy() === 'open' && !page()}>
                <p class="muted">{t('browse.finding_videos')}</p>
            </Show>
            <Show when={error()}>
                <div class="notice error-notice">
                    <p class="error">{chatError(error())}</p>
                    <button class="link" type="button" onClick={() => run(page() ? 'refresh' : 'open')}>{t('common.try_again')}</button>
                </div>
            </Show>
            <HiddenBySize count={view().query ? 0 : (page()?.hiddenBySize ?? 0)} />
            <Show when={page()?.selfDestructing}>
                <p class="notice muted small">{t('grid.self_destructing_hidden', { count: page()!.selfDestructing })}</p>
            </Show>
            <Show when={empty()}>
                <p class="muted">{empty()}</p>
            </Show>
            <VideoList cards={cards()} label={title()} onEnd={atEnd} />
            <div class="list-foot">
                <Show when={busy() === 'more'}>
                    <p class="muted">{t('grid.loading_more')}</p>
                </Show>
                <Show when={moreFailed()}>
                    <p class="error">{t('grid.more_failed')}</p>
                    <button class="link" type="button" onClick={() => run('more')}>{t('common.try_again')}</button>
                </Show>
                <Show when={page() && !page()!.done && !busy() && !moreFailed() && idlePages() >= IDLE_PAGES}>
                    <button class="link" type="button" onClick={() => run('more')}>{t('common.more')}</button>
                </Show>
            </div>
            <Show when={page()?.archives.length}>
                <details class="archives">
                    <summary>{t('grid.archives_heading', { count: page()!.archives.length })}</summary>
                    <ul>
                        <For each={page()!.archives}>{(name) => <li>{name}</li>}</For>
                    </ul>
                </details>
            </Show>
        </>
    )
}

/** Sort videos by: the app's seven orders in one list, the chosen one ticked. Kept for every chat. */
function SortMenu() {
    const [open, setOpen] = createSignal(false)
    let box!: HTMLDivElement
    const close = (e: Event) => {
        if (e instanceof KeyboardEvent ? e.key === 'Escape' : !box.contains(e.target as Node)) setOpen(false)
    }
    createEffect(() => {
        if (!open()) return
        document.addEventListener('pointerdown', close)
        document.addEventListener('keydown', close)
        onCleanup(() => {
            document.removeEventListener('pointerdown', close)
            document.removeEventListener('keydown', close)
        })
    })
    return (
        <div class="menu-box" ref={box}>
            <button
                class="round-button"
                type="button"
                aria-haspopup="true"
                aria-expanded={open()}
                aria-label={t('grid.sort_label')}
                title={t('grid.sort_label')}
                onClick={() => setOpen(!open())}
            >
                <Icon icon={Sorting01Icon} size={22} />
            </button>
            <Show when={open()}>
                <div class="pop-menu" role="menu" aria-label={t('grid.sort_label')}>
                    <p class="pop-title">{t('grid.sort_label')}</p>
                    <For each={SORTS}>
                        {(s) => (
                            <button
                                type="button"
                                role="menuitemradio"
                                aria-checked={settings().sort === s.value}
                                classList={{ on: settings().sort === s.value }}
                                onClick={() => {
                                    setSetting('sort', s.value)
                                    setOpen(false)
                                }}
                            >
                                <span>{t(s.key)}</span>
                                <Show when={settings().sort === s.value}>
                                    <Icon icon={Tick02Icon} size={20} />
                                </Show>
                            </button>
                        )}
                    </For>
                </div>
            </Show>
        </div>
    )
}
