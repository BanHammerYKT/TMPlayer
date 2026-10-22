import { readFileSync } from 'node:fs'
import { defineConfig } from 'vite'
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

export default defineConfig({
    plugins: [solid()],
    define: {
        __TG_API_ID__: JSON.stringify(Number(secret('TG_API_ID')) || 0),
        __TG_API_HASH__: JSON.stringify(secret('TG_API_HASH')),
    },
    worker: { format: 'es' },
    optimizeDeps: { exclude: ['@mtcute/wasm', '@mtcute/web', '@mediabunny/ac3', '@mediabunny/dts'] },
    build: { target: 'es2022', sourcemap: true },
    server: { port: 8091, host: true },
    preview: { port: 8091, host: true },
})
