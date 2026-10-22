import { createSignal, For, onCleanup, onMount, Show } from 'solid-js'
import type { InputAudioTrack } from 'mediabunny'
import { openFile, streams } from '../stream/reader'
import { nativeEngine, probe, RemuxEngine, Unplayable, type Engine, type Probe } from './engines'
import { errorText } from '../tg/client'
import type { VideoItem } from '../screens/Pick'

const RESUME_KEY = 'resume:'

function loadResume(key: string): number {
    try {
        return Number(localStorage.getItem(RESUME_KEY + key)) || 0
    } catch {
        return 0
    }
}

function saveResume(key: string, t: number, duration: number) {
    try {
        // Near the end counts as watched: next time starts from the beginning.
        if (duration > 0 && t > duration - 30) localStorage.removeItem(RESUME_KEY + key)
        else if (t > 10) localStorage.setItem(RESUME_KEY + key, String(Math.floor(t)))
    } catch {}
}

export function clock(s: number): string {
    if (!isFinite(s) || s < 0) s = 0
    const h = Math.floor(s / 3600)
    const m = Math.floor((s % 3600) / 60)
    const sec = Math.floor(s % 60)
    const mm = h ? String(m).padStart(2, '0') : String(m)
    return `${h ? h + ':' : ''}${mm}:${String(sec).padStart(2, '0')}`
}

