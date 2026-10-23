// The page's words, from the app's own catalog (core's i18n/en.json): only the sections
// vite.config.ts picks, so the first load stays light. English only for now.
import { flatten, translator, type TemplateResolver } from '@solid-primitives/i18n'
import strings from 'virtual:strings'

const dict = flatten(strings)

// The catalog writes placeholders as `{name}`, ICU style. Plural forms are not read here yet: a
// string with one would need the ICU formatter, so the page picks strings without.
const fill = ((text: string, args?: Record<string, string | number>) =>
    args ? text.replace(/\{(\w+)\}/g, (whole, key: string) => (key in args ? String(args[key]) : whole)) : text) as TemplateResolver

/** `t('login.qr_title')`, `t('login.delivery_sms', { number })`. */
export const t = translator(() => dict, fill) as (key: keyof typeof dict, args?: Record<string, string | number>) => string

/** A wait in the largest unit that fits it, as the app's Failures.waitPhrase: "42 seconds", "5 minutes". */
export function waitPhrase(seconds: number): string {
    const [n, unit] =
        seconds < 60 ? [seconds, 'second'] : seconds < 3600 ? [Math.floor(seconds / 60), 'minute'] : seconds < 2 * 86_400 ? [Math.floor(seconds / 3600), 'hour'] : [Math.floor(seconds / 86_400), 'day']
    return new Intl.NumberFormat('en', { style: 'unit', unit: unit as string, unitDisplay: 'long' }).format(n as number)
}

/** "1:05", as the app's resend clock. */
export const clock = (seconds: number) => `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
