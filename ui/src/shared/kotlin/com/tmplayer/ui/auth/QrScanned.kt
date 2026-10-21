package com.tmplayer.ui.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tmplayer.data.AuthState
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import kotlinx.coroutines.delay

/**
 * The sign in state to draw, which is [auth] except for a moment after a QR code is scanned.
 *
 * TDLib has no "scanned" state: the phone's confirmation moves it straight from
 * `authorizationStateWaitOtherDeviceConfirmation` ([AuthState.Qr]) to Ready, or to the password
 * for an account with two-step verification. Without a pause the code vanishes into a loader and
 * the viewer never learns the scan worked, so that move is held for [SCANNED_HOLD_MS] on the same
 * code marked [AuthState.Qr.scanned]. Any other way out of the QR pane (the phone number chosen,
 * a restart) goes through at once.
 */
@Composable
fun rememberScanHold(auth: AuthState): AuthState {
    // Plain fields, written while composing: the decision has to be in the first frame after
    // the scan, and only the hold's end ([ended]) is state, set later by the timer.
    val memo = remember { ScanMemo() }
    var ended by remember { mutableStateOf(0) }
    val scanned = auth is AuthState.Ready || auth is AuthState.Password
    when {
        auth is AuthState.Qr -> {
            memo.lastLink = auth.link
            memo.held = null
        }
        scanned && memo.lastLink != null -> {
            memo.held = AuthState.Qr(memo.lastLink!!, scanned = true)
            memo.holdId++
            memo.lastLink = null
        }
        !scanned -> {
            memo.lastLink = null
            memo.held = null
        }
    }
    val id = memo.holdId
    val hold = memo.held?.takeIf { ended != id }
    LaunchedEffect(id) {
        if (memo.held == null) return@LaunchedEffect
        delay(SCANNED_HOLD_MS)
        ended = id
    }
    return hold ?: auth
}

private class ScanMemo {
    var lastLink: String? = null
    var held: AuthState.Qr? = null
    var holdId = 0
}

/**
 * Drawn over a QR plate once its code was scanned: the code fades under a white veil and
 * "Scanned" with a tick says the phone got it. Fills whatever plate it is put in.
 */
@Composable
fun QrScannedVeil(scanned: Boolean, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    AnimatedVisibility(
        scanned,
        modifier = modifier,
        enter = fadeIn(tween(VEIL_MS)),
        exit = fadeOut(tween(VEIL_MS)),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.White.copy(alpha = 0.88f))
                .semantics { liveRegion = LiveRegionMode.Polite },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier
                        .animateEnterExit(enter = scaleIn(tween(VEIL_MS), initialScale = 0.6f))
                        .size(56.dp)
                        .background(SCANNED_GREEN, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(TmIcons.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
                }
                // Dark ink on the veil whatever the theme: the plate under it is always white.
                Text(s.loginQrScanned, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = Color(0xFF111111))
            }
        }
    }
}

/** Long enough to read "Scanned", short enough not to hold up the sign in. */
const val SCANNED_HOLD_MS = 1_400L

private const val VEIL_MS = 220
private val SCANNED_GREEN = Color(0xFF1E9E5A)
