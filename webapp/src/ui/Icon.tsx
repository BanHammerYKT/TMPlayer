// The app's icons: Hugeicons' free Stroke Rounded set, the same glyphs TmIcons draws in Compose.
// A place that is chosen (the nav bar's) fills its glyph in the way the app's SelectableIcon does:
// the outline's shapes filled, the inner detail (a door, a speech bubble's lines) cut out of it.
import { createUniqueId, For, Show } from 'solid-js'
import { Home03Icon, Message01Icon, Search01Icon, Settings01Icon } from '@hugeicons/core-free-icons'

type Part = readonly [string, Readonly<Record<string, string | number>>]
export type IconData = readonly Part[]

/** How an icon fills in when chosen: which parts are the body and which is the detail cut from it. */
interface Fill {
    body: number[]
    cut?: number
    /** The cut is a filled shape rather than a line (Settings' hub). */
    cutFilled?: boolean
    cutWidth?: number
}

// TmIcons.selectedOf, part by part.
const FILLS = new Map<IconData, Fill>([
    [Home03Icon, { body: [0], cut: 1, cutWidth: 2.25 }],
    [Message01Icon, { body: [1], cut: 0 }],
    [Settings01Icon, { body: [0], cut: 1, cutFilled: true }],
    [Search01Icon, { body: [0, 1] }],
])

const d = (icon: IconData, i: number) => String(icon[i][1].d)

export function Icon(props: { icon: IconData; size?: number; selected?: boolean; class?: string }) {
    const mask = createUniqueId()
    const fill = () => FILLS.get(props.icon)
    return (
        <svg
            class={props.class ? `icon ${props.class}` : 'icon'}
            classList={{ chosen: !!props.selected }}
            width={props.size ?? 24}
            height={props.size ?? 24}
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="1.5"
            stroke-linecap="round"
            stroke-linejoin="round"
            aria-hidden="true"
        >
            <g class="icon-outline">
                <For each={props.icon as Part[]}>{([, a]) => <path d={String(a.d)} fill={a.fill === 'currentColor' ? 'currentColor' : 'none'} />}</For>
            </g>
            <Show when={fill()}>
                {(f) => (
                    <g class="icon-fill" mask={f().cut !== undefined ? `url(#${mask})` : undefined}>
                        <Show when={f().cut !== undefined}>
                            <mask id={mask} maskUnits="userSpaceOnUse" x="-2" y="-2" width="28" height="28">
                                <rect x="-2" y="-2" width="28" height="28" fill="#fff" stroke="none" />
                                <path
                                    d={d(props.icon, f().cut!)}
                                    stroke="#000"
                                    stroke-width={f().cutWidth ?? 1.5}
                                    fill={f().cutFilled ? '#000' : 'none'}
                                />
                            </mask>
                        </Show>
                        <For each={f().body}>{(i) => <path d={d(props.icon, i)} fill="currentColor" />}</For>
                    </g>
                )}
            </Show>
        </svg>
    )
}
