package com.tmplayer.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A small file of named values, the shape DataStore preferences have: the stores the screens read
 * (Watched, Watch later, the settings the browser build keeps) are written against this, so the
 * same keys and the same encodings hold whether the file is DataStore on Android and the desktop
 * or the browser's own storage.
 *
 * Values are what DataStore preferences hold: String, Boolean, Int, Long, Float, Double and
 * Set<String>. Anything else is refused by [edit].
 */
interface KeyValueStore {
    /** Every entry, as it is now and after each change. */
    val data: Flow<Map<String, Any>>

    /** The entries as they are now. */
    suspend fun snapshot(): Map<String, Any>

    /**
     * Changes the entries in one step: [change] sees them as they are and edits the map in place
     * (put to write, remove to delete). Edits never interleave.
     */
    suspend fun edit(change: (MutableMap<String, Any>) -> Unit)
}

/** Whether [value] is a type a [KeyValueStore] keeps. */
internal fun isStorable(value: Any): Boolean = when (value) {
    is String, is Boolean, is Int, is Long, is Float, is Double -> true
    is Set<*> -> value.all { it is String }
    else -> false
}

/**
 * A [KeyValueStore] in memory, which hands each new state to [persist]: the browser build saves it
 * as [KeyValueCodec] text, and a test passes nothing.
 */
class MemoryKeyValueStore(
    initial: Map<String, Any> = emptyMap(),
    private val persist: (Map<String, Any>) -> Unit = {},
) : KeyValueStore {
    private val state = MutableStateFlow(initial.toMap())
    private val lock = Mutex()

    override val data: Flow<Map<String, Any>> = state.asStateFlow()

    override suspend fun snapshot(): Map<String, Any> = state.value

    override suspend fun edit(change: (MutableMap<String, Any>) -> Unit) {
        lock.withLock {
            val next = LinkedHashMap(state.value)
            change(next)
            require(next.values.all(::isStorable)) { "a value of a type the store does not keep" }
            if (next != state.value) {
                state.value = next
                persist(next)
            }
        }
    }
}

/**
 * The text a [KeyValueStore] is kept as where there is only text to keep it in (the browser's
 * local storage, a backup): a JSON object of name to `[type, value]`, so a Long read back is a
 * Long and not a Double. Types: `s` String, `b` Boolean, `i` Int, `l` Long, `f` Float, `d` Double,
 * `S` Set<String>.
 */
object KeyValueCodec {
    fun encode(entries: Map<String, Any>): String {
        val obj = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        for ((name, value) in entries) {
            val pair = when (value) {
                is String -> listOf(JsonPrimitive("s"), JsonPrimitive(value))
                is Boolean -> listOf(JsonPrimitive("b"), JsonPrimitive(value))
                // On Kotlin/JS a Float or a Double passes `is Int` too: only a whole one is written as an Int.
                is Int -> if (value.toDouble() % 1.0 == 0.0) listOf(JsonPrimitive("i"), JsonPrimitive(value)) else listOf(JsonPrimitive("d"), JsonPrimitive(value.toDouble()))
                is Long -> listOf(JsonPrimitive("l"), JsonPrimitive(value.toString()))
                is Float -> listOf(JsonPrimitive("f"), JsonPrimitive(value.toDouble()))
                is Double -> listOf(JsonPrimitive("d"), JsonPrimitive(value))
                is Set<*> -> listOf(JsonPrimitive("S"), JsonArray(value.map { JsonPrimitive(it as String) }))
                else -> continue
            }
            obj[name] = JsonArray(pair)
        }
        return JsonObject(obj).toString()
    }

    /** The entries in [text]; anything that does not read is left out, never fatal. */
    fun decode(text: String?): Map<String, Any> {
        if (text.isNullOrBlank()) return emptyMap()
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, Any>()
        for ((name, element) in root) {
            val pair = runCatching { element.jsonArray }.getOrNull()?.takeIf { it.size == 2 } ?: continue
            val type = runCatching { pair[0].jsonPrimitive.content }.getOrNull() ?: continue
            val v = pair[1]
            val value: Any? = runCatching {
                when (type) {
                    "s" -> v.jsonPrimitive.content
                    "b" -> v.jsonPrimitive.content.toBooleanStrictOrNull()
                    "i" -> v.jsonPrimitive.content.toIntOrNull()
                    "l" -> v.jsonPrimitive.content.toLongOrNull()
                    "f" -> v.jsonPrimitive.content.toDoubleOrNull()?.toFloat()
                    "d" -> v.jsonPrimitive.content.toDoubleOrNull()
                    "S" -> v.jsonArray.map { it.jsonPrimitive.content }.toSet()
                    else -> null
                }
            }.getOrNull()
            if (value != null) out[name] = value
        }
        return out
    }
}
