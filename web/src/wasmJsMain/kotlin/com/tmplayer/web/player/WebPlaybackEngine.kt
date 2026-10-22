package com.tmplayer.web.player

import com.tmplayer.data.MediaItem
import com.tmplayer.desktop.player.MediaTrack
import com.tmplayer.desktop.player.OpenPrefs
import com.tmplayer.desktop.player.PlaybackEngine
import com.tmplayer.desktop.player.PlaybackStatus
import com.tmplayer.desktop.player.TrackType
import com.tmplayer.player.AbLoop
import com.tmplayer.player.Chapter
import com.tmplayer.player.SubtitlePlan
import com.tmplayer.player.SubtitleStyle
import com.tmplayer.player.VideoScale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import org.openani.mediamp.source.MediaData

@JsFun("(spec, onState) => window.TmVideo.open(spec, onState)")
private external fun videoOpen(spec: String, onState: (String) -> Unit)

@JsFun("() => window.TmVideo.close()")
private external fun videoClose()

@JsFun("() => window.TmVideo.play()")
private external fun videoPlay()

@JsFun("() => window.TmVideo.pause()")
private external fun videoPause()

@JsFun("(sec) => window.TmVideo.seek(sec)")
private external fun videoSeek(sec: Double)

@JsFun("(s) => window.TmVideo.setSpeed(s)")
private external fun videoSpeed(s: Double)

@JsFun("(v) => window.TmVideo.setVolume(v)")
private external fun videoVolume(v: Double)

@JsFun("(on) => window.TmVideo.setMuted(on)")
private external fun videoMuted(on: Boolean)

@JsFun("(s) => window.TmVideo.setShape(s)")
private external fun videoShape(s: String)

@JsFun("(on) => window.TmVideo.setStable(on)")
private external fun videoStable(on: Boolean)

@JsFun("(id) => window.TmVideo.selectAudio(id)")
private external fun videoAudio(id: String)

@JsFun("(id) => window.TmVideo.selectSubtitle(id)")
private external fun videoSubtitle(id: String)

@JsFun("(s) => window.TmVideo.setSubtitleDelay(s)")
private external fun videoSubtitleDelay(s: Double)

@JsFun("(forward) => window.TmVideo.frameStep(forward)")
private external fun videoFrameStep(forward: Boolean)

@JsFun("() => window.TmVideo.retry()")
private external fun videoRetry()

@JsFun("(on) => window.TmVideo.fullscreen(on)")
internal external fun videoFullscreen(on: Boolean)

@JsFun("() => window.TmVideo.canPip()")
private external fun videoCanPip(): Boolean

@JsFun("() => { try { const p = window.TmVideo.pip(); if (p && p.catch) p.catch(() => {}); } catch (e) {} }")
private external fun videoPip()

/** A Telegram video the page streams: what the browser's PlaybackEngine opens. */
class WebMedia(val item: MediaItem) : MediaData

/**
 * The desktop's [PlaybackEngine] in the browser: the picture is web/player/video-host.js's, in the
 * browser's own player or movi-player (decided by player.js's rules), under the page's canvas; the
 * controls over it are the desktop's PlayerOverlay. Its state comes back from the page as JSON.
 *
 * What the browser cannot do the way mpv does (frame accurate steps, an audio delay, downmix,
 * picture subtitles in the browser's own player, a screenshot) is left as close as it gets or
 * left out; docs/WEB.md lists the gaps.
 */
class WebPlaybackEngine : PlaybackEngine {
    private val _state = MutableStateFlow(PlaybackStatus())
    override val state: StateFlow<PlaybackStatus> = _state.asStateFlow()
    private val _tracks = MutableStateFlow<List<MediaTrack>>(emptyList())
    override val tracks: StateFlow<List<MediaTrack>> = _tracks.asStateFlow()

    /** "codec", "heavy" or "stopped" once the page gives up on the file; null while it plays. */
    private val _failure = MutableStateFlow<String?>(null)
    val failure: StateFlow<String?> = _failure.asStateFlow()

    /**
     * Bytes the page's reader (web/player/tg-source.js) has handed the player since this video
     * opened, for the opening loader's line and speed. The page's count is for the whole tab, so
     * this one starts from the first figure it sends, which comes before the video's first read.
     */
    var readBytes: Long = 0
        private set
    private var readBytesAt = -1L

    /** "native", "movi" or "movi-sw": which engine draws the picture, for Playback details. */
    var engineName: String = ""
        private set

    private val audioIds = ArrayList<String>()
    private val subtitleIds = ArrayList<String>()
    private var open = false
    private var loop: AbLoop? = null
    private var style = SubtitleStyle()
    private var subtitleDelayMs = 0L

