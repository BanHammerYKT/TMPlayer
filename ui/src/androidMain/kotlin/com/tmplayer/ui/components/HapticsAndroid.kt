package com.tmplayer.ui.components

import android.content.Context
import android.os.Build
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import com.tmplayer.data.FormFactor

/**
 * Plays [Haptic]s on the view the screen is drawn in. Call the result with the moment that just
 * happened; it does nothing on a television, on a device with no vibrator, or with "Vibrate on tap"
 * off, and the system's touch feedback setting still has the last word.
 */
@Composable
fun rememberHaptics(): (Haptic) -> Unit {
    val view = LocalView.current
    return remember(view) { { event: Haptic -> view.haptic(event) } }
}

/**
 * [rememberHaptics] for a screen built of views (the player): plays [event] on this view when
 * [enabled] and the device can. True when something was played.
 */
fun View.haptic(event: Haptic, enabled: Boolean = Micro.haptics): Boolean {
    if (!Haptics.allowed(enabled, FormFactor.isTv(context), HapticDevice.hasVibrator(context))) return false
    // The vibrator's own predefined effects first: a view's haptic feedback was dropped silently on
    // a POCO F5 under HyperOS (nothing from the app in the vibrator's log, 2026-10-22), while the
    // click, tick and heavy click every vibrator implements reach the motor everywhere.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        HapticDevice.play(context, event)?.let { return it }
    }
    val constant = Haptics.constant(event, Build.VERSION.SDK_INT) ?: return false
    // No FLAG_IGNORE_GLOBAL_SETTING: with the system's touch feedback off, nothing plays.
    return performHapticFeedback(constant)
}

/** Whether this device has a vibrator, asked once: hardware does not change under a process. */
internal object HapticDevice {
    @Volatile
    private var cached: Boolean? = null

    fun hasVibrator(context: Context): Boolean = cached ?: ask(context.applicationContext).also { cached = it }

    /**
     * Plays [event] as one of the vibrator's predefined effects, honouring the system's "Touch
     * feedback" switch as a view's feedback does. True when played, false when the setting is off
     * or the event has no effect, null when the vibrator could not be used (then the view's own
     * feedback is tried).
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    fun play(context: Context, event: Haptic): Boolean? = runCatching {
        val app = context.applicationContext
        val touchFeedback = android.provider.Settings.System.getInt(
            app.contentResolver, android.provider.Settings.System.HAPTIC_FEEDBACK_ENABLED, 1,
        ) != 0
        if (!touchFeedback) return@runCatching false
        val effect = Haptics.predefined(event) ?: return@runCatching false
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            app.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return@runCatching null
        val wave = android.os.VibrationEffect.createPredefined(effect)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(wave, android.os.VibrationAttributes.createForUsage(android.os.VibrationAttributes.USAGE_TOUCH))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(
                wave,
                android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build(),
            )
        }
        true
    }.getOrNull()

    private fun ask(context: Context): Boolean = runCatching {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        vibrator?.hasVibrator() == true
    }.getOrDefault(false)
}
