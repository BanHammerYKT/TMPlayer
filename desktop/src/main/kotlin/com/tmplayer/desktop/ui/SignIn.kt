package com.tmplayer.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.tmplayer.data.AuthState
import com.tmplayer.data.CodeInput
import com.tmplayer.data.EmailReset
import com.tmplayer.data.Failures
import com.tmplayer.data.PhoneCountries
import com.tmplayer.data.QrPhase
import com.tmplayer.data.SignInMethod
import com.tmplayer.data.Td
import com.tmplayer.ui.auth.CountryPicker
import com.tmplayer.ui.auth.InternationalGrouping
import com.tmplayer.ui.auth.QrCode
import com.tmplayer.ui.auth.QrExpiredVeil
import com.tmplayer.ui.auth.QrLifeCue
import com.tmplayer.ui.auth.QrRefreshButton
import com.tmplayer.ui.auth.QrScannedVeil
import com.tmplayer.ui.auth.SignInWords
import com.tmplayer.ui.auth.keepInternational
import com.tmplayer.ui.auth.rememberCountdown
import com.tmplayer.ui.auth.rememberInternational
import com.tmplayer.ui.auth.rememberQrClock
import com.tmplayer.ui.auth.rememberSignInCountries
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.components.AppLogo
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Signing in on the desktop (B2.1): the QR code first, since the phone in the viewer's hand is
 * already signed in, with "Sign in with a phone number" under it; then the code and the two-step
 * password. The states and their order are [com.tmplayer.data.AuthReducer]'s, shared with Android,
 * and so are the words, the code clean up and the clocks ([SignInWords], [CodeInput]).
 */
@Composable
fun SignInScreen(auth: AuthState) {
    val s = LocalStrings.current
    // No choice screen on a desktop: the QR code is the first answer, and the phone number is a
    // link under it.
    LaunchedEffect(auth) {
        if (auth == AuthState.ChooseMethod) Td.chooseSignInMethod(SignInMethod.Qr)
    }
    // No page header here, so the empty ground moves the window where the app draws its own
    // title bar; the fields and buttons on it still take their clicks.
    Box(Modifier.fillMaxSize().windowDrag(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                // The QR pane sets its code beside the steps when there is room, so it takes a
                // wider column than the forms do.
                .widthIn(max = if (auth is AuthState.Qr) QR_WIDE_COLUMN else 460.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Image(AppLogo.Mark, contentDescription = null, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)))
            when (auth) {
                // Under the shell's loader, which fades out over whichever pane comes next.
                AuthState.Connecting, AuthState.ChooseMethod -> Unit
                AuthState.Ready -> Waiting(s.signinConnecting)
                is AuthState.Qr -> QrPane(auth.link, auth.scanned)
                is AuthState.Phone -> PhonePane(auth.wrong, initial = null, onBack = null)
                is AuthState.Code -> CodeStep(auth)
                is AuthState.Password -> PasswordPane(auth)
                is AuthState.PasswordRecovery -> PasswordRecoveryPane(auth)
                is AuthState.EmailAddress -> EmailAddressPane(auth)
                is AuthState.EmailCode -> EmailCodePane(auth)
                is AuthState.Failed -> FailedPane(auth)
            }
            // Under every pane: the proxy, for a network where Telegram is reached only through
            // one, rather than only once the line is known to be down.
            TextButton(onClick = { com.tmplayer.ui.connect.ProxyScreenState.open = true }) {
                Text(s.loginProxySettings)
            }
            Text(
                LocalStrings.current.authUnofficial,
                style = MaterialTheme.typography.labelSmall,
                color = Tone.muted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Waiting(label: String) {
    CircularProgressIndicator()
    Text(label, color = Tone.muted)
}

@Composable
private fun Heading(title: String, body: String) {
    Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    Text(body, color = Tone.muted, textAlign = TextAlign.Center)
}

/**
 * One request to Telegram behind a button: [busy] while it is out, never for good (past the
 * ceiling the button is pressable again), and its error under the field.
 */
private class Request {
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
}

@Composable
private fun rememberRequest(): Request {
    val request = remember { Request() }
    LaunchedEffect(request.busy) { if (request.busy) { kotlinx.coroutines.delay(SIGN_IN_PENDING_CEILING_MS); request.busy = false } }
    return request
}

private fun kotlinx.coroutines.CoroutineScope.send(request: Request, call: suspend () -> String?) {
    if (request.busy) return
    request.busy = true
    request.error = null
    launch {
        request.error = call()
        request.busy = false
    }
}

@Composable
private fun QrPane(link: String, scanned: Boolean) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = link) {
        value = withContext(Dispatchers.Default) { QrCode.render(link, QR_PIXELS) }
    }
    val life = rememberQrClock(link)
    // Black on a white plate whatever the theme: contrast is what makes a code scannable.
    val plate: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .size(QR_PLATE)
                    .clip(RoundedCornerShape(Corner.ExtraLarge))
                    .background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                val rendered = bitmap
                if (rendered == null) {
                    CircularProgressIndicator()
                } else {
                    Image(rendered, contentDescription = s.signinQrLabel, modifier = Modifier.fillMaxSize().padding(16.dp))
                }
                QrScannedVeil(scanned, Modifier.matchParentSize())
                QrExpiredVeil(life.phase == QrPhase.Expired && !scanned, { scope.launch { Td.refreshQrCode() } }, Modifier.matchParentSize())
            }
            if (!scanned) QrLifeCue(life, Modifier.size(width = QR_PLATE, height = 36.dp))
        }
    }
    // The same three numbered steps and the note the phone and the TV show, rather than one long
    // sentence: each step is one thing to do on the phone in the other hand.
    val steps: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            QrStep(1, s.loginQrStepOpen)
            QrStep(2, s.loginQrStepDevices)
            QrStep(3, s.loginQrStepLink)
        }
    }
    val note: @Composable () -> Unit = {
        Text(s.loginQrNote, style = MaterialTheme.typography.bodySmall, color = Tone.muted)
    }
    val usePhone: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { scope.launch { Td.chooseSignInMethod(SignInMethod.Phone) } }) {
                Text(s.signinUsePhone)
            }
            QrRefreshButton({ scope.launch { Td.refreshQrCode() } })
        }
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Side by side when the window is wide enough, which keeps the whole pane on a 960 by 600
        // window without scrolling; stacked, as before, in a narrower one.
        if (maxWidth >= QR_SIDE_BY_SIDE) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                plate()
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(s.signinQrTitle, style = MaterialTheme.typography.headlineSmall)
                    steps()
                    note()
                    Box(Modifier.offset(x = (-12).dp)) { usePhone() }
                }
            }
        } else {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(s.signinQrTitle, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                steps()
                plate()
                note()
                usePhone()
            }
        }
    }
}

