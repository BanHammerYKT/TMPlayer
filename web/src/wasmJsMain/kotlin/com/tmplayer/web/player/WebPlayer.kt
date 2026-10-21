package com.tmplayer.web.player

import com.tmplayer.i18n.Decimal
import com.tmplayer.i18n.Translator
import com.tmplayer.web.data.WebPrefs
import com.tmplayer.web.data.WebVideo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add

@JsFun("(f) => { window.TmStrings = { format: (key, args) => f(key, args || '') }; }")
private external fun installStrings(f: (String, String) -> String)

@JsFun("(spec, onEvent) => window.TmPlayer.open(spec, onEvent)")
private external fun jsOpen(spec: String, onEvent: (String) -> Unit)

@JsFun("() => window.TmPlayer && window.TmPlayer.close()")
private external fun jsClose()

@JsFun("(spec) => window.TmPlayer ? window.TmPlayer.choose(spec) : 'native'")
private external fun jsChoose(spec: String): String

@JsFun("() => window.TmPlayer && window.TmPlayer.preload()")
private external fun jsPreload()

@JsFun("(id, size, head, tail) => window.TmTd && window.TmTd.warm(id, size, head, tail)")
private external fun jsWarm(id: Int, size: Double, head: Double, tail: Double)

/** What the player reports back: where the viewer got to, and what they chose. */
sealed interface PlayerEvent {
    val positionSec: Double
    val durationSec: Double

    data class Progress(override val positionSec: Double, override val durationSec: Double) : PlayerEvent
    data class Closed(override val positionSec: Double, override val durationSec: Double) : PlayerEvent
    data class Ended(override val positionSec: Double, override val durationSec: Double) : PlayerEvent
    data class Next(override val positionSec: Double, override val durationSec: Double) : PlayerEvent
    data class Previous(override val positionSec: Double, override val durationSec: Double) : PlayerEvent
    data class Failed(val kind: String, override val positionSec: Double, override val durationSec: Double) : PlayerEvent
}

/**
 * The Kotlin side of web/player/player.js: opens a video in the player overlay and hears back
 * from it. The catalog is handed to the player as a formatter, so its words are the app's own,
 * in the viewer's language.
 */
object WebPlayer {

    fun installStrings() {
        installStrings { key, args -> format(key, args) }
    }

    private fun format(key: String, argsJson: String): String {
        val messages = Translator.messages
        if (argsJson.isBlank()) return messages.text(key)
        val args = runCatching { Json.parseToJsonElement(argsJson).jsonObject }.getOrNull() ?: return messages.text(key)
        return messages.format(key, args.mapValues { (_, v) -> value(v) })
    }

    private fun value(e: JsonElement): Any? {
        val p = e as? JsonPrimitive ?: return e.toString()
        if (p.isString) return p.content
        p.longOrNull?.let { return it }
        p.doubleOrNull?.let { return Decimal.parse(p.content) ?: it }
        p.booleanOrNull?.let { return it.toString() }
        return null
    }

    fun spec(
        video: WebVideo,
        prefs: WebPrefs,
        startSec: Double,
        title: String,
        subtitle: String,
        chat: String,
        hasNext: Boolean,
        hasPrevious: Boolean,
        next: WebVideo? = null,
    ): JsonObject =
        buildJsonObject {
            put("key", video.key)
            put("title", title)
            put("subtitle", subtitle)
            put("chat", chat)
            put("name", video.fileName)
            put("mime", video.mimeType)
            put("durationSec", video.durationSec)
            put("startSec", startSec)
            put("hasNext", hasNext)
            put("hasPrevious", hasPrevious)
            next?.let { n ->
                putJsonObject("nextWarm") {
                    put("id", n.fileId)
                    put("size", n.sizeBytes)
                }
            }
            putJsonObject("source") {
                putJsonArray("ids") { add(video.fileId) }
                putJsonArray("sizes") { add(video.sizeBytes) }
            }
            prefs.string("player")?.let { saved ->
                runCatching { Json.parseToJsonElement(saved) }.getOrNull()?.let { put("prefs", it) }
            }
        }

    fun open(spec: JsonObject, prefs: WebPrefs, onEvent: (PlayerEvent) -> Unit) {
        jsOpen(spec.toString()) { text ->
            val o = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return@jsOpen
            val pos = (o["pos"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
            val dur = (o["dur"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
            when ((o["type"] as? JsonPrimitive)?.content) {
                "progress" -> onEvent(PlayerEvent.Progress(pos, dur))
                "close" -> onEvent(PlayerEvent.Closed(pos, dur))
                "ended" -> onEvent(PlayerEvent.Ended(pos, dur))
                "next" -> onEvent(PlayerEvent.Next(pos, dur))
                "previous" -> onEvent(PlayerEvent.Previous(pos, dur))
                "error" -> onEvent(PlayerEvent.Failed((o["kind"] as? JsonPrimitive)?.content.orEmpty(), pos, dur))
                "prefs" -> o["prefs"]?.let { prefs.setString("player", it.toString()) }
            }
        }
    }

    fun close() = jsClose()

    private const val WARM_HEAD = 2.0 * 1024 * 1024
    private const val WARM_TAIL = 1.0 * 1024 * 1024

    /** "native" or "movi": the engine [video] would start in. */
    fun engineFor(video: WebVideo): String = jsChoose(
        buildJsonObject {
            put("name", video.fileName)
            put("mime", video.mimeType)
            putJsonObject("source") {
                putJsonArray("ids") { add(video.fileId) }
                putJsonArray("sizes") { add(video.sizeBytes) }
            }
        }.toString(),
    )

    /**
     * A card held under the pointer or the focus for a moment: the head of the file and its last
     * megabyte (where MP4 and MKV often keep their index) are fetched ahead, as reelbox does, and
     * the decoder starts loading if the file will need it.
     */
    fun warm(video: WebVideo) {
        jsWarm(video.fileId, video.sizeBytes.toDouble(), WARM_HEAD, WARM_TAIL)
        if (engineFor(video) == "movi") jsPreload()
    }
}
