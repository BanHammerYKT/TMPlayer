import { readFileSync } from 'node:fs'
import { defineConfig, type Plugin } from 'vite'
import solid from 'vite-plugin-solid'

// The app's own api_id and hash: from the environment in CI, else from the repo's local.properties
// (the same secrets the Gradle builds read). Never a sample one.
function secret(name: string): string {
    const env = process.env[name]
    if (env) return env
    try {
        const line = readFileSync(new URL('../local.properties', import.meta.url), 'utf8')
            .split('\n')
            .find((l) => l.startsWith(`${name}=`))
        return line ? line.slice(name.length + 1).trim() : ''
    } catch {
        return ''
    }
}

const CATALOG = new URL('../core/src/commonMain/resources/i18n/en.json', import.meta.url)

/**
 * The catalog sections the page itself shows (src/i18n.ts), as `virtual:strings`: a few KB in
 * place of the whole 180 KB file. The worker still fetches the whole catalog for the rules.
 * Add a section here and to `PageStrings` in env.d.ts when a page needs one.
 */
const PAGE_SECTIONS = ['auth', 'errors', 'login']

function pageStrings(): Plugin {
    const id = 'virtual:strings'
    return {
        name: 'tmplayer-page-strings',
        resolveId: (source) => (source === id ? `\0${id}` : null),
        load(source) {
            if (source !== `\0${id}`) return null
            this.addWatchFile(CATALOG.pathname)
            const all = JSON.parse(readFileSync(CATALOG, 'utf8'))
            return `export default ${JSON.stringify(Object.fromEntries(PAGE_SECTIONS.map((k) => [k, all[k]])))}`
        },
    }
}

export default defineConfig({
    plugins: [solid(), pageStrings()],
    define: {
        __TG_API_ID__: JSON.stringify(Number(secret('TG_API_ID')) || 0),
        __TG_API_HASH__: JSON.stringify(secret('TG_API_HASH')),
    },
    worker: { format: 'es' },
    optimizeDeps: { exclude: ['@mtcute/wasm', '@mtcute/web', '@mediabunny/ac3', '@mediabunny/dts'] },
    build: { target: 'es2022', sourcemap: true },
    // The UI catalog is read from :core, where every app reads it.
    server: { port: 8091, host: true, fs: { allow: ['.', '../core/src/commonMain/resources/i18n'] } },
    preview: { port: 8091, host: true },
})
