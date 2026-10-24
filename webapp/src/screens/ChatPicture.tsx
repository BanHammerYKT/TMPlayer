import { createSignal, onCleanup, onMount, Show } from 'solid-js'
import { BookmarkIcon } from '@hugeicons/core-free-icons'
import { Icon } from '../ui/Icon'
import { Initial } from '../ui/PageBar'
import { loadThumb } from './thumbs'

/** A chat's picture once it is on screen, its first letter until then (and where it has none); Saved Messages' bookmark. */
export function ChatPicture(props: { photo: string | null; name: string; self?: boolean; class?: string }) {
    let el!: HTMLSpanElement
    const [src, setSrc] = createSignal<string | null>(null)
    onMount(() => {
        if (!props.photo) return
        const io = new IntersectionObserver((entries) => {
            if (!entries.some((e) => e.isIntersecting)) return
            io.disconnect()
            loadThumb(props.photo!).then(setSrc, () => {})
        })
        io.observe(el)
        onCleanup(() => io.disconnect())
    })
    return (
        <span ref={el} class={`chat-picture ${props.class ?? 'avatar'}`} aria-hidden="true">
            <Show when={!props.self} fallback={<span class="initial saved"><Icon icon={BookmarkIcon} size={22} /></span>}>
                <Show when={src()} fallback={<Initial name={props.name} />}>
                    <img src={src()!} alt="" decoding="async" onError={() => setSrc(null)} />
                </Show>
            </Show>
        </span>
    )
}
