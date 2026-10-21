package com.tmplayer.json

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray as KxArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject as KxObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * The part of org.json's API `:core` used, over kotlinx-serialization, so the documents the apps
 * read and write (online metadata, its cache, subtitles, backups, the update feed) compile for the
 * browser too with their code unchanged but for the import. The org.json rules are kept where
 * callers lean on them: the opt readers never throw and fall back the same way (a missing string
 * is "", a number given as text still reads as a number), a missing value for a get reader throws
 * [JSONException], and JSON null is [JSONObject.NULL].
 */

/** What org.json throws for text that does not parse, or a get of something that is not there. */
class JSONException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

private fun fromElement(element: JsonElement): Any = when (element) {
    is JsonNull -> JSONObject.NULL
    is KxObject -> JSONObject(element)
    is KxArray -> JSONArray(element)
    is JsonPrimitive -> when {
        element.isString -> element.content
        element.content == "true" -> true
        element.content == "false" -> false
        else -> numberOf(element.content) ?: element.content
    }
}

private fun numberOf(text: String): Number? {
    if (text.none { it == '.' || it == 'e' || it == 'E' }) {
        text.toIntOrNull()?.let { return it }
        text.toLongOrNull()?.let { return it }
    }
    return text.toDoubleOrNull()
}

private fun toElement(value: Any?): JsonElement = when (value) {
    null, JSONObject.NULL -> JsonNull
    is JSONObject -> value.toElement()
    is JSONArray -> value.toElement()
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Double -> if (value == kotlin.math.floor(value) && !value.isInfinite() && kotlin.math.abs(value) < 1e15) JsonPrimitive(value.toLong()) else JsonPrimitive(value)
    is Float -> toElement(value.toDouble())
    is Number -> JsonPrimitive(value)
    is Map<*, *> -> JSONObject(value).toElement()
    is Collection<*> -> JSONArray(value).toElement()
    else -> JsonPrimitive(value.toString())
}

private fun asInt(value: Any?, fallback: Int): Int = when (value) {
    is Number -> value.toInt()
    is String -> value.trim().toIntOrNull() ?: value.trim().toDoubleOrNull()?.toInt() ?: fallback
    else -> fallback
}

private fun asLong(value: Any?, fallback: Long): Long = when (value) {
    is Number -> value.toLong()
    is String -> value.trim().toLongOrNull() ?: value.trim().toDoubleOrNull()?.toLong() ?: fallback
    else -> fallback
}

private fun asDouble(value: Any?, fallback: Double): Double = when (value) {
    is Number -> value.toDouble()
    is String -> value.trim().toDoubleOrNull() ?: fallback
    else -> fallback
}

private fun asBoolean(value: Any?, fallback: Boolean): Boolean = when (value) {
    is Boolean -> value
    is String -> when (value.lowercase()) {
        "true" -> true
        "false" -> false
        else -> fallback
    }
    else -> fallback
}

/** A JSON object, as org.json's: insertion ordered, mutable through [put]. */
class JSONObject() {
    private val map = LinkedHashMap<String, Any>()

    /** Parses [text]; throws [JSONException] when it is not a JSON object. */
    constructor(text: String) : this() {
        val element = try {
            Json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw JSONException("not JSON: ${e.message}", e)
        }
        val obj = element as? KxObject ?: throw JSONException("not a JSON object")
        for ((k, v) in obj) map[k] = fromElement(v)
    }

    internal constructor(obj: KxObject) : this() {
        for ((k, v) in obj) map[k] = fromElement(v)
    }

    /** From a map of plain values, as `JSONObject(Map)`. */
    constructor(values: Map<*, *>) : this() {
        for ((k, v) in values) if (k != null) map[k.toString()] = wrap(v)
    }

    fun put(key: String, value: Any?): JSONObject {
        if (value == null) map.remove(key) else map[key] = wrap(value)
        return this
    }

    fun put(key: String, value: Int): JSONObject = put(key, value as Any?)
    fun put(key: String, value: Long): JSONObject = put(key, value as Any?)
    fun put(key: String, value: Double): JSONObject = put(key, value as Any?)
    fun put(key: String, value: Boolean): JSONObject = put(key, value as Any?)

    /** As org.json: only when there is no value yet. */
    fun putOpt(key: String, value: Any?): JSONObject {
        if (value != null) put(key, value)
        return this
    }

