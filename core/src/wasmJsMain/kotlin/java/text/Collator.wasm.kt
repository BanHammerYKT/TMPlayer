// java.text.Collator on the browser's Intl.Collator (core's textCollator), for the shared screens.
package java.text

class Collator private constructor() : Comparator<Any?> {
    var strength: Int = TERTIARY

    override fun compare(a: Any?, b: Any?): Int =
        com.tmplayer.platform.textCollator(com.tmplayer.i18n.localeOf(com.tmplayer.i18n.Translator.messages.tag), strength <= SECONDARY)
            .compare(a?.toString().orEmpty(), b?.toString().orEmpty())

    companion object {
        const val PRIMARY = 0
        const val SECONDARY = 1
        const val TERTIARY = 2
        fun getInstance(): Collator = Collator()
        fun getInstance(@Suppress("UNUSED_PARAMETER") locale: com.tmplayer.i18n.TmLocale): Collator = Collator()
    }
}
