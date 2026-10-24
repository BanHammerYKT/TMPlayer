// Whether the browser has a connection, for the offline line and the lists that try again by
// themselves once it is back.
import { createSignal, onCleanup } from 'solid-js'

const [online, setOnline] = createSignal(navigator.onLine)
window.addEventListener('online', () => setOnline(true))
window.addEventListener('offline', () => setOnline(false))
export { online }

/** Runs `fn` each time the connection comes back, for as long as the calling component lives. */
export function onBackOnline(fn: () => void) {
    window.addEventListener('online', fn)
    onCleanup(() => window.removeEventListener('online', fn))
}
