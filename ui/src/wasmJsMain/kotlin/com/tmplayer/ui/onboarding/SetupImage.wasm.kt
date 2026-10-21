package com.tmplayer.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// The setup and the tour show no pictures in the browser yet: they are the desktop's window,
// which a browser tab is not. The answers and pages read as text, as on a television.

@Composable
internal fun SetupShot(choice: SetupChoice, modifier: Modifier = Modifier) {}

fun setupImageName(choice: SetupChoice, dark: Boolean): String? = null

@Composable
internal fun TourShot(page: TourPage, modifier: Modifier = Modifier) {}

@Composable
internal fun tourShotSize(page: TourPage): androidx.compose.ui.geometry.Size? = null

fun tourImageName(page: TourPage, dark: Boolean): String? = null
