package com.tmplayer.ui.theme

import android.animation.ValueAnimator
import android.content.Context
import android.database.ContentObserver
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

private object SystemMotion {
    val reduced = mutableStateOf(!ValueAnimator.areAnimatorsEnabled())

    @Volatile
    private var watching = false

    fun watch(context: Context) {
        if (watching) return
        synchronized(this) {
            if (watching) return
            watching = true
        }
        val app = context.applicationContext
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduced.value = !ValueAnimator.areAnimatorsEnabled() ||
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
