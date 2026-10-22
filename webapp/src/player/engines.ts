import {
    ALL_FORMATS,
    canEncodeAudio,
    Conversion,
    CustomSource,
    EncodedPacketSink,
    Input,
    Mp4InputFormat,
    Mp4OutputFormat,
    Output,
    QUALITY_HIGH,
    QuickTimeInputFormat,
    StreamTarget,
    WebMInputFormat,
    type InputAudioTrack,
    type InputVideoTrack,
    type StreamTargetChunk,
} from 'mediabunny'
import type { TgFile } from '../stream/reader'

export interface Probe {
    input: Input
    container: string
    video: InputVideoTrack | null
    audios: InputAudioTrack[]
    duration: number
    mime: string
    /** The browser plays the file as it is, through the Service Worker. */
    native: boolean
    /** The picture can be remuxed to MP4 and decoded by this browser. */
    remuxable: boolean
    /** The picture codec in plain words, for messages. */
    videoCodec: string
}

export class Unplayable extends Error {}

const debug = location.hostname === 'localhost'
const mark = (label: string, since: number) => debug && console.debug('[remux]', label, Math.round(performance.now() - since), 'ms')

const MS: typeof MediaSource | undefined = window.ManagedMediaSource ?? window.MediaSource
const phone = matchMedia('(pointer: coarse)').matches

/** Reads the file's header (not its name) to learn the container and codecs. */
export async function probe(file: TgFile, durationHint: number): Promise<Probe> {
    const input = new Input({
        formats: ALL_FORMATS,
        source: new CustomSource({
            getSize: () => file.size,
            read: (start, end) => file.read(start, end),
            maxCacheSize: 4 * 1024 * 1024,
        }),
    })
    const format = await input.getFormat()
    const video = await input.getPrimaryVideoTrack()
    const audios = await input.getAudioTracks()
    const duration = (await input.getDurationFromMetadata().catch(() => null)) ?? durationHint
    const mime = await input.getMimeType().catch(() => 'video/mp4')
    const isoOrWebm = format instanceof Mp4InputFormat || format instanceof QuickTimeInputFormat || format instanceof WebMInputFormat
    // QuickTime files are MP4 to every browser that plays them.
    const asMp4 = mime.replace('video/quicktime', 'video/mp4')
    const probeEl = document.createElement('video')
    const native = isoOrWebm && probeEl.canPlayType(asMp4) !== '' && !!navigator.serviceWorker?.controller
    const vcodec = (await video?.getCodecParameterString().catch(() => null)) ?? null
    const remuxable = !!MS && !!vcodec && MS.isTypeSupported(`video/mp4; codecs="${vcodec.replace(/^hev1/, 'hvc1')}"`)
    const names: Record<string, string> = { avc: 'H.264', hevc: 'HEVC (H.265)', av1: 'AV1', vp9: 'VP9', vp8: 'VP8' }
    const videoCodec = names[video?.codec ?? ''] ?? video?.codec ?? 'unknown'
    return { input, container: format.name, video, audios, duration, mime: asMp4, native, remuxable, videoCodec }
}

export interface EngineEvents {
    onStatus?: (text: string) => void
    onError?: (e: unknown) => void
}

export interface Engine {
    readonly name: string
    readonly detail: string
    setAudio?(track: InputAudioTrack): void
    destroy(): void
}

/** The browser decodes the file itself, read through /stream/ by the Service Worker. */
export function nativeEngine(video: HTMLVideoElement, url: string, start: number): Engine {
    video.src = url
    if (start > 0) video.currentTime = start
    return {
        name: 'native',
        detail: 'Played by the browser',
        destroy() {
            video.removeAttribute('src')
            video.load()
        },
    }
}

/**
 * Remux to fragmented MP4 in the browser: the picture is copied untouched and decoded by the
 * device's hardware, sound the browser cannot play (AC-3, E-AC-3, DTS) is converted to AAC. Fed to
 * ManagedMediaSource on iPhone and Safari, MediaSource elsewhere.
 */
export class RemuxEngine implements Engine {
    readonly name = 'remux'
    detail = 'Remuxed to MP4'
    private ms: MediaSource
    private sb: SourceBuffer | null = null
    private url: string
    private gen = 0
    private conversion: Conversion | null = null
    private running: Promise<void> = Promise.resolve()
    private audio: InputAudioTrack | null
    private keySink: EncodedPacketSink | null
    private destroyed = false
    private t0 = 0
    private wake: (() => void) | null = null
    private readonly onSeeking = () => this.seekTo(this.video.currentTime)
    private readonly onTick = () => this.wake?.()

