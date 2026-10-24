import { createResource, lazy, Match, Switch } from 'solid-js'
import { useLocation, useNavigate, useParams } from '@solidjs/router'
import { api, errorText } from '../tg/rpc'
import { back } from '../session'
import { dropResume } from '../store'
import type { VideoItem } from '../tg/types'

const Player = lazy(() => import('../player/Player').then((m) => ({ default: m.Player })))

/**
 * One video, at `/watch/<chat>/<message>`. Opened from a list it starts at once with the item the
 * list passed along; opened by its address (or after the worker restarted) it asks Telegram for
 * the message first. Back, Escape and the iPhone's swipe back all close it.
 */
export function Watch() {
    const params = useParams<{ chatId: string; messageId: string }>()
    const location = useLocation<{ video?: VideoItem }>()
    const navigate = useNavigate()
    const [video] = createResource(
        () => ({ chatId: Number(params.chatId), messageId: Number(params.messageId) }),
        async ({ chatId, messageId }) => {
            const passed = location.state?.video
            if (passed?.chatId === chatId && passed.messageId === messageId && (await api.known(passed.key))) return passed
            const found = await api.video(chatId, messageId)
            // Gone from the chat: Continue watching forgets it too.
            if (found === null) dropResume(chatId, messageId)
            return found
        },
    )
    const close = () => back(navigate)
    return (
        <Switch fallback={<div class="player"><div class="spinner" aria-label="Loading" /></div>}>
            <Match when={video.error}>
                <div class="boot">
                    <p class="error">{errorText(video.error)}</p>
                    <button class="primary" onClick={close}>Back</button>
                </div>
            </Match>
            <Match when={video.state === 'ready' && video() === null}>
                <div class="boot">
                    <p>This video is gone from the chat.</p>
                    <button class="primary" onClick={close}>Back</button>
                </div>
            </Match>
            <Match when={video()} keyed>
                {(item) => <Player item={item} onClose={close} />}
            </Match>
        </Switch>
    )
}
