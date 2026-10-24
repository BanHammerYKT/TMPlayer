// Video.js 10's custom elements, used by the player. Solid sets their props as properties.
import 'solid-js'

declare module 'solid-js' {
    namespace JSX {
        interface IntrinsicElements {
            'video-player': HTMLAttributes<HTMLElement>
            [tag: `media-${string}`]: HTMLAttributes<HTMLElement> & Record<string, unknown>
        }
    }
}
