package com.tmplayer.i18n

internal actual fun parseCatalog(json: String): Map<String, String> = catalogFromJson(json)
