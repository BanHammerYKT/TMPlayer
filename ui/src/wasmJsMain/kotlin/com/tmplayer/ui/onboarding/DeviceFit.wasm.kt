package com.tmplayer.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.tmplayer.data.DeviceFit
import com.tmplayer.data.DeviceFitOverride
import com.tmplayer.data.DeviceFitRules
import com.tmplayer.data.DeviceProbe
import com.tmplayer.data.DiskInfo
import java.io.File

/**
 * The desktop reads free disk only: a computer's memory is not this app's to judge, and the
 * player's buffers and pictures are already sized for the window. The volume measured is the home
 * folder's, where the app keeps its data unless told otherwise.
 */
object DeviceProbes {
    // The browser keeps its own storage quota; the page asks for it (navigator.storage.estimate).
    fun read(): DeviceProbe = DeviceProbe(freeStorageBytes = null)

    fun fit(): DeviceFit = DeviceFitOverride.forced ?: DeviceFitRules.assess(read())
}

@Composable
fun rememberDeviceFit(): DeviceFit = remember { DeviceProbes.fit() }

/** The desktop has no Thumbnails while seeking setting. */
@Composable
fun rememberSeekThumbnailsShown(): Boolean = false

/** Nor the Lighter pictures and Reduce motion rows: only Low storage mode is offered there. */
val hasPictureSettings: Boolean = false

/** A computer has no wallpaper palette for the app to take, so the appearance step never asks. */
internal val wallpaperColoursOffered: Boolean get() = false
