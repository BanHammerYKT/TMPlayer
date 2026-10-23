package com.tmplayer.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.Player
import com.tmplayer.R
import com.tmplayer.data.FormFactor
import com.tmplayer.i18n.L
import com.tmplayer.ui.theme.TMPlayerTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.lang.reflect.Proxy
import java.time.Duration

/**
 * The player pieces added after the seeking work: the speed sheet's fine control on a phone held
 * both ways and on a television, the failure sheet with Send logs, the "Frame saved" answer with
 * its tick and Share, and the fine scrub's hint over the picture and over the bar. Drawn to
 * `build/device-test/review/extras-*.png` for a look.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerExtrasRenderTest {

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    private fun idle(ms: Long = 1000) {
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        var left = ms
        while (left > 0) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            left -= 20
        }
    }

    private fun save(bitmap: Bitmap, name: String) {
        val file = File("../build/device-test/review/$name").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 0)
    }

    private fun shot(activity: ComponentActivity, name: String, dialog: Boolean) {
        val root = activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        root.draw(canvas)
        if (dialog) {
            val window = requireNotNull(ShadowDialog.getLatestDialog()?.window) { "no dialog up" }
            canvas.drawColor(0x99000000.toInt())
            window.decorView.draw(canvas)
        }
        save(bitmap, name)
    }

    // ---- the speed sheet ----------------------------------------------------------------------

    private fun speedSheet(tv: Boolean, name: String, presses: Int = 0, faster: Boolean = true): Float {
        FormFactor.override(tv)
        // Stepped before the sheet opens, as presses would leave it: the figure and the buttons'
        // ends are what the picture is for.
        var start = 1.35f
        repeat(presses) { start = PlaybackSpeed.fine(start, up = faster) }
        var speed by mutableStateOf(start)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            TMPlayerTheme {
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF203040))) {
                    SpeedSheet(
                        current = speed,
                        onPick = { speed = it },
                        onDismiss = {},
                        onClose = {},
                        skipSilence = false,
                        onFine = { speed = it },
                    )
                }
            }
        }
        idle()
        shot(activity, name, dialog = true)
        return speed
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun speedPhoneUpright() {
        speedSheet(tv = false, name = "extras-speed-phone.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w891dp-h411dp-land-night-xhdpi")
    fun speedPhoneSideways() {
        assertEquals(1.45f, speedSheet(tv = false, name = "extras-speed-phone-land.png", presses = 2))
    }

    @Test
    @Config(sdk = [35], qualifiers = "w891dp-h411dp-land-notnight-xhdpi")
    fun speedPhoneAtTheTop() {
        // Up to 4x: plus greys out, minus still works.
        assertEquals(4f, speedSheet(tv = false, name = "extras-speed-phone-max.png", presses = 80))
    }

    @Test
    // Taller than a television, so the whole list is in the picture rather than scrolled.
    @Config(sdk = [35], qualifiers = "w960dp-h1000dp-land-night-xhdpi")
    fun speedTelevision() {
        speedSheet(tv = true, name = "extras-speed-tv.png")
    }

    // ---- the failure sheet --------------------------------------------------------------------

    private fun errorSheet(tv: Boolean, name: String) {
        FormFactor.override(tv)
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        // The player's own theme, as the manifest gives PlayerActivity, so the buttons are its buttons.
        activity.setTheme(R.style.Theme_TMPlayer_Player)
        activity.setContentView(R.layout.activity_player)
        val openWith = activity.findViewById<Button>(R.id.status_open_with)
        val logs = PlayerErrorSheet.addSendLogs(openWith)
        // As showError and showFailureActions leave it for a file that may simply be stale.
        activity.findViewById<View>(R.id.status_overlay).visibility = View.VISIBLE
        activity.findViewById<View>(R.id.status_icon).visibility = View.VISIBLE
        activity.findViewById<View>(R.id.status_spinner)?.visibility = View.GONE
        activity.findViewById<View>(R.id.status_progress).visibility = View.GONE
        activity.findViewById<View>(R.id.status_detail).visibility = View.GONE
        activity.findViewById<TextView>(R.id.status_name).apply {
            text = "Night Train S01E04"
            visibility = View.VISIBLE
        }
        activity.findViewById<TextView>(R.id.status_title).text = L.playerCantPlay
        activity.findViewById<TextView>(R.id.status_text).text =
            "The video stopped coming in. Reload fetches it again from the start."
        activity.findViewById<Button>(R.id.status_reload).apply {
            text = L.commonReload
            visibility = View.VISIBLE
            // What markPrimaryAction gives the suggested way out.
            setBackgroundResource(R.drawable.bg_control_pill_primary)
            setTextColor(context.getColorStateList(R.color.player_primary_text))
        }
        activity.findViewById<Button>(R.id.status_back).apply {
            text = L.playerGoBack
            visibility = View.VISIBLE
        }
        if (tv) {
            // A television's worst case: every way out at once, which the old stack ran off the
            // bottom of the screen.
            activity.findViewById<Button>(R.id.status_decoder).apply {
                text = L.playerDecoderOther
                visibility = View.VISIBLE
            }
            openWith.apply {
                text = L.playerOpenWith
                visibility = View.VISIBLE
            }
        }
        logs?.visibility = View.VISIBLE
        idle()
        assertTrue("Send logs is in the sheet", logs != null && logs.isShown)
        val screen = activity.findViewById<View>(R.id.status_overlay)
        val back = activity.findViewById<View>(R.id.status_back)
        val at = IntArray(2).also(back::getLocationInWindow)
        assertTrue("Go back is on the screen", at[1] + back.height <= screen.height)
        val sheet = activity.findViewById<StatusSheet>(R.id.status_sheet)
        val expected = if (tv) StatusSheetShape.Shape.Tv else StatusSheetShape.Shape.Stacked
        assertEquals("the sheet's shape", expected, sheet.shape)
        assertWordsWhole(activity.findViewById(R.id.status_title), listOfNotNull<TextView>(activity.findViewById(R.id.status_reload), logs as? TextView, activity.findViewById(R.id.status_back)))
        shot(activity, name, dialog = false)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun errorSheetPhone() = errorSheet(tv = false, name = "extras-error-phone.png")

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-night-xhdpi")
    fun errorSheetTelevision() = errorSheet(tv = true, name = "extras-error-tv.png")

    /** The title is not cut short, and no button label wraps inside its pill. */
    private fun assertWordsWhole(title: TextView, buttons: List<TextView>) {
        val titleLayout = title.layout
        for (line in 0 until titleLayout.lineCount) {
            assertEquals("\"${title.text}\" is not cut short", 0, titleLayout.getEllipsisCount(line))
        }
        for (button in buttons) {
            assertEquals("\"${button.text}\" is on one line", 1, button.lineCount)
        }
    }

    /**
     * The sheet that says the video stopped arriving, in the shape the player gives it: a handset
     * held sideways puts the poster beside the words and the three buttons in a row under them,
     * which it needs to show all of them at once; held upright it stacks with a smaller poster and
     * centres the row. A television keeps the stack. [askedSideways] is the player having asked for
     * landscape, which is what keeps the phone that reports a portrait window side by side.
     */
    private fun stallSheet(
        tv: Boolean,
        name: String,
        expected: StatusSheetShape.Shape,
        askedSideways: Boolean = false,
    ) {
        FormFactor.override(tv)
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        activity.setTheme(R.style.Theme_TMPlayer_Player)
        activity.setContentView(R.layout.activity_player)
        val overlay = activity.findViewById<View>(R.id.status_overlay)
        overlay.visibility = View.VISIBLE
        activity.findViewById<View>(R.id.status_spinner)?.visibility = View.GONE
        activity.findViewById<View>(R.id.status_progress).visibility = View.GONE
        activity.findViewById<ImageView>(R.id.status_poster).apply {
            setImageDrawable(android.graphics.drawable.ColorDrawable(0xFFB5462B.toInt()))
            visibility = View.VISIBLE
        }
        activity.findViewById<TextView>(R.id.status_name).apply {
            text = "Harbour.Lights.2026.1080p.10bit.6CH.HEVC.mkv"
            visibility = View.VISIBLE
        }
        activity.findViewById<TextView>(R.id.status_title).text = L.playerStalledMidTitle
        activity.findViewById<TextView>(R.id.status_meta).apply {
            text = "2.0 GB"
            visibility = View.VISIBLE
        }
        activity.findViewById<TextView>(R.id.status_text).text = L.playerStalledMidSlow
        activity.findViewById<TextView>(R.id.status_detail).apply {
            text = L.playerStalledDownloadHint
            visibility = View.VISIBLE
        }
        val reload = activity.findViewById<Button>(R.id.status_reload).apply {
            text = L.commonTryAgain
            visibility = View.VISIBLE
            setBackgroundResource(R.drawable.bg_control_pill_primary)
            setTextColor(context.getColorStateList(R.color.player_primary_text))
        }
        val back = activity.findViewById<Button>(R.id.status_back).apply {
            text = L.playerGoBack
            visibility = View.VISIBLE
        }
        val download = activity.findViewById<Button>(R.id.status_open_with).apply {
            text = L.playerDownloadFirst
            visibility = View.VISIBLE
        }
        activity.findViewById<StatusSheet>(R.id.status_sheet).askedSideways = askedSideways
        idle()
        assertEquals("the sheet's shape", expected, activity.findViewById<StatusSheet>(R.id.status_sheet).shape)
        val screen = Rect().also { overlay.getGlobalVisibleRect(it) }
        for (button in listOf(reload, download, back)) {
            val box = Rect().also { button.getGlobalVisibleRect(it) }
            assertTrue("${button.text} is on the screen whole", button.isShown && screen.contains(box) && box.height() == button.height)
        }
        // Asked sideways on a portrait test screen is that phone's lie drawn literally: only the
        // shape is the point there, not how the buttons fit a width the real screen never has.
        if (expected == StatusSheetShape.Shape.SideBySide && !askedSideways) {
            assertEquals("the buttons share a line beside the poster", reload.top, back.top)
            assertEquals("the buttons share a line beside the poster", reload.top, download.top)
        }
        if (expected == StatusSheetShape.Shape.Stacked) {
            assertWordsWhole(activity.findViewById(R.id.status_title), listOf(reload, download, back))
        }
        shot(activity, name, dialog = false)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w873dp-h393dp-land-night-xxhdpi")
    fun stallSheetPhoneSideways() =
        stallSheet(tv = false, name = "extras-stall-phone-sideways.png", expected = StatusSheetShape.Shape.SideBySide)

    /** The phone that reports a portrait window on a landscape panel, with the player asking for landscape. */
    @Test
    @Config(sdk = [35], qualifiers = "w393dp-h873dp-port-night-xxhdpi")
    fun stallSheetPhoneAskedSideways() = stallSheet(
        tv = false,
        name = "extras-stall-phone-asked-sideways.png",
        expected = StatusSheetShape.Shape.SideBySide,
        askedSideways = true,
    )

    @Test
    @Config(sdk = [35], qualifiers = "w393dp-h873dp-port-night-xxhdpi")
    fun stallSheetPhoneUpright() =
        stallSheet(tv = false, name = "extras-stall-phone-upright.png", expected = StatusSheetShape.Shape.Stacked)

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-night-xhdpi")
    fun stallSheetTelevision() = stallSheet(tv = true, name = "extras-stall-tv.png", expected = StatusSheetShape.Shape.Tv)

    // ---- the frame saved answer and the fine scrub ---------------------------------------------

    private val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
        when (method.name) {
            "getDuration" -> 2_634_000L
            "getCurrentPosition" -> 1_260_000L
            "getBufferedPosition" -> 1_700_000L
            "isPlaying", "getPlayWhenReady" -> true
            else -> when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                else -> null
            }
        }
    } as Player

    private fun picture(build: (FragmentActivity, FrameLayout) -> Unit): FragmentActivity {
        FormFactor.override(false)
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val root = FrameLayout(activity).apply {
            id = R.id.player_root
            setBackgroundColor(Color.rgb(0x20, 0x30, 0x40))
        }
        activity.setContentView(root)
        build(activity, root)
        idle(600)
        return activity
    }

    private fun drawWindow(activity: FragmentActivity, name: String) {
        val decor = activity.window.decorView
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bitmap))
        save(bitmap, name)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w891dp-h411dp-land-night-xhdpi")
    fun frameSavedAndSwipeScrub() {
        var feedback: PlayerFeedback? = null
        val activity = picture { _, root ->
            feedback = PlayerFeedback(root, null).also {
                it.offer(L.playerFrameSaved, L.commonShare, tick = true) {}
            }
        }
        drawWindow(activity, "extras-frame-saved.png")
        feedback?.clear()
        feedback?.scrub(deltaMs = 42_000, targetMs = 1_302_000, durationMs = 2_634_000, rate = 0.5f)
        idle(600)
        drawWindow(activity, "extras-swipe-half.png")
    }

    // ---- the row: picture shape on the bar ------------------------------------------------------

    /**
     * The row as a video in a series with a second copy leaves it: every button the row can carry,
     * so a crowded row shows its worst. [rtl] lays the window out right to left (the row itself
     * stays left to right, as media controls do).
     */
    private fun row(tv: Boolean, name: String, rtl: Boolean = false, focus: Int? = null, shape: VideoScale = VideoScale.Fit): PlayerControls {
        FormFactor.override(tv)
        var controls: PlayerControls? = null
        val activity = picture { activity, root ->
            FormFactor.override(tv)
            if (rtl) activity.window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
            LayoutInflater.from(activity).inflate(R.layout.player_controls, root, true)
            controls = PlayerControls(
                root = root,
                isTv = tv,
                player = { player },
                onVisibility = {},
                onTogglePlay = {},
                onPickSubtitles = {},
                onPickAudio = {},
                onPickSpeed = {},
                onCycleOrientation = {},
                onPlayEpisode = {},
            ).also {
                it.setTitle("Night Train", "S01E04  ·  The Lighthouse")
                it.setEpisodesAvailable(true)
                it.setCopy("1080p")
                it.setShape(shape)
                it.show()
            }
        }
        idle(600)
        // From touch, which takes the window out of touch mode, so the focus circle is drawn as on a remote.
        focus?.let { assertTrue(activity.findViewById<View>(it).requestFocusFromTouch()) }
        idle(300)
        drawWindow(activity, name)
        return requireNotNull(controls)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w891dp-h411dp-land-night-xhdpi")
    fun rowPhoneSideways() {
        val controls = row(tv = false, name = "extras-row-phone-land.png")
        assertTrue("picture shape on the row", controls.shapeOnRow)
        assertTrue("the lock on the row", controls.lockOnRow)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w891dp-h411dp-land-night-xhdpi")
    fun rowPhoneSidewaysRtl() {
        row(tv = false, name = "extras-row-phone-land-rtl.png", rtl = true, shape = VideoScale.Crop)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun rowPhoneUpright() {
        val controls = row(tv = false, name = "extras-row-phone-port.png")
        // Upright the row has no room: both stay in the overflow, which then offers picture shape.
        assertTrue(!controls.shapeOnRow && !controls.lockOnRow)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-night-xhdpi")
    fun rowTelevisionOnShape() {
        val controls = row(tv = true, name = "extras-row-tv-shape.png", focus = R.id.control_shape, shape = VideoScale.Stretch)
        assertTrue(controls.shapeOnRow)
        assertTrue("the television keeps its lock in More", !controls.lockOnRow)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w891dp-h411dp-land-night-xhdpi")
    fun barScrubHint() {
        var controls: PlayerControls? = null
        val activity = picture { activity, root ->
            LayoutInflater.from(activity).inflate(R.layout.player_controls, root, true)
            controls = PlayerControls(
                root = root,
                isTv = false,
                player = { player },
                onVisibility = {},
                onTogglePlay = {},
                onPickSubtitles = {},
                onPickAudio = {},
                onPickSpeed = {},
                onCycleOrientation = {},
                onPlayEpisode = {},
            ).also { it.show() }
        }
        controls?.showScrubRate(0.25f)
        idle(600)
        drawWindow(activity, "extras-bar-quarter.png")
        controls?.showScrubRate(1f)
        idle(600)
        drawWindow(activity, "extras-bar-finer.png")
    }
}
