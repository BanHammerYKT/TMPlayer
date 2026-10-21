package com.tmplayer.i18n

import com.tmplayer.platform.Logger
import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The UI language for the whole process. Catalogs are `i18n/<tag>.json` on the classpath (the
 * resources of `:core`, so the same files on Android and the desktop; the browser fetches the same
 * files and hands them in through [provide]), nested objects whose keys join with dots:
 * `{"browse": {"videos_count": "..."}}` is `browse.videos_count`.
 *
 * Compose reads [active] through `LocalStrings` in `:ui`, so a change recomposes in place. Code
 * outside Compose (notifications, the tray, the track picker) reads [L], which is always the
 * current language.
 */
object Translator {
    private const val TAG = "Translator"

    /** Lets the `en-XA` pseudo-locale resolve. Set by debug builds only. */
    @Volatile
    var pseudoEnabled: Boolean = false

    private val englishPatterns: Map<String, Icu.Pattern> by lazy {
        val source = resource(Languages.ENGLISH) ?: error("i18n/en.json is missing")
        parse(source).mapValues { Icu.parse(it.value) }
    }

    private val state: MutableStateFlow<Messages> by lazy { MutableStateFlow(english()) }

    /** The current language's text, as a flow for Compose. */
    val active: StateFlow<Messages> get() = state.asStateFlow()

    /** The current language's text. */
    val messages: Messages get() = state.value

    /**
     * Picks the language from the saved setting ("" or null follows the system) and the device's
     * preferred tags, loads it, and returns the tag chosen. Cheap when nothing changes.
     */
    fun select(saved: String?, system: List<String>): String {
        this.system = system
        val tag = Languages.resolve(saved, system, pseudoEnabled)
        use(tag)
        return tag
    }

    /**
     * The device's own languages as last handed to [select], or empty before the first call.
     *
     * Kept because the process locale cannot answer "what would System default be": on Android
     * 13 and later a per-app language rewrites `Locale.getDefault()`, so with Arabic picked the
     * picker's "System default" row read Arabic too.
     */
    @Volatile
    var system: List<String> = emptyList()
        private set

    /** The tag "System default" stands for: [system] resolved with nothing saved. */
    fun systemDefault(fallback: List<String> = emptyList()): String =
        Languages.resolve("", system.ifEmpty { fallback }, pseudoEnabled)

    /** Switches to [tag], one of [Languages.tags] or [Languages.PSEUDO]. */
    fun use(tag: String) {
        if (state.value.tag == tag) return
        state.value = load(tag)
        Logger.i(TAG, "UI language $tag")
    }

    /** English, the source of truth every other catalog falls back to key by key. */
    fun english(): Messages = Messages(Languages.ENGLISH, englishPatterns, englishPatterns)

    /** The messages for [tag], read from the classpath. A missing catalog is all English. */
    fun load(tag: String): Messages = when (tag) {
        Languages.ENGLISH -> english()
        Languages.PSEUDO -> pseudo(englishPatterns)
        else -> build(tag, resource(tag), englishPatterns)
    }

    /** The pseudo-locale over [english]: same keys and arguments, accented text. */
    internal fun pseudo(english: Map<String, Icu.Pattern>): Messages =
        Messages(Languages.PSEUDO, english.mapValues { it.value.mapText(Pseudo::accent) }, english, pseudo = true)

    /**
     * [tag]'s messages from its catalog JSON over [english]. A translation that does not parse, or
     * that uses an argument the English does not pass, is dropped so that key reads in English.
     */
    internal fun build(tag: String, json: String?, english: Map<String, Icu.Pattern>): Messages {
        val own = HashMap<String, Icu.Pattern>()
        if (json != null) {
            val entries = runCatching { parse(json) }
                .onFailure { Logger.w(TAG, "i18n/$tag.json does not parse", it) }
                .getOrDefault(emptyMap())
            for ((key, text) in entries) {
                val reference = english[key] ?: continue
                val pattern = runCatching { Icu.parse(text) }.getOrNull()
                if (pattern == null || !reference.args.keys.containsAll(pattern.args.keys)) {
                    Logger.w(TAG, "$tag: $key does not match the English, using English")
                    continue
                }
                own[key] = pattern
            }
        }
        return Messages(tag, own, english)
    }

    /** A catalog's JSON as dotted keys to message text. */
    fun parse(json: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        fun walk(prefix: String, node: JsonObject) {
            for ((name, value) in node) {
                val key = if (prefix.isEmpty()) name else "$prefix.$name"
                when {
                    value is JsonObject -> walk(key, value)
                    value is JsonPrimitive && value.isString -> out[key] = value.content
                    else -> throw IllegalArgumentException("$key is not text")
                }
            }
        }
        walk("", Json.parseToJsonElement(json).jsonObject)
        return out
    }

    /** Copied on write, so a reader on another thread never sees it half changed. */
    @Volatile
    private var translated: Map<String, Boolean> = emptyMap()

    /** Catalogs handed in rather than read off the classpath: the browser fetches its own. */
    @Volatile
    private var provided: Map<String, String> = emptyMap()

    /** Makes [json] the catalog for [tag], for a platform with no classpath (the browser). */
    fun provide(tag: String, json: String) {
        provided = provided + (tag to json)
        translated = translated - tag
    }

    /**
     * Whether [tag] has text of its own: English always, any other language once its catalog
     * ships with at least one key. An empty `{}` catalog, waiting for its first translation run,
     * reads all in English and does not count.
     */
    fun hasCatalog(tag: String): Boolean =
        tag == Languages.ENGLISH || translated[tag] ?: run {
            val has = resource(tag)?.let { json -> runCatching { parse(json).isNotEmpty() }.getOrDefault(false) } ?: false
            translated = translated + (tag to has)
            has
        }

    /** `i18n/<tag>.json` from the classpath (or [provide]), or null when that language has no catalog yet. */
    fun resource(tag: String): String? = provided[tag] ?: bundledCatalog(tag)
}
