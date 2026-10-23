// The app's navigation, as the phone and the desktop draw it: one rounded pill floating at the foot
// of the page, holding Home, Chats, Search and the viewer's picture (Settings, for now). A pointer
// gets a short name under each icon; a phone held sideways, where height is short, gets the
// compact bar of round icon buttons (CSS). One highlight slides to the chosen place.
import { For, Show } from 'solid-js'
import { A, useLocation } from '@solidjs/router'
import { Home03Icon, Message01Icon, Search01Icon } from '@hugeicons/core-free-icons'
import { Icon, type IconData } from './Icon'
import { me } from '../session'

interface Place {
    href: string
    label: string
    icon: IconData
    on: (path: string) => boolean
}

const PLACES: Place[] = [
    { href: '/', label: 'Home', icon: Home03Icon, on: (p) => p === '/' },
    { href: '/chats', label: 'Chats', icon: Message01Icon, on: (p) => p === '/chats' || p.startsWith('/chat/') },
    { href: '/search', label: 'Search', icon: Search01Icon, on: (p) => p === '/search' },
]

export function Nav() {
    const location = useLocation()
    const chosen = () => PLACES.findIndex((p) => p.on(location.pathname))
    const inProfile = () => location.pathname === '/settings'
    const initial = () => (me()?.name ?? '').trim().charAt(0).toUpperCase()
    return (
        <nav class="nav" aria-label="Main">
            <div class="nav-cells" style={{ '--i': Math.max(0, chosen()) }}>
                <span class="nav-highlight" classList={{ shown: chosen() >= 0 }} aria-hidden="true" />
                <For each={PLACES}>
                    {(p, i) => (
                        <A class="nav-cell" href={p.href} aria-current={chosen() === i() ? 'page' : undefined} title={p.label}>
                            <span class="nav-glyph">
                                <Icon icon={p.icon} size={22} selected={chosen() === i()} />
                            </span>
                            <span class="nav-label">{p.label}</span>
                        </A>
                    )}
                </For>
                <A class="nav-cell" href="/settings" aria-current={inProfile() ? 'page' : undefined} title="You: Settings">
                    <span class="nav-glyph">
                        <span class="nav-picture" classList={{ ringed: inProfile() }}>
                            <Show when={initial()} fallback={<span class="nav-picture-blank" />}>
                                {initial()}
                            </Show>
                        </span>
                    </span>
                    <span class="nav-label">You</span>
                </A>
            </div>
        </nav>
    )
}
