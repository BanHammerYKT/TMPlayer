// A long list or grid that only draws the rows near the screen (@tanstack/solid-virtual over the
// window's own scroll), so a chat of thousands of videos or an account of thousands of chats
// scrolls as lightly as a short one. Columns follow the width the way the CSS grids did.
import { createEffect, createSignal, For, onCleanup, onMount, type JSX } from 'solid-js'
import { createWindowVirtualizer } from '@tanstack/solid-virtual'

export interface GridLayout {
    cols: number
    /** A row's height before it is measured, gap below included. */
    estimate: number
}

export function VirtualGrid<T>(props: {
    items: readonly T[]
    /** Columns and a row's likely height for the list's width. */
    layout: (width: number) => GridLayout
    /** The class of each row (a `ul`), which lays its cells out. */
    rowClass: string
    label?: string
    children: (item: T, index: number) => JSX.Element
    /** Called while the last rows are near the screen, to load more. */
    onEnd?: () => void
}) {
    let el!: HTMLDivElement
    const [width, setWidth] = createSignal(0)
    const [top, setTop] = createSignal(0)
    const layout = () => props.layout(width() || el?.clientWidth || window.innerWidth)
    const rows = () => Math.ceil(props.items.length / layout().cols)
    const measureTop = () => setTop(el.getBoundingClientRect().top + window.scrollY)
    onMount(() => {
        setWidth(el.clientWidth)
        measureTop()
        // What sits above the list (a notice, a search field) moves it down: its parent's size says so.
        const ro = new ResizeObserver(() => {
            setWidth(el.clientWidth)
            measureTop()
        })
        ro.observe(el)
        if (el.parentElement) ro.observe(el.parentElement)
        onCleanup(() => ro.disconnect())
    })
    const v = createWindowVirtualizer<HTMLUListElement>({
        get count() {
            return rows()
        },
        estimateSize: () => layout().estimate,
        overscan: 3,
        get scrollMargin() {
            return top()
        },
    })
    // A new width changes every row's height.
    createEffect(() => {
        layout()
        v.measure()
    })
    createEffect(() => {
        const items = v.getVirtualItems()
        const last = items[items.length - 1]
        if (props.onEnd && last && last.index >= rows() - 3) props.onEnd()
    })
    return (
        <div ref={el} class="vgrid" role="list" aria-label={props.label} style={{ height: `${v.getTotalSize()}px` }}>
            <For each={v.getVirtualItems()}>
                {(row) => {
                    const cells = () => {
                        const cols = layout().cols
                        return props.items.slice(row.index * cols, row.index * cols + cols)
                    }
                    return (
                        <ul
                            class={`vrow ${props.rowClass}`}
                            role="presentation"
                            data-index={row.index}
                            ref={(r) => queueMicrotask(() => r.isConnected && v.measureElement(r))}
                            style={{ transform: `translateY(${row.start - top()}px)`, '--cols': layout().cols }}
                        >
                            <For each={cells()}>{(item, i) => <li role="listitem">{props.children(item, row.index * layout().cols + i())}</li>}</For>
                        </ul>
                    )
                }}
            </For>
        </div>
    )
}

/** The portrait card grid's columns, as `.cards` laid them out: 3 on a phone, then 140 and 156 px wide. */
export function cardLayout(width: number): GridLayout {
    const vw = window.innerWidth
    const [min, gap, rowGap] = vw < 600 ? [0, 10, 16] : vw < 840 ? [140, 16, 20] : [156, 24, 24]
    const cols = min ? Math.max(1, Math.floor((width + gap) / (min + gap))) : 3
    const card = (width - gap * (cols - 1)) / cols
    // The 2:3 art, the name's two lines and the facts.
    return { cols, estimate: Math.round(card * 1.5 + 8 + 44 + 20 + rowGap) }
}
