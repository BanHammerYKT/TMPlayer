package com.tmplayer.ui.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import com.tmplayer.ui.theme.collectIsFocusVisibleAsState
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Stable
import com.tmplayer.data.AuthState
import com.tmplayer.data.QrClock
import com.tmplayer.data.QrPhase
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

/**
 * True once [link] has been on screen for [QR_STALE_MS] without a new one. TDLib renews the code
 * by itself before it runs out (a login token lasts about 30 seconds), so a code that has not
 * changed in that time is one TDLib could not renew, most often for want of a line: it no longer
 * signs anybody in and must not be left looking usable.
 */
@Composable
fun rememberQrExpired(link: String): Boolean = rememberQrClock(link).phase == QrPhase.Expired

/** Where the code on screen is in its life ([QrClock]), ticking a few times a second. */
@Stable
class QrLife(val phase: QrPhase, val remaining: Float)

/**
 * The clock of the code [link] on screen. The time between the last two links is the lifetime
 * TDLib renews at, so it is remembered across links; each new link starts the count again.
 */
@Composable
fun rememberQrClock(link: String): QrLife {
    val memo = remember { QrArrivals() }
    val arrived = remember(link) {
        val now = System.currentTimeMillis()
        memo.lastAt?.let { memo.gap = now - it }
        memo.lastAt = now
        now
    }
    val lifetime = QrClock.lifetime(memo.gap)
    var age by remember(link) { mutableStateOf(0L) }
    LaunchedEffect(link) {
        while (age < QrClock.STALE_MS) {
            delay(QR_TICK_MS)
            age = System.currentTimeMillis() - arrived
        }
    }
    return QrLife(QrClock.phase(age, lifetime), QrClock.remaining(age, lifetime))
}

private class QrArrivals {
    var lastAt: Long? = null
    var gap: Long? = null
}

/**
 * The "expires soon" cue under a QR plate: a thin bar that drains over the code's life, and in
 * its last seconds a line saying a new code is on its way, so a code that changes under the
 * camera is expected rather than a surprise. Its width is the plate's ([modifier]).
 */
@Composable
fun QrLifeCue(life: QrLife, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val bar by androidx.compose.animation.core.animateFloatAsState(
        life.remaining,
        animationSpec = tween(QR_TICK_MS.toInt(), easing = androidx.compose.animation.core.LinearEasing),
        label = "qrLife",
    )
    val soon = life.phase == QrPhase.ExpiringSoon || life.phase == QrPhase.Renewing
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(CircleShape)
                .background(com.tmplayer.ui.theme.Tone.outline),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(if (life.phase == QrPhase.Renewing) 1f else bar)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(if (soon) com.tmplayer.ui.theme.Tone.muted else com.tmplayer.ui.theme.Tone.accent),
            )
        }
        // Always laid out, so the pane does not jump when the line appears.
        Text(
            when (life.phase) {
                QrPhase.ExpiringSoon -> s.loginQrExpiresSoon
                QrPhase.Renewing, QrPhase.Expired -> s.loginQrRenewing
                QrPhase.Fresh -> ""
            },
            style = MaterialTheme.typography.bodySmall,
            color = com.tmplayer.ui.theme.Tone.muted,
            maxLines = 1,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

private const val QR_TICK_MS = 500L

/**
 * Over a code that has run out ([rememberQrExpired]): the code fades under the same white veil as
 * [QrScannedVeil], with "This code has expired" and a button for a new one ([onRefresh]), which has
 * the focus on a television so one press of the remote does it.
 */
@Composable
fun QrExpiredVeil(expired: Boolean, onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    AnimatedVisibility(expired, modifier = modifier, enter = fadeIn(tween(VEIL_MS)), exit = fadeOut(tween(VEIL_MS))) {
        val focus = remember { androidx.compose.ui.focus.FocusRequester() }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.White.copy(alpha = 0.9f))
                .semantics { liveRegion = LiveRegionMode.Polite },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(s.loginQrExpired, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color(0xFF111111))
                QrRefreshButton(onRefresh, Modifier.focusRequester(focus), onWhite = true)
            }
        }
        if (!com.tmplayer.ui.components.isTouch()) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }
}

/**
 * "Get a new code": a small pill that spins while the new code is asked for, so the press shows
 * even before TDLib answers. [onWhite] for the veil over the plate, else the theme's colours.
 */
@Composable
fun QrRefreshButton(onRefresh: () -> Unit, modifier: Modifier = Modifier, onWhite: Boolean = false) {
    val s = LocalStrings.current
    val interactions = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val focused by interactions.collectIsFocusVisibleAsState()
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(busy) {
        if (busy) {
            delay(REFRESH_SPIN_MS)
            busy = false
        }
    }
    val fill = when {
        focused -> com.tmplayer.ui.theme.Tone.focusFill
        onWhite -> Color(0xFF111111)
        else -> com.tmplayer.ui.theme.Tone.surfaceHigh
    }
    val ink = when {
        focused -> com.tmplayer.ui.theme.Tone.onFocusFill
        onWhite -> Color.White
        else -> com.tmplayer.ui.theme.Tone.text
    }
    androidx.compose.foundation.layout.Row(
        modifier
            .height(44.dp)
            .clip(CircleShape)
            .background(fill)
            .clickable(interactionSource = interactions, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button) {
                if (!busy) {
                    busy = true
                    onRefresh()
                }
            }
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (busy) {
            androidx.compose.material3.CircularProgressIndicator(color = ink, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        } else {
            Icon(TmIcons.Refresh, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
        }
        Text(s.loginQrRefresh, style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 1)
    }
}

/** A login token lasts about 30 seconds and TDLib renews it before then; past this it could not. */
const val QR_STALE_MS = QrClock.STALE_MS

private const val REFRESH_SPIN_MS = 4_000L

/** Long enough to read "Scanned", short enough not to hold up the sign in. */
const val SCANNED_HOLD_MS = 1_400L

private const val VEIL_MS = 220
private val SCANNED_GREEN = Color(0xFF1E9E5A)