    constructor(
        private video: HTMLVideoElement,
        private p: Probe,
        start: number,
        private ev: EngineEvents,
    ) {
        if (!MS) throw new Unplayable('This browser has no Media Source Extensions.')
        if (!p.video) throw new Unplayable('This file has no picture.')
        this.audio = p.audios[0] ?? null
        this.keySink = new EncodedPacketSink(p.video)
        this.ms = new MS()
        if (window.ManagedMediaSource && this.ms instanceof window.ManagedMediaSource) video.disableRemotePlayback = true
        this.url = URL.createObjectURL(this.ms)
        video.src = this.url
        video.addEventListener('seeking', this.onSeeking)
        video.addEventListener('timeupdate', this.onTick)
        video.addEventListener('play', this.onTick)
        this.ms.addEventListener(
            'sourceopen',
            () => {
                if (this.p.duration > 0) this.ms.duration = this.p.duration
                this.restart(start)
            },
            { once: true },
        )
    }

    setAudio(track: InputAudioTrack) {
        this.audio = track
        this.restart(this.video.currentTime)
    }

    destroy() {
        this.destroyed = true
        this.gen++
        this.wake?.()
        this.conversion?.cancel().catch(() => {})
        this.video.removeEventListener('seeking', this.onSeeking)
        this.video.removeEventListener('timeupdate', this.onTick)
        this.video.removeEventListener('play', this.onTick)
        this.video.removeAttribute('src')
        this.video.load()
        URL.revokeObjectURL(this.url)
    }

    private seekTo(t: number) {
        if (this.isBuffered(t)) return
        this.restart(t)
    }

    private isBuffered(t: number): boolean {
        const b = this.sb?.buffered
        if (!b) return false
        for (let i = 0; i < b.length; i++) if (t >= b.start(i) - 0.1 && t < b.end(i) - 0.3) return true
        return false
    }

    private restart(t: number) {
        const gen = ++this.gen
        this.wake?.()
        const previous = this.conversion
        this.conversion = null
        const before = this.running
        const t0 = performance.now()
        this.running = (async () => {
            await previous?.cancel().catch(() => {})
            mark(`seek ${t.toFixed(1)}: cancelled`, t0)
            await before.catch(() => {})
            mark('previous done', t0)
            if (gen !== this.gen || this.destroyed) return
            await this.clearBuffer()
            mark('buffer cleared', t0)
            if (gen !== this.gen) return
            const key = await this.keySink!.getKeyPacket(t, { metadataOnly: true }).catch(() => null)
            const from = key?.timestamp ?? 0
            mark(`key frame ${from.toFixed(2)}`, t0)
            if (gen !== this.gen) return
            this.t0 = t0
            await this.run(gen, from)
        })().catch((e) => {
            if (gen === this.gen && !this.destroyed) this.ev.onError?.(e)
        })
    }

    private async clearBuffer() {
        const sb = this.sb
        if (!sb) return
        await this.idle(sb)
        if (this.ms.readyState === 'open') sb.abort()
        if (sb.buffered.length) {
            sb.remove(0, Infinity)
            await this.idle(sb)
        }
    }

    private async run(gen: number, from: number) {
        const audioOptions = await this.audioPlan()
        let written = 0
        const output = new Output({
            format: new Mp4OutputFormat({ fastStart: 'fragmented', minimumFragmentDuration: 1 }),
            target: new StreamTarget(
                new WritableStream<StreamTargetChunk>({
                    write: async (chunk) => {
                        if (gen !== this.gen) throw new DOMException('superseded', 'AbortError')
                        // Fragmented output is written in order; anything that rewrites earlier bytes is skipped.
                        if (chunk.position < written) return
                        written = chunk.position + chunk.data.length
                        if (chunk.position === 0) relabelHevc(chunk.data)
                        const sb = await this.sourceBuffer(output, from)
                        await this.roomAhead(gen)
                        if (gen !== this.gen) throw new DOMException('superseded', 'AbortError')
                        await this.append(sb, chunk.data)
                        if (this.t0) {
                            mark(`first append ${chunk.data.length} B`, this.t0)
                            this.t0 = 0
                        }
                    },
                }),
            ),
        })
        const conversion = await Conversion.init({
            input: this.p.input,
            output,
            video: (track) => (track === this.p.video ? {} : { discard: true }),
            audio: (track) => (track === this.audio ? audioOptions : { discard: true }),
            trim: { start: from },
            copy: { shiftTolerance: 0.25 },
            tags: {},
            showWarnings: false,
        })
        mark('conversion ready', this.t0)
        if (gen !== this.gen) return
        if (!conversion.isValid || !conversion.utilizedTracks.some((t) => t === this.p.video)) {
            throw new Unplayable('The picture in this file cannot be played in this browser.')
        }
        this.conversion = conversion
        this.ev.onStatus?.('')
        await conversion.execute()
        if (gen === this.gen && this.ms.readyState === 'open' && this.sb) {
            await this.idle(this.sb)
            if (gen === this.gen && this.ms.readyState === 'open') this.ms.endOfStream()
        }
    }