export function Player(props: { item: VideoItem; onClose: () => void }) {
    let video!: HTMLVideoElement
    let root!: HTMLDivElement
    const item = props.item
    const file = openFile(item)
    streams.set(item.key, file)
    file.warmUp()

    let engine: Engine | null = null
    let info: Probe | null = null
    const [playing, setPlaying] = createSignal(false)
    const [waiting, setWaiting] = createSignal(true)
    const [time, setTime] = createSignal(0)
    const [duration, setDuration] = createSignal(item.duration)
    const [buffered, setBuffered] = createSignal(0)
    const [error, setError] = createSignal<string | null>(null)
    const [shown, setShown] = createSignal(true)
    const [muted, setMuted] = createSignal(false)
    const [audios, setAudios] = createSignal<InputAudioTrack[]>([])
    const [audio, setAudio] = createSignal<InputAudioTrack | null>(null)
    const [menu, setMenu] = createSignal(false)
    const [details, setDetails] = createSignal('')
    const [scrub, setScrub] = createSignal<number | null>(null)
    const [switchable, setSwitchable] = createSignal(false)

    const resumeAt = (() => {
        const t = loadResume(item.key)
        // A few seconds back, so the scene is recognised.
        return t > 10 ? Math.max(0, t - 5) : 0
    })()

    let hideTimer = 0
    const poke = () => {
        setShown(true)
        clearTimeout(hideTimer)
        hideTimer = window.setTimeout(() => {
            if (!video.paused && !menu()) setShown(false)
        }, 3000)
    }

    const start = async () => {
        try {
            info = await probe(file, item.duration)
            if (info.duration > 0) setDuration(info.duration)
            setAudios(info.audios)
            setAudio(info.audios[0] ?? null)
            if (info.native) {
                const url = `/stream/${encodeURIComponent(item.key)}?size=${item.size}&mime=${encodeURIComponent(info.mime.split(';')[0])}`
                engine = nativeEngine(video, url, resumeAt)
            } else {
                engine = new RemuxEngine(video, info, resumeAt, { onError: fail })
                if (resumeAt) video.currentTime = resumeAt
            }
            setSwitchable(!!engine.setAudio)
            setDetails(`${info.container}, ${info.mime.replace(/^[^;]*;\s*/, '')}. ${engine.detail}`)
            video.play().catch(() => setPlaying(false))
        } catch (e) {
            fail(e)
        }
    }

    const fail = (e: unknown) => {
        console.error('[player]', e)
        setWaiting(false)
        if (e instanceof Unplayable) setError(e.message)
        else setError(`Could not play this video: ${errorText(e)}`)
    }

    // A native element that refuses the file hands over to the remux engine at the same place.
    const onVideoError = () => {
        if (engine?.name !== 'native' || !info) return
        const at = video.currentTime
        engine.destroy()
        try {
            engine = new RemuxEngine(video, info, at, { onError: fail })
            if (at) video.currentTime = at
            setSwitchable(true)
            setDetails(`${info.container}. ${engine.detail} (the browser refused it as it is)`)
            video.play().catch(() => {})
        } catch (e) {
            fail(e)
        }
    }

    let pauseTimer = 0
    const sync = () => {
        setPlaying(!video.paused)
        setTime(video.currentTime)
        if (isFinite(video.duration) && video.duration > 0) setDuration(video.duration)
        const b = video.buffered
        let end = 0
        for (let i = 0; i < b.length; i++) if (video.currentTime >= b.start(i) - 0.5 && video.currentTime <= b.end(i)) end = b.end(i)
        setBuffered(end)
    }

    let saveTimer = 0
    onMount(() => {
        start()
        poke()
        saveTimer = window.setInterval(() => saveResume(item.key, video.currentTime, duration()), 10_000)
        if ('mediaSession' in navigator) {
            navigator.mediaSession.metadata = new MediaMetadata({ title: item.title, artist: item.chat })
            navigator.mediaSession.setActionHandler('seekbackward', () => seekBy(-10))
            navigator.mediaSession.setActionHandler('seekforward', () => seekBy(10))
            navigator.mediaSession.setActionHandler('seekto', (d) => d.seekTime != null && seek(d.seekTime))
        }
        document.addEventListener('keydown', onKey)
        root.focus()
    })

    onCleanup(() => {
        saveResume(item.key, video.currentTime, duration())
        clearInterval(saveTimer)
        clearTimeout(hideTimer)
        clearTimeout(pauseTimer)
        document.removeEventListener('keydown', onKey)
        engine?.destroy()
        info?.input.dispose()
        streams.delete(item.key)
        file.close()
        if (document.fullscreenElement) document.exitFullscreen().catch(() => {})
        if ('mediaSession' in navigator) navigator.mediaSession.metadata = null
    })

    const toggle = () => {
        if (video.paused) video.play().catch(() => {})
        else video.pause()
        poke()
    }
    const seek = (t: number) => {
        video.currentTime = Math.max(0, Math.min(t, (duration() || Infinity) - 0.5))
        setTime(video.currentTime)
        poke()
    }
    const seekBy = (d: number) => seek(video.currentTime + d)
    const fullscreen = () => {
        if (document.fullscreenElement) document.exitFullscreen()
        else if (root.requestFullscreen) root.requestFullscreen().catch(() => {})
        else (video as HTMLVideoElement & { webkitEnterFullscreen?: () => void }).webkitEnterFullscreen?.()
    }
    const close = () => {
        if (document.fullscreenElement) document.exitFullscreen().catch(() => {})
        props.onClose()
    }

    function onKey(e: KeyboardEvent) {
        if (e.target instanceof HTMLInputElement && e.target.type !== 'range') return
        const k = e.key
        if (k === ' ' || k === 'k') {
            // A focused button would also take the Space as its own click and toggle back.
            if (document.activeElement instanceof HTMLButtonElement) document.activeElement.blur()
            toggle()
        }
        else if (k === 'ArrowLeft') seekBy(e.shiftKey ? -30 : -5)
        else if (k === 'ArrowRight') seekBy(e.shiftKey ? 30 : 5)
        else if (k === 'j') seekBy(-10)
        else if (k === 'l') seekBy(10)
        else if (k === 'f') fullscreen()
        else if (k === 'm') {
            video.muted = !video.muted
            setMuted(video.muted)
        } else if (k === 'ArrowUp') video.volume = Math.min(1, video.volume + 0.1)
        else if (k === 'ArrowDown') video.volume = Math.max(0, video.volume - 0.1)
        else if (k === 'Escape' && !document.fullscreenElement) close()
        else if (/^[0-9]$/.test(k)) seek((duration() * Number(k)) / 10)
        else return
        e.preventDefault()
        poke()
    }

    const pickAudio = (track: InputAudioTrack) => {
        setMenu(false)
        if (track === audio()) return
        setAudio(track)
        engine?.setAudio?.(track)
        if (engine) setDetails((d) => d.replace(/Remuxed.*$/, engine!.detail))
    }

    const trackLabel = (t: InputAudioTrack, i: number) => {
        const lang = t.languageCode && t.languageCode !== 'und' ? t.languageCode.toUpperCase() : ''
        const parts = [t.name, lang, t.codec?.toUpperCase(), t.numberOfChannels ? `${t.numberOfChannels} ch` : ''].filter(Boolean)
        return parts.length ? parts.join(', ') : `Track ${i + 1}`
    }

    const pct = (t: number) => (duration() > 0 ? `${Math.min(100, (t / duration()) * 100)}%` : '0%')

    return (
        <div class="player" classList={{ idle: !shown() }} ref={root} tabIndex={-1} onPointerMove={poke} onPointerDown={poke}>
            <video
                ref={video}
                playsinline
                preload="auto"
                onPlay={() => {
                    sync()
                    file.wantAhead = true
                    clearTimeout(pauseTimer)
                }}
                onPause={() => {
                    sync()
                    saveResume(item.key, video.currentTime, duration())
                    setShown(true)
                    // Paused for a minute: stop reading ahead.
                    pauseTimer = window.setTimeout(() => (file.wantAhead = false), 60_000)
                }}
                onTimeUpdate={sync}
                onProgress={sync}
                onDurationChange={sync}
                onWaiting={() => setWaiting(true)}
                onSeeking={() => setWaiting(true)}
                onPlaying={() => {
                    setWaiting(false)
                    if (info && engine) setDetails(`${info.container}, ${info.mime.replace(/^[^;]*;\s*/, '')}. ${engine.detail}`)
                }}
                onCanPlay={() => setWaiting(false)}
                onSeeked={() => setWaiting(video.readyState < 3)}
                onError={onVideoError}
                onEnded={() => saveResume(item.key, duration(), duration())}
                onClick={toggle}
                onDblClick={fullscreen}
            />
            <Show when={waiting() && !error()}>
                <div class="spinner" aria-label="Loading" />
            </Show>
            <Show when={error()}>
                <div class="player-error" role="alert">
                    <p>{error()}</p>
                    <button class="primary" onClick={close}>Back</button>
                </div>
            </Show>
            <div class="top-bar">
                <button class="icon" onClick={close} aria-label="Back">
                    <svg viewBox="0 0 24 24"><path d="M15.4 5.4 14 4l-8 8 8 8 1.4-1.4L8.8 12z" /></svg>
                </button>
                <div class="title">
                    <div>{item.title}</div>
                    <div class="muted small">{item.chat}</div>
                </div>
            </div>
            <div class="controls" onClick={(e) => e.stopPropagation()}>
                <div class="seek">
                    <div class="seek-track">
                        <div class="seek-buffered" style={{ width: pct(buffered()) }} />
                        <div class="seek-played" style={{ width: pct(scrub() ?? time()) }} />
                    </div>
                    <input
                        type="range"
                        min="0"
                        max={duration() || 0}
                        step="0.1"
                        value={scrub() ?? time()}
                        aria-label="Position"
                        onInput={(e) => setScrub(Number(e.currentTarget.value))}
                        onChange={(e) => {
                            seek(Number(e.currentTarget.value))
                            setScrub(null)
                        }}
                    />
                    <Show when={scrub() != null}>
                        <div class="scrub-time" style={{ left: pct(scrub()!) }}>{clock(scrub()!)}</div>
                    </Show>
                </div>
                <div class="row">
                    <button class="icon" onClick={() => seekBy(-10)} aria-label="Back 10 seconds">
                        <svg viewBox="0 0 24 24"><path d="M12 5V1L7 6l5 5V7a6 6 0 1 1-6 6H4a8 8 0 1 0 8-8z" /><text x="12" y="16.5" font-size="6.5" text-anchor="middle">10</text></svg>
                    </button>
                    <button class="icon play" onClick={toggle} aria-label={playing() ? 'Pause' : 'Play'}>
                        <Show when={playing()} fallback={<svg viewBox="0 0 24 24"><path d="M8 5v14l11-7z" /></svg>}>
                            <svg viewBox="0 0 24 24"><path d="M6 5h4v14H6zm8 0h4v14h-4z" /></svg>
                        </Show>
                    </button>
                    <button class="icon" onClick={() => seekBy(10)} aria-label="Forward 10 seconds">
                        <svg viewBox="0 0 24 24"><path d="M12 5V1l5 5-5 5V7a6 6 0 1 0 6 6h2a8 8 0 1 1-8-8z" /><text x="12" y="16.5" font-size="6.5" text-anchor="middle">10</text></svg>
                    </button>
                    <div class="time">
                        {clock(time())} <span class="muted">/ {clock(duration())}</span>
                    </div>
                    <div class="spacer" />
                    <Show when={audios().length > 1 && switchable()}>
                        <div class="menu-anchor">
                            <button class="chip" onClick={() => setMenu(!menu())} aria-haspopup="menu" aria-expanded={menu()}>
                                Sound
                            </button>
                            <Show when={menu()}>
                                <div class="menu" role="menu">
                                    <For each={audios()}>
                                        {(t, i) => (
                                            <button role="menuitemradio" aria-checked={t === audio()} classList={{ on: t === audio() }} onClick={() => pickAudio(t)}>
                                                {trackLabel(t, i())}
                                            </button>
                                        )}
                                    </For>
                                </div>
                            </Show>
                        </div>
                    </Show>
                    <button
                        class="icon"
                        onClick={() => {
                            video.muted = !video.muted
                            setMuted(video.muted)
                        }}
                        aria-label={muted() ? 'Unmute' : 'Mute'}
                    >
                        <Show when={!muted()} fallback={<svg viewBox="0 0 24 24"><path d="M3 9v6h4l5 5V4L7 9zm13.6 3 2.7-2.7-1.4-1.4-2.7 2.7-2.7-2.7-1.4 1.4 2.7 2.7-2.7 2.7 1.4 1.4 2.7-2.7 2.7 2.7 1.4-1.4z" /></svg>}>
                            <svg viewBox="0 0 24 24"><path d="M3 9v6h4l5 5V4L7 9zm13.5 3A4.5 4.5 0 0 0 14 8v8a4.5 4.5 0 0 0 2.5-4zM14 3.2v2.1a7 7 0 0 1 0 13.4v2.1a9 9 0 0 0 0-17.6z" /></svg>
                        </Show>
                    </button>
                    <button class="icon" onClick={fullscreen} aria-label="Full screen">
                        <svg viewBox="0 0 24 24"><path d="M7 14H5v5h5v-2H7zm-2-4h2V7h3V5H5zm12 7h-3v2h5v-5h-2zM14 5v2h3v3h2V5z" /></svg>
                    </button>
                </div>
                <div class="details muted small">{details()}</div>
            </div>
        </div>
    )
}
