package com.tmplayer.web.td

import com.tmplayer.web.js.TmTd
import com.tmplayer.web.js.text
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** TDLib answered a request with an error. [message] is TDLib's own, such as `PHONE_CODE_INVALID`. */
class TdError(val code: Int, override val message: String) : Exception(message)

/**
 * TDLib in the page (tdweb), spoken to as JSON. Every request is a [JsonObject] with an `@type`;
 * every answer and update comes back the same way, parsed with kotlinx-serialization and read with
 * the small helpers below, so no DTO classes are generated: fields a newer or older TDLib adds or
 * drops are simply absent or ignored.
 */
class WebTd {
    private val handlers = ArrayList<(JsonObject) -> Unit>()

    /** Called for every update, in order, on the page's one thread. */
    fun onUpdate(handler: (JsonObject) -> Unit) {
        handlers += handler
    }

    val started: Boolean get() = TmTd.started

    /** Opens the client. Updates start arriving at once, the first being the authorization state. */
    fun start() {
        if (TmTd.started) return
        val options = buildJsonObject {
            put("instanceName", "tmplayer")
            put("jsLogVerbosityLevel", "error")
            put("logVerbosityLevel", 1)
        }
        TmTd.start(options.toString()) { text ->
            val update = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return@start
            for (h in handlers.toList()) runCatching { h(update) }.onFailure { println("TMPlayer: update handler failed: ${it.message}") }
        }
    }

    /** Sends [query] and returns TDLib's answer, or throws [TdError]. */
    suspend fun call(query: JsonObject): JsonObject {
        val text = TmTd.send(query.toString()).text()
        val answer = Json.parseToJsonElement(text) as? JsonObject ?: throw TdError(0, "no answer")
        if (answer.type == "error") throw TdError(answer.int("code"), answer.str("message"))
        return answer
    }

    /** [call] for a request built in place: `td.call("getChat") { put("chat_id", id) }`. */
    suspend fun call(type: String, fields: JsonObjectBuilder.() -> Unit = {}): JsonObject =
        call(buildJsonObject {
            put("@type", type)
            fields()
        })

    /** [call] that answers null instead of throwing. */
    suspend fun tryCall(type: String, fields: JsonObjectBuilder.() -> Unit = {}): JsonObject? =
        runCatching { call(type, fields) }.getOrNull()

    suspend fun close() {
        runCatching { TmTd.close() }
    }
}

// Reading TDLib JSON. Every reader tolerates a missing or differently typed field.

val JsonObject.type: String get() = str("@type")

fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""

fun JsonObject.int(key: String): Int = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() } ?: 0

/** TDLib writes int64 fields as strings in JSON; both forms are read. */
fun JsonObject.long(key: String): Long = (this[key] as? JsonPrimitive)?.let {
    it.longOrNull ?: it.content.toLongOrNull() ?: it.doubleOrNull?.toLong()
} ?: 0L

fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

fun JsonObject.arr(key: String): List<JsonElement> = (this[key] as? JsonArray).orEmpty()

fun JsonObject.objects(key: String): List<JsonObject> = arr(key).mapNotNull { it as? JsonObject }

fun JsonObject.longs(key: String): List<Long> = arr(key).mapNotNull { (it as? JsonPrimitive)?.let { p -> p.longOrNull ?: p.content.toLongOrNull() } }

fun JsonObject.ints(key: String): List<Int> = arr(key).mapNotNull { (it as? JsonPrimitive)?.intOrNull }

/** A path of objects: `msg.at("content", "video", "video")`. */
fun JsonObject.at(vararg keys: String): JsonObject? {
    var o: JsonObject? = this
    for (k in keys) o = o?.obj(k)
    return o
}

/** int64 values go to TDLib as strings, which is what its JSON interface expects for them. */
fun JsonObjectBuilder.putLong(key: String, value: Long) {
    put(key, value.toString())
}

fun JsonObjectBuilder.putNull(key: String) {
    put(key, JsonNull)
}
