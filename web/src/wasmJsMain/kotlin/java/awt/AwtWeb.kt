// The few AWT names the desktop's screens use, for their browser build: the clipboard, and the
// process wide input listener that hides the focus ring on a click (here Compose's own pointer
// and key handling do that, see FocusVisible.trackFocusVisibility).
package java.awt

import java.awt.datatransfer.Clipboard

open class AWTEvent(val id: Int) {
    companion object {
        const val MOUSE_EVENT_MASK = 0x10L
        const val KEY_EVENT_MASK = 0x08L
    }
}

fun interface AWTEventListener {
    fun eventDispatched(event: AWTEvent)
}

class Toolkit private constructor() {
    val systemClipboard: Clipboard = Clipboard()

    @Suppress("UNUSED_PARAMETER")
    fun addAWTEventListener(listener: AWTEventListener, mask: Long) {}

    @Suppress("UNUSED_PARAMETER")
    fun getDesktopProperty(name: String): Any? = null

    companion object {
        private val one = Toolkit()
        fun getDefaultToolkit(): Toolkit = one
    }
}
