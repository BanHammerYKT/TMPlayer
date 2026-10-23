package com.tmplayer.web.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmplayer.data.AppLockSession
import com.tmplayer.data.AuthState
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.SupportReminder
import com.tmplayer.data.Td
import com.tmplayer.data.TdTabs
import com.tmplayer.desktop.os.OpenExternal
import com.tmplayer.desktop.ui.CoffeeButton
import com.tmplayer.desktop.ui.CoffeeDialog
import com.tmplayer.desktop.ui.NavBarClearance
import com.tmplayer.desktop.ui.ShellState
import com.tmplayer.ui.about.About
import com.tmplayer.ui.about.BmcBrand
import com.tmplayer.ui.about.BmcCupMark
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.onboarding.Setup
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import com.tmplayer.web.js.TmHost
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The browser build's offers, over the app's own window (Main.kt): the Buy Me a Coffee button, and
 * the one time suggestion to install, either this web app (Chromium's prompt, or the Home Screen
 * steps on an iPhone or iPad) or the TMPlayer app for the platform the page runs on. The coffee
 * window itself is the apps' own (SupportAskHost in the shared shell, with [SupportReminder]'s
 * rules and timing), so the web and the apps ask alike.
 *
 * The rules (user, 2026-10-22), all of them offers, nothing locked:
 *
 * - **One at a time.** The install suggestion and the coffee window never share a run of the page:
 *   showing the suggestion counts as this run's ask ([SupportReminder.sessionShown]), and a run
 *   that already had the coffee window gets no suggestion. The coffee button steps aside while
 *   the suggestion is up. The suggestion comes first in practice: it is offered from the first
 *   visit, best before the sign in on an iPhone (the Home Screen app keeps its own storage, so
 *   signing in first in Safari means signing in twice), while the coffee window waits for a day
 *   of real use at the least.
 * - **Never over a video**, the setup, the quick tour, the PIN pad, the search, a detail sheet or
 *   the profile menu. The coffee button stays clear of the floating bar and a chat's bar (above
 *   them on a narrow window, beside the bar on a wide one) and is not drawn while signed out.
 * - **Remembered.** The coffee button's x hides it for this tab's session; Settings, About turns it
 *   off for good, and so does "I already support" or "Don't show again" on the coffee window.
 *   The suggestion's "Not now" is kept, and so is an answer to it; one left unanswered through
 *   [MAX_UNANSWERED] runs stops being offered. Storage the browser blocks (a private window)
 *   falls back to memory in web-host.js: the page still works, it simply forgets on close.
 */
object WebOffers {
    private const val INSTALL_KEY = "tm.offer.install"
    private const val INSTALL_SEEN_KEY = "tm.offer.installSeen"
    private const val COFFEE_OFF_KEY = "tm.offer.coffeeOff"
    private const val COFFEE_SESSION_KEY = "tm.offer.coffeeHidden"

    /** How many runs an unanswered suggestion is offered in. */
    const val MAX_UNANSWERED = 3

    /** The development server's `?tm-offer=` value (web-host.js offerDebug), empty elsewhere. */
    val debug: String by lazy { runCatching { TmHost.offerDebug() }.getOrDefault("") }

    private val coffeeOn = MutableStateFlow(runCatching { TmHost.getItem(COFFEE_OFF_KEY) }.getOrNull() != "1")

    /** Settings' switch for the coffee button. */
    val coffeeButtonOn: StateFlow<Boolean> get() = coffeeOn

    fun setCoffeeButton(on: Boolean) {
        coffeeOn.value = on
        runCatching { if (on) TmHost.removeItem(COFFEE_OFF_KEY) else TmHost.setItem(COFFEE_OFF_KEY, "1") }
    }

    /** The x on the coffee button: hidden until this tab's session ends. */
    var coffeeHiddenThisSession: Boolean
        get() = runCatching { TmHost.sessionGet(COFFEE_SESSION_KEY) }.getOrNull() == "1"
        set(value) {
            runCatching { TmHost.sessionSet(COFFEE_SESSION_KEY, if (value) "1" else "0") }
        }

    /** The suggestion was answered, or offered unanswered often enough. */
    fun installSettled(): Boolean {
        if (runCatching { TmHost.getItem(INSTALL_KEY) }.getOrNull() != null) return true
        val seen = runCatching { TmHost.getItem(INSTALL_SEEN_KEY) }.getOrNull()?.toIntOrNull() ?: 0
        return seen >= MAX_UNANSWERED
    }

