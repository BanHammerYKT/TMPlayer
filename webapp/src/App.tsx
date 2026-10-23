import { createEffect, createSignal, ErrorBoundary, lazy, Match, onMount, Show, Switch, type ParentProps } from 'solid-js'
import { A, Route, Router, useLocation, useNavigate } from '@solidjs/router'
import { api, errorText } from './tg/rpc'
import { entryState, me, setMe, signOut } from './session'
import { loadSettings } from './store'
import { Home } from './screens/Home'
import { Search } from './screens/Search'
import { Chats } from './screens/Chats'
import { Chat } from './screens/Chat'
import { Settings } from './screens/Settings'
import { Nav } from './ui/Nav'

// Sign in (with its QR code) and the watch page (with the player and its remuxer) load when needed;
// the watch page is fetched while idle once signed in.
const SignIn = lazy(() => import('./screens/SignIn').then((m) => ({ default: m.SignIn })))
const loadWatch = () => import('./screens/Watch').then((m) => ({ default: m.Watch }))
const Watch = lazy(loadWatch)

// Safari has no requestIdleCallback.
const idle = (fn: () => void) => ('requestIdleCallback' in window ? requestIdleCallback(fn) : setTimeout(fn, 1500))

/** Every page: waits for the session, sends a signed out visitor to sign in and back again after. */
function Shell(props: ParentProps) {
    const location = useLocation()
    const navigate = useNavigate()
    const [error, setError] = createSignal<string | null>(null)

    onMount(async () => {
        try {
            const [user] = await Promise.all([api.me(), loadSettings()])
            setMe(user)
            // The saved session is used at once; Telegram's answer follows, and says when it was ended elsewhere.
            if (user) api.whoami().then((u) => void (u ? setMe(u) : signOut()), () => {})
        } catch (e) {
            setError(errorText(e))
        }
    })

    createEffect(() => {
        const user = me()
        if (user === undefined) return
        const onSignIn = location.pathname === '/signin'
        if (!user && !onSignIn) {
            const next = location.pathname + location.search
            navigate(next === '/' ? '/signin' : `/signin?next=${encodeURIComponent(next)}`, { replace: true, state: entryState() })
        } else if (user && onSignIn) {
            const next = new URLSearchParams(location.search).get('next')
            navigate(next?.startsWith('/') ? next : '/', { replace: true, state: entryState() })
        }
        if (user) idle(loadWatch)
    })

    const signedIn = () => me() && location.pathname !== '/signin'
    const playing = () => location.pathname.startsWith('/watch/')

    return (
        <Switch fallback={<Boot />}>
            <Match when={error()}>
                <div class="boot">
                    <p class="error">{error()}</p>
                    <button class="primary" onClick={() => window.location.reload()}>Try again</button>
                </div>
            </Match>
            <Match when={me() === null && location.pathname === '/signin'}>{props.children}</Match>
            <Match when={signedIn()}>
                <main class="page" classList={{ playing: playing() }}>
                    <ErrorBoundary
                        fallback={(e, reset) => (
                            <div class="boot">
                                <p class="error">{errorText(e)}</p>
                                <button class="primary" onClick={reset}>Try again</button>
                            </div>
                        )}
                    >
                        {props.children}
                    </ErrorBoundary>
                </main>
                <Show when={!playing()}>
                    <Nav />
                </Show>
            </Match>
        </Switch>
    )
}

export function App() {
    return (
        <Router root={Shell}>
            <Route path="/" component={Home} />
            <Route path="/signin" component={SignIn} />
            <Route path="/chats" component={Chats} />
            <Route path="/chat/:chatId" component={Chat} />
            <Route path="/search" component={Search} />
            <Route path="/watch/:chatId/:messageId" component={Watch} />
            <Route path="/settings" component={Settings} />
            <Route path="*" component={NotFound} />
        </Router>
    )
}

function NotFound() {
    return (
        <div class="boot">
            <p>There is nothing at this address.</p>
            <A class="link" href="/">Home</A>
        </div>
    )
}

/** The first frame while the session is checked: the logo mark, as index.html paints it. */
function Boot() {
    return (
        <div class="boot">
            <img class="boot-mark" src="/icon.svg" alt="TMPlayer" width="64" height="64" />
        </div>
    )
}
