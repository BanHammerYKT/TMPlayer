// The page's words, from the app's own catalog (core's i18n/en.json): only the sections
// vite.config.ts picks, so the first load stays light. English only for now.
import { flatten, translator, type TemplateResolver } from '@solid-primitives/i18n'
import strings from 'virtual:strings'

const dict = flatten(strings)

type Args = Record<string, string | number>

const plurals = new Intl.PluralRules('en')
const numbers = new Intl.NumberFormat('en')

/** Where the `}` closing a `{` opened just before `from` is. */
function closing(text: string, from: number): number {
    let depth = 1
    for (let i = from; i < text.length; i++) {
        if (text[i] === '{') depth++
        else if (text[i] === '}' && --depth === 0) return i
    }
    return text.length
}

/**
 * The catalog's ICU subset, as the apps' Translator reads it: `{name}`, and `{count, plural, one
 * {# video} other {# videos}}` (with `=0` cases) and `{form, select, tv {...} other {...}}`,
 * nested. A whole MessageFormat library would weigh more than the page's strings.
 */
function format(text: string, args: Args, hash?: string): string {
    let out = ''
    for (let i = 0; i < text.length; ) {
        const c = text[i]
        if (c === '#' && hash !== undefined) {
            out += hash
            i++
            continue
        }
        if (c !== '{') {
            out += c
            i++
            continue
        }
        const end = closing(text, i + 1)
        const inner = text.slice(i + 1, end)
        i = end + 1
        const head = /^\s*(\w+)\s*(?:,\s*(plural|select)\s*,)?/.exec(inner)!
        const [whole, name, kind] = head
        if (!kind) {
            out += name in args ? String(args[name]) : `{${inner}}`
            continue
        }
        // The cases: `key {message}` pairs.
        const cases = new Map<string, string>()
        for (let j = whole.length; j < inner.length; ) {
            const m = /^\s*(=?\w+)\s*\{/.exec(inner.slice(j))
            if (!m) break
            const open = j + m[0].length
            const close = closing(inner, open)
            cases.set(m[1], inner.slice(open, close))
            j = close + 1
        }
        const value = args[name]
        if (kind === 'plural') {
            const n = Number(value)
            const pick = cases.get(`=${n}`) ?? cases.get(plurals.select(n)) ?? cases.get('other') ?? ''
            out += format(pick, args, numbers.format(n))
        } else out += format(cases.get(String(value)) ?? cases.get('other') ?? '', args, hash)
    }
    return out
}

const fill = ((text: string, args?: Args) => (args || text.includes('{') ? format(text, args ?? {}) : text)) as TemplateResolver

/** `t('login.qr_title')`, `t('login.delivery_sms', { number })`. */
export const t = translator(() => dict, fill) as (key: keyof typeof dict, args?: Args) => string

/** A wait in the largest unit that fits it, as the app's Failures.waitPhrase: "42 seconds", "5 minutes". */
export function waitPhrase(seconds: number): string {
    const [n, unit] =
        seconds < 60 ? [seconds, 'second'] : seconds < 3600 ? [Math.floor(seconds / 60), 'minute'] : seconds < 2 * 86_400 ? [Math.floor(seconds / 3600), 'hour'] : [Math.floor(seconds / 86_400), 'day']
    return new Intl.NumberFormat('en', { style: 'unit', unit: unit as string, unitDisplay: 'long' }).format(n as number)
}

/** "1:05", as the app's resend clock. */
export const clock = (seconds: number) => `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