    fun answerInstall(answer: String) {
        runCatching { TmHost.setItem(INSTALL_KEY, answer) }
    }

    /** Counts a run the suggestion appeared in, once per run. */
    fun noteInstallShown() {
        if (shownThisRun) return
        shownThisRun = true
        val seen = runCatching { TmHost.getItem(INSTALL_SEEN_KEY) }.getOrNull()?.toIntOrNull() ?: 0
        runCatching { TmHost.setItem(INSTALL_SEEN_KEY, (seen + 1).toString()) }
    }

    private var shownThisRun = false

    /** The platform the page runs on, as the suggestion names it. */
    val platform: AppPlatform by lazy {
        val forced = debug.substringAfter("install-app:", "").takeIf { it.isNotEmpty() }
        AppPlatform.of(forced ?: runCatching { TmHost.appPlatform() }.getOrDefault("other"))
    }

    /** The download page at the platform's own section, tagged as the apps tag their links. */
    fun downloadUrl(p: AppPlatform): String =
        "https://tmplayer.org/download/?utm_source=webapp&utm_medium=app" + (p.anchor?.let { "#$it" } ?: "")
}

/** A platform TMPlayer has an app for, or [Ios] (the web app is the app there) and [Other]. */
enum class AppPlatform(val title: String?, val anchor: String?) {
    Ios(null, null),
    Android("Android", "android"), // i18n-ok: platform name
    AndroidTv("Android TV", "android-tv"), // i18n-ok: platform name
    Windows("Windows", "windows"), // i18n-ok: platform name
    Mac("macOS", "macos"), // i18n-ok: platform name
    Linux("Linux", "linux"), // i18n-ok: platform name
    Other(null, null),
    ;

    val hasApp: Boolean get() = title != null

    companion object {
        fun of(name: String): AppPlatform = when (name) {
            "ios" -> Ios
            "android" -> Android
            "androidtv" -> AndroidTv
            "windows" -> Windows
            "macos" -> Mac
            "linux" -> Linux
            else -> Other
        }
    }
}

/** What the suggestion offers on this page. */
sealed interface InstallOffer {
    /** An iPhone or iPad in a browser tab: Share, then Add to Home Screen. */
    data object HomeScreen : InstallOffer

    /** Chromium's own install prompt, where TMPlayer has no app for the platform. */
    data object WebApp : InstallOffer

    /** The app for [platform], and the web app's install as the second choice when [alsoWeb]. */
    data class NativeApp(val platform: AppPlatform, val alsoWeb: Boolean) : InstallOffer

    companion object {
        /**
         * The suggestion for [platform], or null when there is none: an installed web app on an
         * iPhone is already the app, and a browser that offers no install and runs on a platform
         * with no app has nothing to suggest. A platform with an app gets the app first, since it
         * plays more kinds of video and downloads them, even inside an installed web app.
         */
        fun of(platform: AppPlatform, standalone: Boolean, canInstall: Boolean): InstallOffer? = when {
            platform == AppPlatform.Ios -> if (standalone) null else HomeScreen
            platform.hasApp -> NativeApp(platform, alsoWeb = canInstall && !standalone)
            canInstall && !standalone -> WebApp
            else -> null
        }
    }
}

/**
 * The offers over the window. [shell] says what is on screen; nothing here is drawn over a video,
 * the setup or anything modal of the app's own.
 */
