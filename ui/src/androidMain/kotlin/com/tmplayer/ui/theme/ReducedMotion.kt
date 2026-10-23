package com.tmplayer.ui.theme

import android.animation.ValueAnimator
import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext

/**
 * True when the system has its animations turned off: the accessibility switch, or the
 * developer options' animator scale at zero, which is the same setting underneath. Nothing then
 * scales, slides, parallaxes or advances on its own; see [Motion].
 *
 * Watched rather than read once, so turning animations off in Settings and coming back takes
 * effect without a restart. One watcher for the whole app: every tile asks, and a watcher of its
 * own per tile was two calls to the system each time a tile scrolled in and out, which a TV stick
 * felt on every row.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    SystemMotion.watch(LocalContext.current)
    return SystemMotion.reduced.value
}

/**
 * Whether the system lets animations run. Android 8 answers in one call; on 6 and 7 the developer
 * options' animator duration scale at zero, the same switch underneath, is the only way to tell.
 * Either is cheap enough to ask per animation: the settings read is cached in the process.
 */
fun systemAnimatorsEnabled(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        ValueAnimator.areAnimatorsEnabled()
    } else {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }

private object SystemMotion {
    // Android 6 and 7 need a context for the answer, so there it is read as the watch starts,
    // before anything has read this state.
    val reduced = mutableStateOf(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ValueAnimator.areAnimatorsEnabled())

    @Volatile
    private var watching = false

    fun watch(context: Context) {
        if (watching) return
        synchronized(this) {
            if (watching) return
            watching = true
        }
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) reduced.value = !systemAnimatorsEnabled(app)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduced.value = !systemAnimatorsEnabled(app) ||
                    Settings.Global.getFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
            }
        }
        app.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
    }
}