/** One numbered step beside the QR code, its number in a quiet disc as on the phone. */
@Composable
private fun QrStep(number: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "$number",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * The number: the country (flag, name, picker with search), then one international field drawn
 * grouped as the country writes it. The field starts on the country Telegram guesses from the
 * address it sees, else this computer's locale, and a pasted "+44 7700 900123" or "0044..." is
 * taken whole. [initial] and [onBack] when the number is being changed from the code step.
 */
@Composable
private fun PhonePane(wrong: Boolean, initial: String?, onBack: (() -> Unit)?) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val countries = rememberSignInCountries()
    var number by remember { mutableStateOf(initial?.let(::keepInternational) ?: "+") }
    var picking by remember { mutableStateOf(false) }
    val request = rememberRequest()
    val focus = remember { FocusRequester() }
    val (country, pattern) = rememberInternational(number, countries)

    LaunchedEffect(Unit) { focus.requestFocus() }
    // Telegram guesses the country from the address it sees, which saves typing the dial code.
    LaunchedEffect(countries) {
        if (countries.isEmpty() || number != "+") return@LaunchedEffect
        val iso = runCatching { Td.guessedCountryCode() }.getOrDefault("").ifBlank { Locale.getDefault().country }
        val dial = PhoneCountries.byIso(countries, iso)?.dialCode
        if (dial != null && number == "+") number = "+$dial"
    }

    if (picking) {
        Dialog(onDismissRequest = { picking = false }) {
            Box(Modifier.size(width = 520.dp, height = 620.dp).clip(RoundedCornerShape(Corner.Large)).background(Tone.background)) {
                CountryPicker(
                    countries = countries,
                    selected = country,
                    onPick = { picked ->
                        val national = PhoneCountries.splitInternational(number, countries)?.second.orEmpty()
                        number = "+${picked.dialCode}$national"
                        picking = false
                        scope.launch { runCatching { focus.requestFocus() } }
                    },
                    onDismiss = { picking = false },
                )
            }
        }
    }

    val ready = number.count { it.isDigit() } >= 7
    val submit = { if (ready) scope.send(request) { Td.submitPhoneNumber(number) } }
    Heading(s.signinPhoneTitle, s.signinPhoneBody)
    OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
        Text(
            country?.let { "${it.flag}  ${it.name}  +${it.dialCode}" } ?: s.loginChooseCountry,
            modifier = Modifier.weight(1f),
        )
        Text(s.loginChange, color = Tone.muted)
    }
    OutlinedTextField(
        value = number,
        onValueChange = { number = keepInternational(it); request.error = null },
        label = { Text(s.signinPhoneLabel) },
        singleLine = true,
        isError = request.error != null || wrong,
        supportingText = { request.error?.let { Text(it) } },
        visualTransformation = InternationalGrouping(countries, pattern),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { submit() }, onDone = { submit() }),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
    Button(onClick = { submit() }, enabled = !request.busy && ready, modifier = Modifier.fillMaxWidth()) {
        Text(if (request.busy) s.signinSending else s.signinNext)
    }
    if (onBack != null) {
        TextButton(onClick = onBack) { Text(s.commonBack) }
    } else {
        TextButton(onClick = { scope.launch { Td.cancelPhoneEntry() } }) { Text(s.signinUseQr) }
    }
}