    /** Copy the sound when the browser plays it in MP4, else convert it to AAC (or Opus). */
    private async audioPlan() {
        const track = this.audio
        if (!track) return {}
        const codec = await track.getCodecParameterString()
        if (codec && MS!.isTypeSupported(`audio/mp4; codecs="${codec}"`)) {
            this.detail = `Remuxed to MP4, sound copied (${track.codec})`
            return {}
        }
        if (track.codec === 'ac3' || track.codec === 'eac3') {
            const { registerAc3Decoder } = await import('@mediabunny/ac3')
            registerAc3Decoder()
        } else if (track.codec === 'dts') {
            const { registerDtsDecoder } = await import('@mediabunny/dts')
            registerDtsDecoder()
        }
        const target = (await canEncodeAudio('aac')) ? 'aac' : 'opus'
        this.detail = `Remuxed to MP4, sound converted ${track.codec} to ${target}`
        return {
            codec: target,
            numberOfChannels: Math.min(phone ? 2 : 6, track.numberOfChannels || 2),
            sampleRate: 48000,
            quality: QUALITY_HIGH,
            forceTranscode: true,
        } as const
    }

    private async sourceBuffer(output: Output, from: number): Promise<SourceBuffer> {
        if (!this.sb) {
            const mime = (await output.getMimeType()).replace(/hev1\./g, 'hvc1.')
            if (!MS!.isTypeSupported(mime)) throw new Unplayable(`This browser cannot play ${mime}.`)
            this.sb = this.ms.addSourceBuffer(mime)
            this.sb.mode = 'segments'
        }
        await this.idle(this.sb)
        // Output timestamps start at zero from the key frame; place them where they belong.
        if (this.sb.timestampOffset !== from) this.sb.timestampOffset = from
        return this.sb
    }

    /** Keeps 30 s (phone) to 60 s ahead of the playhead, and lets the buffer behind go. */
    private async roomAhead(gen: number) {
        const limit = phone ? 30 : 60
        for (;;) {
            if (gen !== this.gen) return
            const sb = this.sb!
            const t = this.video.currentTime
            let end = t
            for (let i = 0; i < sb.buffered.length; i++) if (t >= sb.buffered.start(i) - 1 && t <= sb.buffered.end(i)) end = sb.buffered.end(i)
            if (end - t < limit) return
            await new Promise<void>((resolve) => {
                this.wake = resolve
                setTimeout(resolve, 1000)
            })
            this.wake = null
        }
    }

    private async append(sb: SourceBuffer, data: Uint8Array) {
        for (let attempt = 0; ; attempt++) {
            try {
                await this.idle(sb)
                sb.appendBuffer(data as BufferSource)
                await this.idle(sb)
                return
            } catch (e) {
                if ((e as DOMException)?.name !== 'QuotaExceededError' || attempt > 2) throw e
                const behind = this.video.currentTime - 10
                if (behind > 0) {
                    sb.remove(0, behind)
                    await this.idle(sb)
                }
                await new Promise((r) => setTimeout(r, 1000))
            }
        }
    }

    private idle(sb: SourceBuffer): Promise<void> {
        if (!sb.updating) return Promise.resolve()
        return new Promise((resolve, reject) => {
            const done = () => {
                sb.removeEventListener('updateend', done)
                sb.removeEventListener('error', fail)
                resolve()
            }
            const fail = () => {
                sb.removeEventListener('updateend', done)
                sb.removeEventListener('error', fail)
                reject(new Error('The browser could not take this part of the video.'))
            }
            sb.addEventListener('updateend', done)
            sb.addEventListener('error', fail)
        })
    }
}

const HEV1 = [0x68, 0x65, 0x76, 0x31]

/**
 * Names HEVC `hvc1` instead of `hev1` in the init segment: Safari refuses `hev1`. The two differ only
 * in whether parameter sets may also travel in the stream; a Matroska file always carries them in
 * its header (CodecPrivate), which is what `hvc1` promises.
 */
function relabelHevc(data: Uint8Array) {
    for (let i = 0; i + 4 <= data.length; i++) {
        if (data[i] === HEV1[0] && data[i + 1] === HEV1[1] && data[i + 2] === HEV1[2] && data[i + 3] === HEV1[3]) data[i + 2] = 0x63
    }
}

if (debug) (window as unknown as { probe: typeof probe }).probe = probe
