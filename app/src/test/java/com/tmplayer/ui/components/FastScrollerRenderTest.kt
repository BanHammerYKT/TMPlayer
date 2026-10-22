package com.tmplayer.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.tmplayer.data.FormFactor
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * The hold to scroll handle on a phone, composed for real and drawn to PNGs under
 * `build/device-test/review/`: out after a scroll, held under a finger (the accent fill), and
 * dragged with its bubble, and the list moved by the drag.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FastScrollerRenderTest {

    private fun idle(ms: Long) = repeat((ms / 50).toInt()) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)) }

    private fun render(out: String) {
        // The answer is kept for the process, and a television test before this one leaves it set.
        FormFactor.override(false)
        val state = LazyListState(firstVisibleItemIndex = 60)
        var accent = 0
        var ground = 0
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            TMPlayerTheme {
                accent = Tone.accent.toArgb()
                ground = Tone.background.toArgb()
                Box(Modifier.fillMaxSize().background(Tone.background)) {
                    LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                        items(400, key = { it }) { i ->
                            // Words only on the start side, so the end edge is the page's own colour but for the handle.
                            Text("Video ${i + 1}", color = Tone.text, modifier = Modifier.fillMaxWidth().height(64.dp).padding(start = 16.dp, top = 20.dp))
                        }
                    }
                    FastScroller(state, label = { key -> "Episode ${(key as Int) + 1}" })
                    LaunchedEffect(Unit) {
                        state.scroll {
                            scrollBy(1f)
                            kotlinx.coroutines.delay(200)
                        }
                    }
                }
            }
        }
        idle(800)
        val root = activity.window.decorView
        val rest = draw(activity, "fast-scroller-rest-$out")
        // The handle is what stands out from the page down the last pixels of the end edge (the
        // left one in Arabic).
        fun run(x: Int) = (0 until rest.height).filter { y -> rest.getPixel(x, y) != ground }
        val x = if (out == "rtl") 6 else rest.width - 6
        val rows = run(x)
        assertTrue("nothing on the other edge", run(if (out == "rtl") rest.width - 6 else 6).isEmpty())
        assertTrue("the handle is out after a scroll", rows.size > 40)
        val y = (rows.first() + rows.last()) / 2f
        val start = SystemClock.uptimeMillis()
        root.dispatchTouchEvent(MotionEvent.obtain(start, start, MotionEvent.ACTION_DOWN, x.toFloat(), y, 0))
        idle(300)
        val held = draw(activity, "fast-scroller-held-$out")
        assertTrue("held, it fills with the accent", held.getPixel(x, y.toInt()) == accent)
        val before = state.firstVisibleItemIndex
        for (step in 1..10) {
            root.dispatchTouchEvent(MotionEvent.obtain(start, start + step * 16L, MotionEvent.ACTION_MOVE, x.toFloat(), y + step * 40f, 0))
            idle(50)
        }
        idle(300)
        draw(activity, "fast-scroller-bubble-$out")
        assertTrue("the drag moved the list (${state.firstVisibleItemIndex} from $before)", state.firstVisibleItemIndex > before + 80)
        root.dispatchTouchEvent(MotionEvent.obtain(start, start + 400, MotionEvent.ACTION_UP, x.toFloat(), y + 400f, 0))
        idle(300)
    }

    private fun draw(activity: ComponentActivity, out: String): Bitmap {
        val root = activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val file = File("../build/device-test/review/$out.png").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `phone, dark`() = render("dark")

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-notnight-xhdpi")
    fun `phone, light`() = render("light")

    @Test
    @Config(sdk = [35], qualifiers = "ar-w411dp-h891dp-port-notnight-xhdpi")
    fun `phone, right to left`() = render("rtl")
}
