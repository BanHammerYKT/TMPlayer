import { For, Show } from 'solid-js'
import { settings, setSetting, type Theme } from '../store'
import { me, signOut } from '../session'

const THEMES: { value: Theme; label: string }[] = [
    { value: 'system', label: 'System' },
    { value: 'dark', label: 'Dark' },
    { value: 'light', label: 'Light' },
    { value: 'black', label: 'Black' },
]

/** For now the theme and sign out; the size limit and player defaults come in session 5. */
export function Settings() {
    return (
        <section class="settings">
            <h2>Settings</h2>
            <fieldset>
                <legend>Theme</legend>
                <For each={THEMES}>
                    {(t) => (
                        <label>
                            <input type="radio" name="theme" checked={settings().theme === t.value} onChange={() => setSetting('theme', t.value)} />
                            {t.label}
                        </label>
                    )}
                </For>
            </fieldset>
            <p class="muted small">
                <Show when={me()?.name}>Signed in as {me()!.name}. </Show>TMPlayer is unofficial, not affiliated with Telegram.
            </p>
            <button class="primary" onClick={signOut}>Sign out</button>
        </section>
    )
}
