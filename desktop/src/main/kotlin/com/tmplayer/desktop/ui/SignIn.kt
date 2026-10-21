package com.tmplayer.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tmplayer.data.AuthState
import com.tmplayer.data.CodeDelivery
import com.tmplayer.data.SignInMethod
import com.tmplayer.data.Td
import com.tmplayer.i18n.L
import com.tmplayer.ui.auth.QrCode
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.components.AppLogo
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Signing in on the desktop (B2.1): the QR code first, since the phone in the viewer's hand is
 * already signed in, with "Sign in with a phone number" under it; then the code and the two-step
 * password. The states and their order are [com.tmplayer.data.AuthReducer]'s, shared with Android.
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
                is AuthState.Phone -> PhonePane(auth.wrong)
                is AuthState.Code -> CodePane(auth)
                is AuthState.Password -> PasswordPane(auth)
                is AuthState.EmailAddress -> EmailAddressPane(auth)
                is AuthState.EmailCode -> EmailCodePane(auth)
                is AuthState.Failed -> FailedPane(auth.message)
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

@Composable
private fun QrPane(link: String, scanned: Boolean) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = link) {
        value = withContext(Dispatchers.Default) { QrCode.render(link, QR_PIXELS) }
    }
    // Black on a white plate whatever the theme: contrast is what makes a code scannable.
    val plate: @Composable () -> Unit = {
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
            com.tmplayer.ui.auth.QrScannedVeil(scanned, Modifier.matchParentSize())
            val expired = com.tmplayer.ui.auth.rememberQrExpired(link)
            com.tmplayer.ui.auth.QrExpiredVeil(expired && !scanned, { scope.launch { Td.refreshQrCode() } }, Modifier.matchParentSize())
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
            com.tmplayer.ui.auth.QrRefreshButton({ scope.launch { Td.refreshQrCode() } })
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

@Composable
private fun PhonePane(wrong: Boolean) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    var number by remember { mutableStateOf("+") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Never "Sending" or "Checking" for good: past the ceiling the button is pressable again.
    LaunchedEffect(busy) { if (busy) { kotlinx.coroutines.delay(SIGN_IN_PENDING_CEILING_MS); busy = false } }
    val focus = remember { FocusRequester() }

    // Telegram guesses the country from the address it sees, which saves typing the dial code.
    LaunchedEffect(Unit) {
        focus.requestFocus()
        val guess = runCatching { Td.guessedCountryCode() }.getOrDefault("")
        if (guess.isBlank() || number != "+") return@LaunchedEffect
        val dial = runCatching { Td.countries() }.getOrDefault(emptyList())
            .firstOrNull { it.countryCode == guess }?.callingCodes?.firstOrNull()
        if (dial != null && number == "+") number = "+$dial "
    }

    val submit = {
        if (!busy) {
            busy = true
            scope.launch {
                error = Td.submitPhoneNumber(number.filter { it.isDigit() || it == '+' })
                busy = false
            }
        }
    }
    Heading(s.signinPhoneTitle, s.signinPhoneBody)
    OutlinedTextField(
        value = number,
        onValueChange = { number = it; error = null },
        label = { Text(s.signinPhoneLabel) },
        singleLine = true,
        isError = error != null || wrong,
        supportingText = { error?.let { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { submit() }),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
    Button(onClick = { submit() }, enabled = !busy && number.count { it.isDigit() } >= 5, modifier = Modifier.fillMaxWidth()) {
        Text(if (busy) s.signinSending else s.signinNext)
    }
    TextButton(onClick = { scope.launch { Td.cancelPhoneEntry() } }) { Text(s.signinUseQr) }
}

@Composable
private fun CodePane(state: AuthState.Code) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Never "Sending" or "Checking" for good: past the ceiling the button is pressable again.
    LaunchedEffect(busy) { if (busy) { kotlinx.coroutines.delay(SIGN_IN_PENDING_CEILING_MS); busy = false } }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val submit = {
        if (!busy && code.isNotBlank()) {
            busy = true
            scope.launch {
                error = Td.submitCode(code.trim())
                busy = false
            }
        }
    }
    Heading(s.signinCodeTitle, whereTheCodeWent(state))
    OutlinedTextField(
        value = code,
        onValueChange = { code = it; error = null },
        label = { Text(s.signinCodeLabel) },
        singleLine = true,
        isError = error != null || state.wrong,
        supportingText = { error?.let { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
    Button(onClick = { submit() }, enabled = !busy && code.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
        Text(if (busy) s.signinChecking else s.signinSignIn)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.next != null) {
            TextButton(onClick = { scope.launch { error = Td.resendCode() } }) { Text(s.signinResend) }
        }
        TextButton(onClick = { scope.launch { Td.restartSignIn() } }) { Text(s.signinStartOver) }
    }
}

private fun whereTheCodeWent(state: AuthState.Code): String = when (state.delivery) {
    CodeDelivery.TelegramApp -> L.signinCodeApp
    CodeDelivery.Sms, CodeDelivery.SmsWord, CodeDelivery.SmsPhrase -> L.signinCodeSms(state.phoneNumber)
    CodeDelivery.Call -> L.signinCodeCall(state.phoneNumber)
    CodeDelivery.MissedCall, CodeDelivery.FlashCall -> L.signinCodeMissedCall(state.phoneNumber)
    CodeDelivery.Fragment -> L.signinCodeFragment
    CodeDelivery.Firebase, CodeDelivery.Unknown -> L.signinCodeSent(state.phoneNumber)
}

@Composable
private fun PasswordPane(state: AuthState.Password) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Never "Sending" or "Checking" for good: past the ceiling the button is pressable again.
    LaunchedEffect(busy) { if (busy) { kotlinx.coroutines.delay(SIGN_IN_PENDING_CEILING_MS); busy = false } }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val submit = {
        if (!busy && password.isNotEmpty()) {
            busy = true
            scope.launch {
                error = Td.submitPassword(password)
                busy = false
            }
        }
    }
    Heading(
        s.signinPasswordTitle,
        if (state.hint.isBlank()) s.signinPasswordBody else s.signinPasswordHint(state.hint),
    )
    OutlinedTextField(
        value = password,
        onValueChange = { password = it; error = null },
        label = { Text(s.signinPasswordLabel) },
        singleLine = true,
        isError = error != null || state.wrong,
        supportingText = { error?.let { Text(it) } },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
    Button(onClick = { submit() }, enabled = !busy && password.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
        Text(if (busy) s.signinChecking else s.signinSignIn)
    }
    TextButton(onClick = { scope.launch { Td.restartSignIn() } }) { Text(s.signinStartOver) }
}

/** Telegram asks this account for an email address to sign in with; it mails a code there next. */
@Composable
private fun EmailAddressPane(state: AuthState.EmailAddress) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Never "Sending" or "Checking" for good: past the ceiling the button is pressable again.
    LaunchedEffect(busy) { if (busy) { kotlinx.coroutines.delay(SIGN_IN_PENDING_CEILING_MS); busy = false } }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val ready = '@' in address && '.' in address.substringAfter('@')
    val submit = {
        if (!busy && ready) {
            busy = true
            scope.launch {
                error = Td.submitEmailAddress(address)
                busy = false
            }
        }
    }
    Heading(s.loginEmailAddressTitle, s.loginEmailAddressBody)
    OutlinedTextField(
        value = address,
        onValueChange = { address = it.trim(); error = null },
        label = { Text(s.loginEmailAddressLabel) },
        singleLine = true,
        isError = error != null || state.wrong,
        supportingText = { error?.let { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
    Button(onClick = { submit() }, enabled = !busy && ready, modifier = Modifier.fillMaxWidth()) {
        Text(if (busy) s.signinSending else s.loginEmailSend)
    }
    TextButton(onClick = { scope.launch { Td.restartSignIn() } }) { Text(s.signinStartOver) }
}

/** The code Telegram mailed to the account's login email, in place of a text. */
@Composable
private fun EmailCodePane(state: AuthState.EmailCode) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Never "Sending" or "Checking" for good: past the ceiling the button is pressable again.
    LaunchedEffect(busy) { if (busy) { kotlinx.coroutines.delay(SIGN_IN_PENDING_CEILING_MS); busy = false } }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val submit = {
        if (!busy && code.isNotBlank()) {
            busy = true
            scope.launch {
                error = Td.submitEmailCode(code)
                busy = false
            }
        }
    }
    Heading(s.loginEmailCodeTitle, s.loginEmailCodeBody(state.pattern))
    OutlinedTextField(
        value = code,
        onValueChange = { code = it.filter(Char::isLetterOrDigit); error = null },
        label = { Text(s.loginEmailCodeLabel) },
        singleLine = true,
        isError = error != null || state.wrong,
        supportingText = { error?.let { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
    Button(onClick = { submit() }, enabled = !busy && code.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
        Text(if (busy) s.signinChecking else s.signinSignIn)
    }
    TextButton(onClick = { scope.launch { Td.restartSignIn() } }) { Text(s.signinStartOver) }
}

@Composable
private fun FailedPane(message: String) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    Heading(s.signinFailed, message)
    Button(onClick = { scope.launch { Td.restartSignIn() } }) { Text(s.commonTryAgain) }
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