@Composable
fun WebOffersHost(shell: ShellState) {
    val debug = WebOffers.debug
    val settings = shell.settings
    val auth by Td.auth.collectAsState()
    val elsewhere by TdTabs.elsewhere.collectAsState()
    val signedIn = auth == AuthState.Ready
    val signInScreen = !signedIn && Setup.isSignInStep(auth)
    val setupPending by settings.setupPending.collectAsState(initial = true)
    val tour by settings.tourState.collectAsState(initial = SettingsStore.TourState.Done)
    val appLock by settings.appLock.collectAsState(initial = null)
    val unlocked by AppLockSession.unlocked.collectAsState()
    val locked = appLock?.onOpen == true && !unlocked
    val counters by settings.supportCounters.collectAsState(initial = null)
    val online by produceState(runCatching { TmHost.online() }.getOrDefault(true)) { TmHost.onOnline { value = it } }
    val coffeeOn by WebOffers.coffeeButtonOn.collectAsState()
    var coffeeHidden by remember { mutableStateOf(WebOffers.coffeeHiddenThisSession) }

    // Anything of the app's own that the offers must not sit over or beside.
    val busy = shell.nowPlaying != null || shell.fullscreen || shell.paletteOpen || shell.detail != null ||
        shell.profileOpen || shell.updatePopup
    val shellQuiet = signedIn && !setupPending && tour != SettingsStore.TourState.Pending && !locked && !busy

    // ---- the install suggestion -------------------------------------------------------------------
    val forcedInstall = debug.startsWith("install")
    val standalone = remember { runCatching { TmHost.isStandalone() }.getOrDefault(false) }
    val canInstall by produceState(runCatching { TmHost.canInstall() }.getOrDefault(false)) {
        // Chromium says the page can be installed some seconds after it loads, and only once.
        while (!value) {
            delay(3_000)
            value = runCatching { TmHost.canInstall() }.getOrDefault(false)
        }
    }
    val offer = when (debug) {
        "install-ios" -> InstallOffer.HomeScreen
        "install-pwa" -> InstallOffer.WebApp
        else -> InstallOffer.of(WebOffers.platform, standalone, canInstall || debug.startsWith("install-app"))
    }
    var installOpen by remember { mutableStateOf(false) }
    // Another forced offer keeps this one out of its screenshot.
    var installSettled by remember {
        mutableStateOf(if (forcedInstall) false else debug.isNotEmpty() || WebOffers.installSettled())
    }
    // The app's link needs the network; the Home Screen steps and Chromium's prompt do not.
    val offerReady = offer != null && (online || offer !is InstallOffer.NativeApp)
    val installMoment = !elsewhere && (signInScreen || shellQuiet) && offerReady && !installSettled
    LaunchedEffect(installMoment) {
        if (!installMoment || installOpen) return@LaunchedEffect
        // A run that already had the coffee window waits for the next one.
        if (SupportReminder.sessionShown && !forcedInstall) return@LaunchedEffect
        // Time to read the screen first; a forced one comes at once, for screenshots.
        delay(if (forcedInstall) 800L else INSTALL_DELAY_MS)
        installOpen = true
        WebOffers.noteInstallShown()
        // The one ask of this run: the coffee window waits for another.
        SupportReminder.sessionShown = true
    }
    // An install finished from the browser's own menu takes the suggestion away.
    LaunchedEffect(installOpen) {
        while (installOpen) {
            if (runCatching { TmHost.installedHere() }.getOrDefault(false)) {
                WebOffers.answerInstall("installed")
                installSettled = true
                installOpen = false
            }
            delay(2_000)
        }
    }
    val installShown = installOpen && !installSettled && !elsewhere && (signInScreen || shellQuiet) && offer != null

    // ---- the coffee button -----------------------------------------------------------------------
    val forcedCoffee = debug == "coffee"
    val coffeeShown = forcedCoffee || (
        SupportReminder.enabled && coffeeOn && !coffeeHidden && counters?.finished == false &&
            shellQuiet && online && !elsewhere && !installShown
        )

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= WIDE_FROM
        // Beside the floating bar on a wide window, where the bar leaves the corner free; above it
        // (or above a chat's own bar, which takes its place) on a narrower one, and inside a chat
        // on any width, whose bar is wider.
        val besideBar = maxWidth >= BESIDE_BAR_FROM && shell.openChat == null
        val bottom = if (!signedIn || besideBar) EDGE else NavBarClearance
        AnimatedVisibility(
            visible = coffeeShown,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier.align(Alignment.BottomStart).padding(start = EDGE, end = EDGE, bottom = bottom),
        ) {
            if (wide) {
                CoffeeCard(onHide = {
                    coffeeHidden = true
                    WebOffers.coffeeHiddenThisSession = true
                })
            } else {
                CoffeePill(onHide = {
                    coffeeHidden = true
                    WebOffers.coffeeHiddenThisSession = true
                })
            }
        }

        // Over the sign in, a one line strip at the top, where the centred form leaves room even on
        // a small phone in landscape, opened into the full card on request; in the app, the card
        // where the coffee button would be.
        val shownOffer = offer
        val atTop = !signedIn
        AnimatedVisibility(
            visible = installShown && shownOffer != null,
            enter = fadeIn() + slideInVertically { if (atTop) -it / 2 else it / 2 },
            exit = fadeOut() + slideOutVertically { if (atTop) -it / 2 else it / 2 },
            modifier = Modifier
                .align(
                    when {
                        atTop -> Alignment.TopCenter
                        wide -> Alignment.BottomStart
                        else -> Alignment.BottomCenter
                    },
                )
                .padding(
                    start = EDGE,
                    end = EDGE,
                    top = if (atTop) 4.dp else 0.dp,
                    // Wider than the coffee card, so above the bar on every width.
                    bottom = if (atTop) 0.dp else NavBarClearance,
                ),
        ) {
            if (shownOffer != null) {
                InstallCard(
                    offer = shownOffer,
                    beforeSignIn = !signedIn,
                    startFolded = atTop,
                    onDone = { answer ->
                        WebOffers.answerInstall(answer)
                        installSettled = true
                        installOpen = false
                    },
                )
            }
        }
    }

    // The coffee window on demand, for a screenshot on the development server only.
    var forcedAsk by remember { mutableStateOf(debug == "ask") }
    if (forcedAsk) {
        CoffeeDialog(
            counters = SupportReminder.Counters(completedWatches = 7, watchTimeMs = 11L * 60 * 60 * 1000),
            asking = true,
            onLater = { forcedAsk = false },
            onNever = { forcedAsk = false },
            onSupporter = { forcedAsk = false },
        )
    }
}