    override suspend fun open(data: MediaData, startAtMs: Long, prefs: OpenPrefs) {
        val item = (data as WebMedia).item
        _failure.value = null
        _state.value = PlaybackStatus(
            volume = prefs.volume,
            muted = prefs.muted,
            speed = prefs.speed,
            scale = prefs.scale,
            volumeBoost = prefs.volumeBoost,
            nightMode = prefs.nightMode,
            subtitleStyle = prefs.subtitleStyle,
            playing = true,
            buffering = true,
        )
        val opened = CompletableDeferred<Unit>()
        val spec = buildJsonObject {
            put("key", "${item.chatId}:${item.messageId}")
            put("name", item.fileName)
            put("mime", item.mimeType)
            put("durationSec", item.durationSec)
            put("startSec", startAtMs / 1000.0)
            put("title", item.title)
            put("source", buildJsonObject {
                putJsonArray("ids") { add(item.fileId) }
                putJsonArray("sizes") { add(item.sizeBytes) }
            })
        }
        open = true
        videoOpen(spec.toString()) { text ->
            val o = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return@videoOpen
            when ((o["type"] as? JsonPrimitive)?.content) {
                "error" -> {
                    _failure.value = (o["kind"] as? JsonPrimitive)?.content ?: "codec"
                    if (!opened.isCompleted) opened.complete(Unit)
                }
                "state" -> {
                    read(o)
                    if (_state.value.opened && !opened.isCompleted) opened.complete(Unit)
                    checkLoop()
                }
            }
        }
        videoVolume(prefs.volume / 100.0)
        videoMuted(prefs.muted)
        videoSpeed(prefs.speed.toDouble())
        videoShape(shapeOf(prefs.scale))
        videoStable(prefs.volumeBoost || prefs.nightMode)
        if (prefs.delays.subtitleMs != 0L) setSubtitleDelay(prefs.delays.subtitleMs)
        // A video the page is still fetching the head of answers when it can; one that never
        // opens is said by the failure, not by waiting here for good.
        withTimeoutOrNull(OPEN_WAIT_MS) { opened.await() }
        applyTrackPlan(prefs)
    }

    private fun applyTrackPlan(prefs: OpenPrefs) {
        val list = _tracks.value
        prefs.resume?.let { r ->
            r.audioTrack?.let { want -> list.firstOrNull { it.type == TrackType.Audio && it.id == want }?.let { selectTrack(TrackType.Audio, it.id) } }
        }
        prefs.audioLanguage?.let { lang ->
            list.firstOrNull { it.type == TrackType.Audio && it.language?.startsWith(lang, ignoreCase = true) == true }
                ?.takeIf { !it.selected }?.let { selectTrack(TrackType.Audio, it.id) }
        }
        when (val plan = prefs.subtitles) {
            is SubtitlePlan.On -> plan.language?.let { lang ->
                list.firstOrNull { it.type == TrackType.Subtitle && it.language?.startsWith(lang, ignoreCase = true) == true }
            }?.let { selectTrack(TrackType.Subtitle, it.id) }
            else -> Unit
        }
    }

