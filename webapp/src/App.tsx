import { createSignal, lazy, Match, onMount, Switch } from 'solid-js'
import type { User } from '@mtcute/web'
import { currentUser, errorText, tg } from './tg/client'
import { SignIn } from './screens/SignIn'
import { Pick, type VideoItem } from './screens/Pick'

// The player and its remuxer load when a video is opened (and are fetched while idle before that).
const Player = lazy(() => import('./player/Player').then((m) => ({ default: m.Player })))

type Screen = { name: 'boot' } | { name: 'signin' } | { name: 'pick' } | { name: 'error'; text: string }

export function App() {
    const [screen, setScreen] = createSignal<Screen>({ name: 'boot' })
    const [playing, setPlaying] = createSignal<VideoItem | null>(null)
    const [, setUser] = createSignal<User | null>(null)

    onMount(async () => {
        try {
            const user = await currentUser()
            setUser(user)
            setScreen(user ? { name: 'pick' } : { name: 'signin' })
            requestIdleCallback?.(() => import('./player/Player'))
        } catch (e) {
            setScreen({ name: 'error', text: errorText(e) })
        }
    })

    const signOut = async () => {
        setPlaying(null)
        await tg.logOut().catch(() => {})
        setUser(null)
        setScreen({ name: 'signin' })
    }

    return (
        <>
            <Switch>
                <Match when={screen().name === 'boot'}>
                    <div class="boot">TMPlayer</div>
                </Match>
                <Match when={screen().name === 'error'}>
                    <div class="boot">
                        <p class="error">{(screen() as { text: string }).text}</p>
                        <button class="primary" onClick={() => location.reload()}>Try again</button>
                    </div>
                </Match>
                <Match when={screen().name === 'signin'}>
                    <SignIn
                        onDone={(u) => {
                            setUser(u)
                            setScreen({ name: 'pick' })
                        }}
                    />
                </Match>
                <Match when={screen().name === 'pick'}>
                    <div hidden={!!playing()}>
                        <Pick onPlay={setPlaying} onSignOut={signOut} />
                    </div>
                </Match>
            </Switch>
            {/* Keyed on the video, so a new pick builds a fresh player and the old one is torn down. */}
            <Switch>
                <Match when={playing()} keyed>
                    {(item) => <Player item={item} onClose={() => setPlaying(null)} />}
                </Match>
            </Switch>
        </>
    )
}