/** On a phone: their yellow pill, their cup and "Buy me a coffee", with a small x beside it. */
@Composable
private fun CoffeePill(onHide: () -> Unit) {
    val s = LocalStrings.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier
                .heightIn(min = 44.dp)
                .clip(CircleShape)
                .background(BmcBrand.Yellow)
                .border(1.dp, BmcBrand.Ink.copy(alpha = 0.12f), CircleShape)
                .clickable(role = Role.Button) { OpenExternal.browse(About.COFFEE) }
                .semantics { contentDescription = s.supportBuyCoffee }
                .padding(start = 14.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BmcCupMark(height = 20.dp)
            Text(
                s.supportBuyCoffee,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = BmcBrand.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp),
            )
        }
        HideButton(onHide)
    }
}

/** On a wide window: a small card, the note and Buy Me a Coffee's own button. */
@Composable
private fun CoffeeCard(onHide: () -> Unit) {
    val s = LocalStrings.current
    val shape = RoundedCornerShape(Corner.Large)
    Box(
        Modifier
            .width(CARD_WIDTH)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
    ) {
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                s.webCoffeeCardNote,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(end = 28.dp),
            )
            CoffeeButton(onClick = { OpenExternal.browse(About.COFFEE) }, modifier = Modifier.fillMaxWidth())
        }
        Box(Modifier.align(Alignment.TopEnd).padding(2.dp)) { HideButton(onHide, framed = false) }
    }
}