    private fun read(o: JsonObject) {
        fun d(key: String) = (o[key] as? JsonPrimitive)?.doubleOrNull ?: 0.0
        fun b(key: String) = (o[key] as? JsonPrimitive)?.booleanOrNull ?: false
        fun i(key: String) = (o[key] as? JsonPrimitive)?.intOrNull ?: 0
        engineName = (o["engine"] as? JsonPrimitive)?.content.orEmpty()
        val total = d("readBytes").toLong()
        if (readBytesAt < 0) readBytesAt = total
        readBytes = (total - readBytesAt).coerceAtLeast(0)
        val before = _state.value
        _state.value = before.copy(
            opened = b("opened"),
            positionMs = (d("pos") * 1000).toLong(),
            durationMs = (d("dur") * 1000).toLong(),
            bufferedMs = (d("buf") * 1000).toLong(),
            playing = b("playing"),
            buffering = b("buffering"),
            seekPending = b("seeking"),
            ended = b("ended"),
            videoWidth = i("w"),
            videoHeight = i("h"),
            volume = (d("volume") * 100).toInt().coerceIn(0, 100),
            muted = b("muted"),
            speed = d("speed").toFloat().takeIf { it > 0f } ?: 1f,
            error = null,
            // The lists come only when they changed (video-host.js), so a position update parses
            // a few numbers rather than every track and chapter four times a second.
            chapters = if (!o.containsKey("chapters")) before.chapters else (o["chapters"] as? JsonArray).orEmpty().mapNotNull { c ->
                val co = c as? JsonObject ?: return@mapNotNull null
                Chapter(
                    title = (co["title"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() },
                    startMs = (((co["start"] as? JsonPrimitive)?.doubleOrNull ?: 0.0) * 1000).toLong(),
                )
            },
        )
        if (!o.containsKey("audio") && !o.containsKey("subtitles")) return
        val list = ArrayList<MediaTrack>()
        audioIds.clear()
        subtitleIds.clear()
        (o["audio"] as? JsonArray).orEmpty().forEachIndexed { n, t ->
            val to = t as? JsonObject ?: return@forEachIndexed
            audioIds += (to["id"] as? JsonPrimitive)?.content.orEmpty()
            list += track(to, n + 1, TrackType.Audio)
        }
        (o["subtitles"] as? JsonArray).orEmpty().forEachIndexed { n, t ->
            val to = t as? JsonObject ?: return@forEachIndexed
            subtitleIds += (to["id"] as? JsonPrimitive)?.content.orEmpty()
            list += track(to, n + 1, TrackType.Subtitle)
        }
        if (list != _tracks.value) _tracks.value = list
    }

    private fun track(o: JsonObject, id: Int, type: TrackType): MediaTrack = MediaTrack(
        id = id,
        type = type,
        title = (o["label"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() },
        language = (o["lang"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() },
        codec = (o["codec"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" },
        channels = (o["channels"] as? JsonPrimitive)?.intOrNull,
        selected = (o["enabled"] as? JsonPrimitive)?.booleanOrNull ?: false,
        isDefault = false,
        external = false,
    )

    private fun checkLoop() {
        val l = loop ?: return
        val s = _state.value
        val end = l.endMs ?: return
        if (s.positionMs >= end) seekTo(l.startMs)
    }

    override fun play() {
        if (_state.value.ended) seekTo(0)
        videoPlay()
    }

    override fun pause() = videoPause()

    override fun stop() {
        videoPause()
    }

    override fun togglePlay() {
        if (_state.value.playing && !_state.value.ended) pause() else play()
    }

    override fun seekTo(positionMs: Long) {
        _state.value = _state.value.copy(positionMs = positionMs.coerceAtLeast(0), seekPending = true)
        videoSeek(positionMs.coerceAtLeast(0) / 1000.0)
    }

    override fun seekBy(deltaMs: Long) = seekTo(_state.value.positionMs + deltaMs)

    override fun frameStep(forward: Boolean) = videoFrameStep(forward)

    override fun setSpeed(speed: Float) {
        _state.value = _state.value.copy(speed = speed)
        videoSpeed(speed.toDouble())
    }

    override fun setVolume(percent: Int) {
        _state.value = _state.value.copy(volume = percent.coerceIn(0, 100))
        videoVolume(percent.coerceIn(0, 100) / 100.0)
    }

    override fun setMuted(muted: Boolean) {
        _state.value = _state.value.copy(muted = muted)
        videoMuted(muted)
    }

    override fun selectTrack(type: TrackType, id: Int?) {
        when (type) {
            TrackType.Audio -> audioIds.getOrNull((id ?: return) - 1)?.let(::videoAudio)
            TrackType.Subtitle -> videoSubtitle(id?.let { subtitleIds.getOrNull(it - 1) } ?: "")
            TrackType.Video -> Unit
        }
    }

    override fun setScale(scale: VideoScale) {
        _state.value = _state.value.copy(scale = scale)
        videoShape(shapeOf(scale))
    }

    private fun shapeOf(scale: VideoScale): String = when (scale) {
        VideoScale.Crop -> "crop"
        VideoScale.Stretch -> "stretch"
        else -> "fit"
    }

    override fun setDownmix(stereo: Boolean) {
        _state.value = _state.value.copy(downmix = stereo)
    }

    override fun setVolumeBoost(on: Boolean) {
        _state.value = _state.value.copy(volumeBoost = on)
        videoStable(on || _state.value.nightMode)
    }

    override fun setNightMode(on: Boolean) {
        _state.value = _state.value.copy(nightMode = on)
        videoStable(on || _state.value.volumeBoost)
    }

    override fun setSubtitleStyle(style: SubtitleStyle) {
        this.style = style
        _state.value = _state.value.copy(subtitleStyle = style)
    }

    override fun setSubtitleDelay(ms: Long) {
        subtitleDelayMs = ms
        _state.value = _state.value.copy(subtitleDelayMs = ms)
        videoSubtitleDelay(ms / 1000.0)
    }

    override fun setAudioDelay(ms: Long) {}

    @Suppress("UNUSED_PARAMETER")
    override fun addSubtitle(path: String): Boolean = false

    @Suppress("UNUSED_PARAMETER")
    override suspend fun screenshot(file: java.io.File, withSubtitles: Boolean): Boolean = false

    override fun setAbLoop(loop: AbLoop?) {
        this.loop = loop
    }

    override fun details(): List<Pair<String, String>> {
        val s = _state.value
        return listOfNotNull(
            "Engine" to engineName,
            if (s.videoWidth > 0) "Picture" to "${s.videoWidth} x ${s.videoHeight}" else null,
        )
    }

    /** Tries the engines again from where the video got to, after a failure. */
    /**
     * Whether the browser can float this video in a picture in picture window, the browser's
     * mini player: its own player can where the browser allows it, movi-player through Document
     * Picture in Picture, and never in a Home Screen app on iOS (web/player/player.js).
     */
    fun canPip(): Boolean = runCatching { videoCanPip() }.getOrDefault(false)

    /** Into the picture in picture window, or back out of it. Called from the press itself. */
    fun pip() = videoPip()

    fun retry() {
        _failure.value = null
        videoRetry()
    }

    override fun close() {
        if (!open) return
        open = false
        videoClose()
    }

    private companion object {
        const val OPEN_WAIT_MS = 20_000L
    }
}
