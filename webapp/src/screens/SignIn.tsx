import { createSignal, Match, onCleanup, onMount, Show, Switch } from 'solid-js'
import { renderSVG } from 'uqr'
import { api, call, errorText } from '../tg/rpc'
import { setMe } from '../session'
import type { Me, QrEvent } from '../tg/types'

type Step = 'qr' | 'phone' | 'code' | 'password'

/** Sign in: the QR code first (scan it with Telegram on the phone), or a phone number and code. */
export function SignIn() {
    // The shell sees the user and leaves this page for the one asked for.
    const done = (user: Me) => setMe(user)
    const [step, setStep] = createSignal<Step>('qr')
    const [qr, setQr] = createSignal<string | null>(null)
    const [scanned, setScanned] = createSignal(false)
    const [phone, setPhone] = createSignal('')
    const [code, setCode] = createSignal('')
    const [password, setPassword] = createSignal('')
    const [hint, setHint] = createSignal<string | null>(null)
    const [busy, setBusy] = createSignal(false)
    const [error, setError] = createSignal<string | null>(null)
    let codeHash = ''
    let qrAbort: AbortController | null = null
    // The QR flow asks for the 2FA password through this: resolved when the form is sent.
    let passwordWaiter: ((pw: string) => void) | null = null

    const startQr = () => {
        qrAbort?.abort()
        const abort = new AbortController()
        qrAbort = abort
        setQr(null)
        setScanned(false)
        setError(null)
        call('signInQr', [], {
            signal: abort.signal,
            onEvent: (data) => {
                const e = data as QrEvent
                if (e.kind === 'url') setQr(renderSVG(e.url, { pixelSize: 6, border: 2, whiteColor: '#fff', blackColor: '#0e0f13' }))
                else if (e.kind === 'scanned') setScanned(true)
                else if (e.kind === 'password') return askPassword(e.hint)
                else if (e.kind === 'badPassword') setError('That password is not right.')
            },
        })
            .then(done)
            .catch((e) => {
                if (abort.signal.aborted) return
                console.error('[qr]', e)
                setError(errorText(e))
            })
    }

    const askPassword = (h: string | null) => {
        setHint(h)
        setStep('password')
        setBusy(false)
        return new Promise<string>((resolve) => (passwordWaiter = resolve))
    }

    onMount(startQr)
    onCleanup(() => qrAbort?.abort())

    const usePhone = () => {
        qrAbort?.abort()
        setError(null)
        setStep('phone')
    }

    const backToQr = () => {
        setStep('qr')
        startQr()
    }

    const sendCode = async (e: Event) => {
        e.preventDefault()
        const number = phone().replace(/[^\d+]/g, '')
        if (number.replace('+', '').length < 6) return setError('Type the whole number with its country code, like +1 555 123 4567.')
        setBusy(true)
        setError(null)
        try {
            const sent = await api.sendCode(number)
            if ('hash' in sent) {
                codeHash = sent.hash
                setStep('code')
            } else {
                done(sent.me)
            }
        } catch (err) {
            setError(errorText(err))
        } finally {
            setBusy(false)
        }
    }

    const sendSignIn = async (e: Event) => {
        e.preventDefault()
        setBusy(true)
        setError(null)
        try {
            done(await api.signIn(phone().replace(/[^\d+]/g, ''), codeHash, code().trim()))
        } catch (err) {
            if (errorText(err).includes('SESSION_PASSWORD_NEEDED')) {
                setHint(await api.passwordHint())
                setStep('password')
            } else {
                setError(errorText(err) === 'PHONE_CODE_INVALID' ? 'That code is not right.' : errorText(err))
            }
        } finally {
            setBusy(false)
        }
    }

    const sendPassword = async (e: Event) => {
        e.preventDefault()
        if (!password()) return
        setBusy(true)
        setError(null)
        if (passwordWaiter) {
            // The QR flow is waiting for it and finishes the sign in itself.
            const resolve = passwordWaiter
            passwordWaiter = null
            resolve(password())
            return
        }
        try {
            done(await api.checkPassword(password()))
        } catch (err) {
            setError(errorText(err) === 'PASSWORD_HASH_INVALID' ? 'That password is not right.' : errorText(err))
        } finally {
            setBusy(false)
        }
    }

    return (
        <main class="signin">
            <h1>Sign in to TMPlayer</h1>
            <p class="muted">A player for the videos in your own Telegram chats. Unofficial, not affiliated with Telegram.</p>
            <Switch>
                <Match when={step() === 'qr'}>
                    <div class="qr" aria-live="polite">
                        <Show when={qr()} fallback={<div class="qr-wait">Connecting…</div>}>
                            <div class="qr-code" innerHTML={qr()!} />
                        </Show>
                    </div>
                    <ol class="steps">
                        <li>Open Telegram on your phone</li>
                        <li>Go to Settings, Devices, Link Desktop Device</li>
                        <li>Point your phone at this code</li>
                    </ol>
                    <Show when={scanned()}>
                        <p class="muted">Scanned, signing in…</p>
                    </Show>
                    <button class="link" onClick={usePhone}>Sign in with a phone number</button>
                </Match>
                <Match when={step() === 'phone'}>
                    <form onSubmit={sendCode}>
                        <label for="phone">Phone number</label>
                        <input id="phone" type="tel" autocomplete="tel" inputmode="tel" placeholder="+1 555 123 4567" value={phone()} onInput={(e) => setPhone(e.currentTarget.value)} autofocus />
                        <button class="primary" disabled={busy()}>{busy() ? 'Sending…' : 'Send the code'}</button>
                    </form>
                    <button class="link" onClick={backToQr}>Use the QR code instead</button>
                </Match>
                <Match when={step() === 'code'}>
                    <form onSubmit={sendSignIn}>
                        <label for="code">The code Telegram sent you</label>
                        <input id="code" inputmode="numeric" autocomplete="one-time-code" value={code()} onInput={(e) => setCode(e.currentTarget.value)} autofocus />
                        <button class="primary" disabled={busy() || !code().trim()}>{busy() ? 'Checking…' : 'Sign in'}</button>
                    </form>
                    <button class="link" onClick={() => setStep('phone')}>Change the number</button>
                </Match>
                <Match when={step() === 'password'}>
                    <form onSubmit={sendPassword}>
                        <label for="pw">Your two-step verification password</label>
                        <input id="pw" type="password" autocomplete="current-password" value={password()} onInput={(e) => setPassword(e.currentTarget.value)} autofocus />
                        <Show when={hint()}>
                            <p class="muted">Hint: {hint()}</p>
                        </Show>
                        <button class="primary" disabled={busy() || !password()}>{busy() ? 'Checking…' : 'Sign in'}</button>
                    </form>
                </Match>
            </Switch>
            <Show when={error()}>
                <p class="error" role="alert">{error()}</p>
            </Show>
        </main>
    )
}
