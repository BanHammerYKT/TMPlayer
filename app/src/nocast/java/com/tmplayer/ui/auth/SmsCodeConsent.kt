package com.tmplayer.ui.auth

import androidx.compose.runtime.Composable

/**
 * The F-Droid build has no Play Services, so no SMS User Consent: the code is typed, pasted or
 * filled by the keyboard's own suggestion, which the code field asks for by its autofill type.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun SmsCodeConsent(active: Boolean, onMessage: (String) -> Unit) = Unit
