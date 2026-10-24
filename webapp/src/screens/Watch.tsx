import { createMemo, createResource, createSignal, For, lazy, Match, Show, Switch } from 'solid-js'
import { A, useLocation, useNavigate, useParams } from '@solidjs/router'
import { api, errorText } from '../tg/rpc'
import { back } from '../session'
import { t } from '../i18n'
import { dropResume, progress as readProgress, settings } from '../store'
import { StarButton } from './Chats'
import { Card, length, size, tileCard, useProgress, watchPath } from './VideoList'
import type { ChatView, VideoItem, WatchInfo } from '../tg/types'

const Player = lazy(() => import('../player/Player').then((m) => ({ default: m.Player })))

const dot = '  ·  '

/**
 * One video, at `/watch/<chat>/<message>`: the player on top (beside the details on a wide
 * screen), then its name, chat and facts, and the show's episodes or more from the chat. Opened
 * from a list it starts at once with the item the list passed along; opened by its address (or
 * after the worker restarted) it asks Telegram for the message first. Back, Escape and the
 * iPhone's swipe back all close it; the next episode replaces this page, so Back still goes to
 * the list.
 */
export function Watch() {
    const params = useParams<{ chatId: string; messageId: string }>()
    const location = useLocation<{ video?: VideoItem }>()
    const navigate = useNavigate()
    const ids = () => ({ chatId: Number(params.chatId), messageId: Number(params.messageId) })
    const [video] = createResource(ids, async ({ chatId, messageId }) => {
        const passed = location.state?.video
        if (passed?.chatId === chatId && passed.messageId === messageId && (await api.known(passed.key))) return passed
        const found = await api.video(chatId, messageId)
        // Gone from the chat: Continue watching forgets it too.
        if (found === null) dropResume(chatId, messageId)
        return found
    })
    const [progress] = createResource(readProgress)
    const view = (): ChatView => ({
        sort: settings().sort,
        minBytes: settings().sizeMin,
        maxBytes: settings().sizeMax,
        watched: progress()?.watched ?? [],
        started: progress()?.started ?? [],
        query: '',
    })
    // The details wait for the chat's videos; the player does not wait for them.
    const [info] = createResource(
        () => (progress.state === 'ready' ? { ...ids(), view: view() } : null),
        ({ chatId, messageId, view }) => api.watch(chatId, messageId, view).catch((e) => (console.warn('[watch]', e), null)),
    )
    const close = () => back(navigate)
    const play = (next: VideoItem) => navigate(watchPath(next), { state: { video: next }, replace: true })

    return (
        <Switch fallback={<div class="watch"><div class="watch-player"><div class="player"><div class="spinner" aria-label="Loading" /></div></div></div>}>
            <Match when={video.error}>
                <div class="boot">
                    <p class="error">{errorText(video.error)}</p>
                    <button class="primary" onClick={close}>{t('common.back')}</button>
                </div>
            </Match>
            <Match when={video.state === 'ready' && video() === null}>
                <div class="boot">
                    <p>{t('web.gone_from_chat')}</p>
                    <button class="primary" onClick={close}>{t('common.back')}</button>
                </div>
            </Match>
            <Match when={video()} keyed>
                {(item) => (
                    <div class="watch">
                        <div class="watch-player">
                            <Player item={item} info={info() ?? null} onClose={close} onPlay={play} />
                        </div>
                        <Details item={item} info={info() ?? null} onPlay={play} />
                    </div>
                )}
            </Match>
        </Switch>
    )
}

/** Below the player, or beside it on a wide screen: what this is, where it is from, and what else to play. */
function Details(props: { item: VideoItem; info: WatchInfo | null; onPlay: (v: VideoItem) => void }) {
    const facts = () => {
        const i = props.info
        return [size(props.item.size), length(props.item.duration), i?.height ? `${i.width} x ${i.height}` : ''].filter(Boolean).join(dot)
    }
    return (
        <section class="watch-details">
            <h1 class="watch-title">{props.info?.title ?? props.item.title}</h1>
            <Show when={props.info?.episode}>
                {(ep) => <p class="watch-episode">{ep().code}{ep().name ? `${dot}${ep().name}` : ''}</p>}
            </Show>
            <div class="watch-chat">
                <A class="inline-link" href={`/chat/${props.item.chatId}`}>{props.info?.chatTitle ?? props.item.chat}</A>
                <StarButton chatId={props.item.chatId} />
            </div>
            <p class="muted small">{facts()}</p>
            <Show when={props.info?.protected || !props.item.canBeSaved}>
                <p class="notice muted small">{t('detail.protected')}</p>
            </Show>
            <Show when={props.info?.fileName && props.info.fileName !== props.info.title}>
                <p class="muted small file-name">{props.info!.fileName}</p>
            </Show>
            <Show when={props.info?.seasons.length} fallback={<More info={props.info} />}>
                <Episodes info={props.info!} current={props.item} onPlay={props.onPlay} />
            </Show>
        </section>
    )
}

/** The show's episodes, a season at a time, the one playing marked; each opens in place of this one. */
function Episodes(props: { info: WatchInfo; current: VideoItem; onPlay: (v: VideoItem) => void }) {
    const progress = useProgress()
    const seasonOf = () => props.info.seasons.find((s) => s.episodes.some((e) => e.item.messageId === props.current.messageId))?.number ?? props.info.seasons[0]?.number
    const [picked, setPicked] = createSignal<number | null>(null)
    const season = createMemo(() => props.info.seasons.find((s) => s.number === (picked() ?? seasonOf())) ?? props.info.seasons[0])
    return (
        <div class="episodes">
            <h2 class="section-title">{t('episodes.title')}</h2>
            <Show when={props.info.seasons.length > 1}>
                <div class="season-tabs" role="tablist">
                    <For each={props.info.seasons}>
                        {(s) => (
                            <button role="tab" class="season-chip" classList={{ on: s.number === season()?.number }} aria-selected={s.number === season()?.number} onClick={() => setPicked(s.number)}>
                                {t('series.season', { number: s.number })}
                            </button>
                        )}
                    </For>
                </div>
            </Show>
            <ol class="episode-list">
                <For each={season()?.episodes ?? []}>
                    {(e) => {
                        const playing = () => e.item.messageId === props.current.messageId && e.item.chatId === props.current.chatId
                        return (
                            <li>
                                <button class="episode" classList={{ playing: playing() }} aria-current={playing() ? 'true' : undefined} onClick={() => !playing() && props.onPlay(e.item)}>
                                    <span class="episode-code">{e.code}</span>
                                    <span class="episode-name">{playing() ? t('episodes.playing') : (e.name ?? length(e.item.duration))}</span>
                                    <Show when={progress(e.item)}>
                                        {(p) => <span class="episode-progress"><span style={{ width: `${Math.min(100, p() * 100)}%` }} /></span>}
                                    </Show>
                                </button>
                            </li>
                        )
                    }}
                </For>
            </ol>
        </div>
    )
}

/** Other videos of the chat, newest first, as cards. */
function More(props: { info: WatchInfo | null }) {
    const progress = useProgress()
    return (
        <Show when={props.info?.more.length}>
            <div class="more-from">
                <h2 class="section-title">{t('web.more_from_chat', { chat: props.info!.chatTitle })}</h2>
                <div class="cards more-cards">
                    <For each={props.info!.more}>{(tile) => <Card card={tileCard(tile)} progress={progress(tile.item)} replace />}</For>
                </div>
            </div>
        </Show>
    )
}