/** The code, or the number again when "Wrong number? Edit" was pressed. */
@Composable
private fun CodeStep(state: AuthState.Code) {
    var changing by remember(state.phoneNumber) { mutableStateOf(false) }
    if (changing) {
        PhonePane(wrong = false, initial = state.phoneNumber, onBack = { changing = false })
    } else {
        CodePane(state, onChangeNumber = { changing = true })
    }
}

@Composable
private fun CodePane(state: AuthState.Code, onChangeNumber: () -> Unit) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf<String?>(null) }
    val request = rememberRequest()
    val resend = rememberRequest()
    var resent by remember { mutableIntStateOf(0) }
    val remaining = rememberCountdown(state.timeout, state.phoneNumber to resent)
    val digits = SignInWords.isDigits(state.delivery)
    val length = SignInWords.codeLength(state)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val submit = {
        if (code.isNotBlank()) {
            submitted = code
            scope.send(request) { Td.submitCode(code.trim()) }
        }
    }
    // The last digit, typed or pasted with the rest, is the whole answer.
    LaunchedEffect(code) { if (CodeInput.shouldAutoSubmit(code, length, digits, submitted)) submit() }

    Heading(s.signinCodeTitle, SignInWords.codeBlurb(state))
    OutlinedTextField(
        value = code,
        // A paste can be the whole message the code came in; only the code is kept.
        onValueChange = { code = CodeInput.clean(it, digits, if (digits) length else 0); request.error = null },
        label = { Text(s.signinCodeLabel) },
        singleLine = true,
        isError = request.error != null || state.wrong,
        supportingText = { (request.error ?: resend.error)?.let { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = if (digits) KeyboardType.Number else KeyboardType.Text, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
    Button(onClick = { submit() }, enabled = !request.busy && code.isNotBlank() && code != submitted, modifier = Modifier.fillMaxWidth()) {
        Text(if (request.busy) s.signinChecking else s.signinSignIn)
    }
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
        // TDLib sends a code again only by a route it offers next ([AuthState.Code.next]).
        if (state.canResend) {
            TextButton(
                onClick = {
                    resent++
                    submitted = null
                    scope.send(resend) { Td.resendCode() }
                },
                enabled = remaining <= 0 && !resend.busy,
            ) {
                Text(if (remaining > 0) s.loginResendIn(SignInWords.clock(remaining)) else SignInWords.resendLabel(state.next))
            }
        }
        TextButton(onClick = onChangeNumber) { Text(s.loginWrongNumber) }
        TextButton(onClick = { scope.launch { Td.restartSignIn() } }) { Text(s.signinStartOver) }
    }
}

/** A password field with its show and hide at the end, as every desktop sign in has. */
@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    error: String?,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    var shown by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = { error?.let { Text(it) } },
        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            val what = if (shown) s.loginHidePassword else s.loginShowPassword
            com.tmplayer.ui.components.IconTip(what) {
                IconButton(onClick = { shown = !shown }) {
                    Icon(if (shown) TmIcons.VisibilityOff else TmIcons.Visibility, contentDescription = what)
                }
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun PasswordPane(state: AuthState.Password) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var forgot by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    val request = rememberRequest()
    val asking = rememberRequest()
    val focus = remember { FocusRequester() }
    LaunchedEffect(forgot) { if (!forgot) runCatching { focus.requestFocus() } }

    if (forgot) {
        // No password and no recovery email: what is left, and the account reset behind a
        // second, plainly worded confirmation.
        Heading(s.loginNoRecoveryTitle, if (confirming) s.loginResetAccountSure else s.loginNoRecoveryBody)
        asking.error?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
        Button(onClick = { if (confirming) confirming = false else forgot = false }, modifier = Modifier.fillMaxWidth()) {
            Text(if (confirming) s.commonCancel else s.loginBackToPassword)
        }
        OutlinedButton(
            onClick = { if (confirming) scope.send(asking) { Td.resetAccount() } else confirming = true },
            enabled = !asking.busy,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (confirming) s.loginResetAccountConfirm else s.loginResetAccount)
        }
        return
    }

    val submit = { if (password.isNotEmpty()) scope.send(request) { Td.submitPassword(password) } }
    Heading(
        s.signinPasswordTitle,
        if (state.hint.isBlank()) s.signinPasswordBody else s.signinPasswordHint(state.hint),
    )
    PasswordField(
        value = password,
        onValueChange = { password = it; request.error = null },
        label = s.signinPasswordLabel,
        error = request.error ?: asking.error ?: if (state.wrong) s.errorsWrongPassword else null,
        onDone = { submit() },
        modifier = Modifier.focusRequester(focus),
    )
    Button(onClick = { submit() }, enabled = !request.busy && password.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
        Text(if (request.busy) s.signinChecking else s.signinSignIn)
    }
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
        TextButton(
            onClick = {
                if (state.recoveryEmail == null) forgot = true else scope.send(asking) { Td.forgotPassword() }
            },
            enabled = !asking.busy,
        ) { Text(s.loginForgotPassword) }
        TextButton(onClick = { scope.launch { Td.restartSignIn() } }) { Text(s.signinStartOver) }
    }
}

