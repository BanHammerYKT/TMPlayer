import { TgFile } from '../stream/reader'
import type { FileRef, Storyboard } from '../tg/types'

async function readWhole(f: FileRef): Promise<Uint8Array> {
    const file = new TgFile(f.key, f.size, 0)
    file.wantAhead = false
    try {
        return await file.read(0, f.size)
    } finally {
        file.close()
    }
}

function vttTime(s: number): string {
    const h = Math.floor(s / 3600)
    const m = Math.floor((s % 3600) / 60)
    const sec = s % 60
    return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:${sec.toFixed(3).padStart(6, '0')}`
}

/**
 * Telegram's storyboard as a WebVTT thumbnails track: each cue points at one frame of the JPEG
 * sprite (`sprite.jpg#xywh=x,y,w,h`), the format the player's scrub preview reads. The map is
 * `file=`, `frame_width=`, `frame_height=` and then one `seconds,x,y` line per frame.
 */
export async function storyboardTrack(sb: Storyboard, duration: number): Promise<{ vtt: string; urls: string[] }> {
    const [sprite, map] = await Promise.all([readWhole(sb.sprite), readWhole(sb.map)])
    const spriteUrl = URL.createObjectURL(new Blob([sprite as BlobPart], { type: 'image/jpeg' }))
    let w = 0
    let h = 0
    const frames: { t: number; x: number; y: number }[] = []
    for (const line of new TextDecoder().decode(map).split('\n')) {
        if (line.startsWith('frame_width=')) w = Number(line.slice(12))
        else if (line.startsWith('frame_height=')) h = Number(line.slice(13))
        else {
            const [t, x, y] = line.split(',').map(Number)
            if (line.includes(',') && [t, x, y].every(Number.isFinite)) frames.push({ t, x, y })
        }
    }
    if (!w || !h || !frames.length) throw new Error('Unreadable storyboard')
    let vtt = 'WEBVTT\n\n'
    frames.forEach((f, i) => {
        const end = frames[i + 1]?.t ?? Math.max(duration, f.t + 1)
        vtt += `${vttTime(f.t)} --> ${vttTime(end)}\n${spriteUrl}#xywh=${f.x},${f.y},${w},${h}\n\n`
    })
    const vttUrl = URL.createObjectURL(new Blob([vtt], { type: 'text/vtt' }))
    return { vtt: vttUrl, urls: [spriteUrl, vttUrl] }
}
