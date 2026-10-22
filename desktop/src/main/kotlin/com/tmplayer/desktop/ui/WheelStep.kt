package com.tmplayer.desktop.ui

/**
 * Pixels a strip moves for a wheel or trackpad [delta] as the pointer event carries it. On the
 * desktop that is a count of wheel steps, each [WHEEL_STEP_PX]; the browser build has its own
 * (DesktopUiWeb.kt), where the delta is the page's wheel event in pixels or lines.
 */
@Suppress("UNUSED_PARAMETER")
internal fun wheelPixels(delta: Float, density: Float): Float = delta * WHEEL_STEP_PX

/** Pixels a strip moves per step of the wheel or the trackpad's sideways delta. */
private const val WHEEL_STEP_PX = 64f
