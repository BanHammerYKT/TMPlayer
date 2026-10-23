package com.tmplayer.i18n

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** [json], a catalog, as dotted keys to message text, read with kotlinx.serialization. */
internal fun catalogFromJson(json: String): Map<String, String> {
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