    fun remove(key: String): Any? = map.remove(key)

    fun has(key: String): Boolean = key in map
    fun isNull(key: String): Boolean = map[key].let { it == null || it == NULL }
    fun length(): Int = map.size
    fun keys(): Iterator<String> = map.keys.toList().iterator()
    fun keySet(): Set<String> = map.keys
    fun names(): JSONArray? = if (map.isEmpty()) null else JSONArray(map.keys.toList())

    fun opt(key: String): Any? = map[key]
    fun get(key: String): Any = map[key] ?: throw JSONException("JSONObject[\"$key\"] not found.")

    fun optString(key: String): String = optString(key, "")
    fun optString(key: String, fallback: String): String = map[key].let { if (it == null || it == NULL) fallback else it.toString() }
    fun optInt(key: String): Int = asInt(map[key], 0)
    fun optInt(key: String, fallback: Int): Int = asInt(map[key], fallback)
    fun optLong(key: String): Long = asLong(map[key], 0L)
    fun optLong(key: String, fallback: Long): Long = asLong(map[key], fallback)
    fun optDouble(key: String): Double = asDouble(map[key], Double.NaN)
    fun optDouble(key: String, fallback: Double): Double = asDouble(map[key], fallback)
    fun optBoolean(key: String): Boolean = asBoolean(map[key], false)
    fun optBoolean(key: String, fallback: Boolean): Boolean = asBoolean(map[key], fallback)
    fun optJSONObject(key: String): JSONObject? = map[key] as? JSONObject
    fun optJSONArray(key: String): JSONArray? = map[key] as? JSONArray

    fun getString(key: String): String = get(key).let { it as? String ?: throw JSONException("JSONObject[\"$key\"] is not a string.") }
    fun getInt(key: String): Int = get(key).let { v -> asInt(v, Int.MIN_VALUE).takeIf { v is Number || it != Int.MIN_VALUE } ?: throw JSONException("JSONObject[\"$key\"] is not an int.") }
    fun getLong(key: String): Long = get(key).let { v -> if (v is Number || v is String && v.trim().toDoubleOrNull() != null) asLong(v, 0L) else throw JSONException("JSONObject[\"$key\"] is not a long.") }
    fun getDouble(key: String): Double = get(key).let { v -> if (v is Number || v is String && v.trim().toDoubleOrNull() != null) asDouble(v, 0.0) else throw JSONException("JSONObject[\"$key\"] is not a number.") }
    fun getBoolean(key: String): Boolean = get(key).let { v -> v as? Boolean ?: (v as? String)?.lowercase()?.toBooleanStrictOrNull() ?: throw JSONException("JSONObject[\"$key\"] is not a Boolean.") }
    fun getJSONObject(key: String): JSONObject = get(key) as? JSONObject ?: throw JSONException("JSONObject[\"$key\"] is not a JSONObject.")
    fun getJSONArray(key: String): JSONArray = get(key) as? JSONArray ?: throw JSONException("JSONObject[\"$key\"] is not a JSONArray.")

    /** The plain values, nested maps and lists, as org.json's toMap. */
    fun toMap(): Map<String, Any?> = map.mapValues { (_, v) -> unwrap(v) }

    internal fun toElement(): KxObject = KxObject(map.mapValues { toElement(it.value) })

    override fun toString(): String = toElement().toString()

    /** Laid out over lines, as org.json's toString(indent) writes a backup file. */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    fun toString(indent: Int): String =
        Json { prettyPrint = true; prettyPrintIndent = " ".repeat(indent.coerceAtLeast(1)) }
            .encodeToString(KxObject.serializer(), toElement())

    override fun equals(other: Any?): Boolean = other is JSONObject && other.toElement() == toElement()
    override fun hashCode(): Int = toElement().hashCode()

    companion object {
        /** JSON's null, as org.json's JSONObject.NULL. */
        val NULL: Any = object {
            override fun toString(): String = "null"
            override fun equals(other: Any?): Boolean = other === this || other == null
            override fun hashCode(): Int = 0
        }

        internal fun wrap(value: Any?): Any = when (value) {
            null -> NULL
            is JSONObject, is JSONArray, is String, is Boolean, is Number -> value
            is Map<*, *> -> JSONObject(value)
            is Collection<*> -> JSONArray(value)
            is Array<*> -> JSONArray(value.toList())
            else -> if (value === NULL) value else value.toString()
        }

        internal fun unwrap(value: Any?): Any? = when (value) {
            NULL -> null
            is JSONObject -> value.toMap()
            is JSONArray -> value.toList()
            else -> value
        }
    }
}

