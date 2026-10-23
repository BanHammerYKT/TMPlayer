package com.tmplayer.i18n

/** The browser's JSON.parse, so the web player's rules carry no JSON decoder of their own. */
internal actual fun parseCatalog(json: String): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    fun walk(prefix: String, node: dynamic) {
        for (name in js("Object").keys(node).unsafeCast<Array<String>>()) {
            val key = if (prefix.isEmpty()) name else "$prefix.$name"
            val value = node[name]
            when {
                jsTypeOf(value) == "string" -> out[key] = value.unsafeCast<String>()
                jsTypeOf(value) == "object" && value != null && !js("Array").isArray(value).unsafeCast<Boolean>() -> walk(key, value)
                else -> throw IllegalArgumentException("$key is not text")
            }
        }
    }
    walk("", JSON.parse<Any>(json))
    return out
}
