package java.awt.datatransfer

import com.tmplayer.web.js.TmHost

class StringSelection(val text: String)

/** The system clipboard is the browser's (navigator.clipboard, through TmHost.copyText). */
class Clipboard {
    @Suppress("UNUSED_PARAMETER")
    fun setContents(contents: StringSelection, owner: Any?) {
        TmHost.copyText(contents.text)
    }
}