/** A JSON array, as org.json's. */
class JSONArray() : Iterable<Any?> {
    private val list = ArrayList<Any>()

    constructor(text: String) : this() {
        val element = try {
            Json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw JSONException("not JSON: ${e.message}", e)
        }
        val array = element as? KxArray ?: throw JSONException("not a JSON array")
        for (v in array) list += fromElement(v)
    }

    internal constructor(array: KxArray) : this() {
        for (v in array) list += fromElement(v)
    }

    constructor(values: Collection<*>) : this() {
        for (v in values) list += JSONObject.wrap(v)
    }

    fun put(value: Any?): JSONArray {
        list += JSONObject.wrap(value)
        return this
    }

    fun put(value: Int): JSONArray = put(value as Any?)
    fun put(value: Long): JSONArray = put(value as Any?)
    fun put(value: Double): JSONArray = put(value as Any?)
    fun put(value: Boolean): JSONArray = put(value as Any?)

    fun put(index: Int, value: Any?): JSONArray {
        while (list.size <= index) list += JSONObject.NULL
        list[index] = JSONObject.wrap(value)
        return this
    }

    fun length(): Int = list.size
    fun isNull(index: Int): Boolean = list.getOrNull(index).let { it == null || it == JSONObject.NULL }
    fun opt(index: Int): Any? = list.getOrNull(index)
    fun get(index: Int): Any = list.getOrNull(index) ?: throw JSONException("JSONArray[$index] not found.")
    fun remove(index: Int): Any? = if (index in list.indices) list.removeAt(index) else null

    fun optString(index: Int): String = optString(index, "")
    fun optString(index: Int, fallback: String): String = list.getOrNull(index).let { if (it == null || it == JSONObject.NULL) fallback else it.toString() }
    fun optInt(index: Int): Int = asInt(list.getOrNull(index), 0)
    fun optInt(index: Int, fallback: Int): Int = asInt(list.getOrNull(index), fallback)
    fun optLong(index: Int): Long = asLong(list.getOrNull(index), 0L)
    fun optLong(index: Int, fallback: Long): Long = asLong(list.getOrNull(index), fallback)
    fun optDouble(index: Int): Double = asDouble(list.getOrNull(index), Double.NaN)
    fun optDouble(index: Int, fallback: Double): Double = asDouble(list.getOrNull(index), fallback)
    fun optBoolean(index: Int): Boolean = asBoolean(list.getOrNull(index), false)
    fun optJSONObject(index: Int): JSONObject? = list.getOrNull(index) as? JSONObject
    fun optJSONArray(index: Int): JSONArray? = list.getOrNull(index) as? JSONArray

    fun getString(index: Int): String = get(index) as? String ?: throw JSONException("JSONArray[$index] is not a string.")
    fun getInt(index: Int): Int = get(index).let { v -> if (v is Number || v is String && v.trim().toDoubleOrNull() != null) asInt(v, 0) else throw JSONException("JSONArray[$index] is not a number.") }
    fun getLong(index: Int): Long = get(index).let { v -> if (v is Number || v is String && v.trim().toDoubleOrNull() != null) asLong(v, 0L) else throw JSONException("JSONArray[$index] is not a number.") }
    fun getJSONObject(index: Int): JSONObject = get(index) as? JSONObject ?: throw JSONException("JSONArray[$index] is not a JSONObject.")
    fun getJSONArray(index: Int): JSONArray = get(index) as? JSONArray ?: throw JSONException("JSONArray[$index] is not a JSONArray.")

    fun toList(): List<Any?> = list.map { JSONObject.unwrap(it) }

    override fun iterator(): Iterator<Any?> = list.toList().iterator()

    internal fun toElement(): KxArray = KxArray(list.map { toElement(it) })

    override fun toString(): String = toElement().toString()

    override fun equals(other: Any?): Boolean = other is JSONArray && other.toElement() == toElement()
    override fun hashCode(): Int = toElement().hashCode()
}
