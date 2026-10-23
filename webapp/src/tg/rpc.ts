// The page's side of the Telegram worker: calls by name, answers by message. Only types come from
// the worker file, so mtcute never lands in the page's bundle.
import type { Api, Ctx } from './worker'
import type { RpcError } from './types'

const worker = new Worker(new URL('./worker.ts', import.meta.url), { type: 'module', name: 'telegram' })

type Out =
    | { id: number; value: unknown }
    | { id: number; error: RpcError }
    | { id: number; event: unknown; ask?: number }

interface Pending {
    resolve: (v: unknown) => void
    reject: (e: unknown) => void
    onEvent?: (data: unknown) => unknown
}

const pending = new Map<number, Pending>()
let nextId = 0

worker.onmessage = async (ev: MessageEvent<Out>) => {
    const msg = ev.data
    const call = pending.get(msg.id)
    if (!call) return
    if ('event' in msg) {
        const answer = await call.onEvent?.(msg.event)
        if (msg.ask != null) worker.postMessage({ reply: msg.id, ask: msg.ask, value: answer })
        return
    }
    pending.delete(msg.id)
    if ('error' in msg) {
        if (msg.error.code === 401) onUnauthorized?.()
        call.reject(Object.assign(new Error(msg.error.message), msg.error))
    } else call.resolve(msg.value)
}

let onUnauthorized: (() => void) | null = null
/** Called when Telegram says the session is gone (signed out on another device, for one). */
export function whenUnauthorized(fn: () => void) {
    onUnauthorized = fn
}

type Args<F> = F extends (ctx: Ctx, ...a: infer A) => unknown ? A : never
type Result<F> = F extends (...a: never[]) => Promise<infer R> ? R : never

export interface CallOptions {
    signal?: AbortSignal
    /** Events the call sends while it runs; what this returns answers the ones that ask. */
    onEvent?: (data: unknown) => unknown
}

export function call<M extends keyof Api>(method: M, args: Args<Api[M]>, opts: CallOptions = {}): Promise<Result<Api[M]>> {
    const { signal, onEvent } = opts
    if (signal?.aborted) return Promise.reject(new DOMException('aborted', 'AbortError'))
    const id = ++nextId
    return new Promise((resolve, reject) => {
        pending.set(id, { resolve: resolve as (v: unknown) => void, reject, onEvent })
        signal?.addEventListener(
            'abort',
            () => {
                if (!pending.delete(id)) return
                worker.postMessage({ abort: id })
                reject(new DOMException('aborted', 'AbortError'))
            },
            { once: true },
        )
        worker.postMessage({ id, method, args })
    })
}

/** The worker's calls as plain functions: `api.chats(60)`. */
export const api = new Proxy({} as { [M in keyof Api]: (...args: Args<Api[M]>) => Promise<Result<Api[M]>> }, {
    get: (_, method: string) => (...args: unknown[]) => call(method as keyof Api, args as never),
})

// Local testing only: the calls in the console.
if (location.hostname === 'localhost') (window as unknown as { api: typeof api }).api = api

export function errorText(e: unknown): string {
    const err = e as Partial<RpcError>
    if (err?.text?.startsWith('FLOOD_WAIT') && err.seconds) return `Telegram asks to wait ${err.seconds} s.`
    return err?.text ?? err?.message ?? String(e)
}