/** "Forgot password?" with a recovery email: the mailed code, then a new password, or none. */
@Composable
private fun PasswordRecoveryPane(state: AuthState.PasswordRecovery) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val request = rememberRequest()
    val focus = remember { FocusRequester() }
    LaunchedEffect(state.code == null) { runCatching { focus.requestFocus() } }
    val back: @Composable () -> Unit = {
        TextButton(onClick = { scope.launch { Td.backToPassword() } }) { Text(s.loginBackToPassword) }
    }

    if (state.code == null) {
        var code by remember { mutableStateOf("") }
        val submit = { if (code.isNotBlank()) scope.send(request) { Td.submitRecoveryCode(code) } }
        Heading(s.loginRecoveryTitle, s.loginRecoveryBody(state.pattern))
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.filter(Char::isLetterOrDigit).take(12); request.error = null },
            label = { Text(s.loginRecoveryCodeLabel) },
            singleLine = true,
            isError = request.error != null || state.wrong,
            supportingText = { request.error?.let { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        Button(onClick = { submit() }, enabled = !request.busy && code.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text(if (request.busy) s.signinChecking else s.signinSignIn)
        }
        back()
        return
    }

    var password by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf("") }
    val submit = { scope.send(request) { Td.finishPasswordRecovery(password, hint) } }
    Heading(s.loginRecoveryNewTitle, s.loginRecoveryNewBody)
    PasswordField(
        value = password,
        onValueChange = { password = it; request.error = null },
        label = s.loginRecoveryNewLabel,
        error = request.error,
        onDone = { submit() },
        modifier = Modifier.focusRequester(focus),
    )
    if (password.isNotEmpty()) {
        OutlinedTextField(
            value = hint,
            onValueChange = { hint = it.take(64) },
            label = { Text(s.loginRecoveryHintLabel) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Button(onClick = { submit() }, enabled = !request.busy, modifier = Modifier.fillMaxWidth()) {
        Text(if (request.busy) s.signinChecking else if (password.isEmpty()) s.loginRecoveryTurnOff else s.loginRecoverySave)
    }
    back()
}

/** Telegram asks this account for an email address to sign in with; it mails a code there next. */
@Composable
private fun EmailAddressPane(state: AuthState.EmailAddress) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    val request = rememberRequest()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val ready = '@' in address && '.' in address.substringAfter('@')
    val submit = { if (ready) scope.send(request) { Td.submitEmailAddress(address) } }
    Heading(s.loginEmailAddressTitle, s.loginEmailAddressBody)
    OutlinedTextField(
        value = address,
        onValueChange = { address = it.trim(); request.error = null },
        label = { Text(s.loginEmailAddressLabel) },
        singleLine = true,
        isError = request.error != null || state.wrong,
        supportingText = { request.error?.let { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
    Button(onClick = { submit() }, enabled = !request.busy && ready, modifier = Modifier.fillMaxWidth()) {
        Text(if (request.busy) s.signinSending else s.loginEmailSend)
    }
    TextButton(onClick = { scope.launch { Td.restartSignIn() } }) { Text(s.signinStartOver) }
}

/**
 * The code Telegram mailed to the account's login email, in place of a text; with the code again
 * on request and the email reset with its waiting period, as TDLib reports it.
 */
@Composable
private fun EmailCodePane(state: AuthState.EmailCode) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf<String?>(null) }
    val request = rememberRequest()
    val asking = rememberRequest()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val length = state.length.takeIf { it > 0 } ?: SignInWords.MAX_CODE_LENGTH
    val submit = {
        if (code.isNotBlank()) {
            submitted = code
            scope.send(request) { Td.submitEmailCode(code) }
        }
    }
    LaunchedEffect(code) { if (state.length > 0 && CodeInput.shouldAutoSubmit(code, state.length, true, submitted)) submit() }
    val pendingLeft = rememberCountdown((state.reset as? EmailReset.Pending)?.resetInSeconds ?: 0, state.reset)
    Heading(s.loginEmailCodeTitle, s.loginEmailCodeBody(state.pattern))
    when (val reset = state.reset) {
        is EmailReset.Available -> Text(s.loginEmailResetAvailable(Failures.waitPhrase(reset.waitSeconds)), color = Tone.muted, textAlign = TextAlign.Center)
        is EmailReset.Pending -> Text(s.loginEmailResetPending(Failures.waitPhrase(pendingLeft)), color = Tone.muted, textAlign = TextAlign.Center)
        EmailReset.None -> Unit
    }
    OutlinedTextField(
        value = code,
        onValueChange = { code = CodeInput.clean(it, true, length); request.error = null },
        label = { Text(s.loginEmailCodeLabel) },
        singleLine = true,
        isError = request.error != null || state.wrong,
        supportingText = { (request.error ?: asking.error)?.let { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
    Button(onClick = { submit() }, enabled = !request.busy && code.isNotBlank() && code != submitted, modifier = Modifier.fillMaxWidth()) {
        Text(if (request.busy) s.signinChecking else s.signinSignIn)
    }
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
        TextButton(onClick = { submitted = null; scope.send(asking) { Td.resendCode() } }, enabled = !asking.busy) { Text(s.loginEmailResend) }
        if (state.reset is EmailReset.Available) {
            TextButton(onClick = { scope.send(asking) { Td.resetEmailAddress() } }, enabled = !asking.busy) { Text(s.loginEmailReset) }
        }
        TextButton(onClick = { scope.launch { Td.restartSignIn() } }) { Text(s.signinStartOver) }
    }
}

/**
 * A failure, with Try again; a flood wait counts down first, since a Try again before it has
 * passed would fail the same way.
 */
@Composable
private fun FailedPane(state: AuthState.Failed) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val left = rememberCountdown(state.waitSeconds, state)
    Heading(s.signinFailed, state.message)
    Button(onClick = { scope.launch { Td.restartSignIn() } }, enabled = left <= 0) {
        Text(if (left > 0) s.loginTryAgainIn(SignInWords.clock(left)) else s.commonTryAgain)
    }
    SendLogsActions()
}

private const val QR_PIXELS = 560
private val QR_PLATE = 280.dp

/** The QR pane's column: the plate, a gap and the steps beside it, with room for the longer languages. */
private val QR_WIDE_COLUMN = 820.dp

/** From this width inside the column the steps stand beside the code rather than over it. */
private val QR_SIDE_BY_SIDE = 640.dp

/**
 * How long a sign in button may say Sending or Checking before it can be pressed again, as on the
 * phone: every step answers with a new state or an error long before this, and the slow connect
 * help ([com.tmplayer.ui.connect]) covers a line that is not answering at all.
 */
private const val SIGN_IN_PENDING_CEILING_MS = 20_000L
