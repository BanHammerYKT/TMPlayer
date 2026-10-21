package com.tmplayer.online

/** [MetaRules.region] from a JDK locale, as the JVM callers hold one. */
fun MetaRules.region(locale: java.util.Locale): String = region(locale.toLanguageTag())
