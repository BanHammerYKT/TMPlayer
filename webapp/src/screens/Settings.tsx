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
            <h1 class="page-title">Settings</h1>
            <div class="panel">
                <h2 class="panel-title" id="theme-label">Theme</h2>
                <div class="segmented" role="radiogroup" aria-labelledby="theme-label">
                    <For each={THEMES}>
                        {(t) => (
                            <label classList={{ on: settings().theme === t.value }}>
                                <input type="radio" name="theme" checked={settings().theme === t.value} onChange={() => setSetting('theme', t.value)} />
                                {t.label}
                            </label>
                        )}
                    </For>
                </div>
            </div>
            <div class="panel">
                <Show when={me()?.name}>
                    <p class="panel-title">Signed in as {me()!.name}</p>
                </Show>
                <p class="muted small">TMPlayer is unofficial, not affiliated with Telegram.</p>
                <button class="danger-button" onClick={signOut}>Sign out</button>
            </div>
        </section>
    )
}