/** The x: hides the coffee button for this session. */
@Composable
private fun HideButton(onHide: () -> Unit, framed: Boolean = true) {
    val s = LocalStrings.current
    IconButton(onClick = onHide, modifier = Modifier.size(44.dp).semantics { contentDescription = s.webCoffeeHide }) {
        Box(
            Modifier
                .size(28.dp)
                .then(
                    if (framed) {
                        Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(TmIcons.Close, contentDescription = null, modifier = Modifier.size(16.dp), tint = Tone.muted)
        }
    }
}

/**
 * The install suggestion: a card with a border, no shadow, that leaves the page usable under it.
 * [onDone] takes the answer kept for good: "later", "installed" or "app".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InstallCard(offer: InstallOffer, beforeSignIn: Boolean, startFolded: Boolean, onDone: (String) -> Unit) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(Corner.Large)
    val install: () -> Unit = {
        scope.launch {
            val accepted = runCatching { TmHost.install().await<JsBoolean>().toBoolean() }.getOrDefault(false)
            // A refusal in the browser's own prompt is an answer too: it is not asked again.
            onDone(if (accepted) "installed" else "later")
        }
    }
    val getApp: (AppPlatform) -> Unit = { platform ->
        OpenExternal.browse(WebOffers.downloadUrl(platform))
        onDone("app")
    }
    val title = when (offer) {
        InstallOffer.HomeScreen -> s.webInstallIosTitle
        InstallOffer.WebApp -> s.webInstallTitle
        is InstallOffer.NativeApp -> s.webAppTitle(offer.platform.title.orEmpty())
    }
    var folded by remember { mutableStateOf(startFolded) }
    if (folded) {
        // One slim line above the sign in's own heading, even on a 4.7 inch phone: a short title,
        // the one action and an x. "Show how" opens the steps.
        val short = when (offer) {
            InstallOffer.HomeScreen -> s.webInstallIosShort
            InstallOffer.WebApp -> s.webInstallTitle
            is InstallOffer.NativeApp -> s.webAppShort(offer.platform.title.orEmpty())
        }
        Row(
            Modifier
                .widthIn(max = OFFER_WIDTH)
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                .padding(start = 16.dp, end = 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (offer == InstallOffer.HomeScreen) {
                Icon(ShareGlyph, contentDescription = null, modifier = Modifier.size(18.dp), tint = Tone.accent)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                short,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            when (offer) {
                InstallOffer.HomeScreen -> TextButton(onClick = { folded = false }, modifier = Modifier.heightIn(min = 44.dp)) { Text(s.webInstallShowHow) }
                InstallOffer.WebApp -> TextButton(onClick = install, modifier = Modifier.heightIn(min = 44.dp)) { Text(s.webInstallButton) }
                is InstallOffer.NativeApp -> TextButton(onClick = { getApp(offer.platform) }, modifier = Modifier.heightIn(min = 44.dp)) { Text(s.webAppGet) }
            }
            IconButton(onClick = { onDone("later") }, modifier = Modifier.size(44.dp).semantics { contentDescription = s.webInstallLater }) {
                Icon(TmIcons.Close, contentDescription = null, modifier = Modifier.size(18.dp), tint = Tone.muted)
            }
        }
        return
    }
    Column(
        Modifier
            .widthIn(max = OFFER_WIDTH)
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() },
        )
        val body = when (offer) {
            InstallOffer.HomeScreen -> s.webInstallIosBody
            InstallOffer.WebApp -> s.webInstallPwaBody
            is InstallOffer.NativeApp -> s.webAppBody
        }
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
        if (offer == InstallOffer.HomeScreen) {
            Step(1, s.webInstallIosStepShare, glyph = ShareGlyph)
            Step(2, s.webInstallIosStepAdd, glyph = null)
            if (beforeSignIn) {
                Text(s.webInstallIosBeforeSignIn, style = MaterialTheme.typography.bodySmall, color = Tone.muted)
            }
        }
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(onClick = { onDone("later") }, modifier = Modifier.heightIn(min = 44.dp)) { Text(s.webInstallLater) }
            when (offer) {
                InstallOffer.HomeScreen -> Unit
                InstallOffer.WebApp -> Button(onClick = install, modifier = Modifier.heightIn(min = 44.dp)) { Text(s.webInstallButton) }
                is InstallOffer.NativeApp -> {
                    if (offer.alsoWeb) {
                        OutlinedButton(onClick = install, modifier = Modifier.heightIn(min = 44.dp)) { Text(s.webAppInstallWeb) }
                    }
                    Button(onClick = { getApp(offer.platform) }, modifier = Modifier.heightIn(min = 44.dp)) { Text(s.webAppGet) }
                }
            }
        }
    }
}

/** One numbered step of the Home Screen steps, with Safari's Share glyph where it helps. */
@Composable
private fun Step(number: Int, text: String, glyph: ImageVector?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.size(24.dp).clip(CircleShape).background(Tone.accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(number.toString(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = Tone.accent)
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
        if (glyph != null) Icon(glyph, contentDescription = null, modifier = Modifier.size(20.dp), tint = Tone.accent)
    }
}

/** The Share glyph as Safari's bar shows it: a tray with an arrow out of its top. */
private val ShareGlyph: ImageVector by lazy {
    ImageVector.Builder("Share", 24.dp, 24.dp, 24f, 24f).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(12f, 3f)
            lineTo(12f, 14.5f)
            moveTo(8f, 7f)
            lineTo(12f, 3f)
            lineTo(16f, 7f)
            moveTo(8.5f, 10f)
            lineTo(6.5f, 10f)
            quadTo(5f, 10f, 5f, 11.5f)
            lineTo(5f, 19.5f)
            quadTo(5f, 21f, 6.5f, 21f)
            lineTo(17.5f, 21f)
            quadTo(19f, 21f, 19f, 19.5f)
            lineTo(19f, 11.5f)
            quadTo(19f, 10f, 17.5f, 10f)
            lineTo(15.5f, 10f)
        }
    }.build()
}

/** From this width the coffee button is the card beside the floating bar (Material's expanded). */
private val WIDE_FROM = 840.dp

/**
 * From this width the coffee card fits beside the floating bar, which is centred and up to about
 * 400 dp wide: half the rest is more than the card, its edge and a gap.
 */
private val BESIDE_BAR_FROM = 1000.dp
private val EDGE = 16.dp
private val CARD_WIDTH = 260.dp
private val OFFER_WIDTH = 420.dp
private const val INSTALL_DELAY_MS = 8_000L
