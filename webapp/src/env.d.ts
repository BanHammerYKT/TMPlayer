declare const __TG_API_ID__: number
declare const __TG_API_HASH__: string

interface Window {
    ManagedMediaSource?: typeof MediaSource
}

/** The catalog sections the page shows, picked out of en.json by vite.config.ts. */
declare module 'virtual:strings' {
    type Catalog = typeof import('../../core/src/commonMain/resources/i18n/en.json')
    const strings: Pick<Catalog, 'auth' | 'errors' | 'login'>
    export default strings
}
