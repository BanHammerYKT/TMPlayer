package com.tmplayer.ui.components

import androidx.compose.runtime.Composable

/**
 * On the desktop nothing is played: a mouse and a keyboard have no motor, and Compose for the
 * desktop has no haptics API. A gamepad's rumble would be the one exception, left for later.
 */
@Composable
fun rememberHaptics(): (Haptic) -> Unit = NoHaptics

private val NoHaptics: (Haptic) -> Unit = {}
