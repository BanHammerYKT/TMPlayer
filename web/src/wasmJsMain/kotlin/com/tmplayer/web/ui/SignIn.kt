package com.tmplayer.web.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tmplayer.data.AuthState
import com.tmplayer.data.CodeDelivery
import com.tmplayer.data.CodeInput
import com.tmplayer.data.Country
import com.tmplayer.data.EmailReset
import com.tmplayer.data.Failures
import com.tmplayer.data.PhoneCountries
import com.tmplayer.data.PhoneFormat
import com.tmplayer.data.QrClock
import com.tmplayer.data.QrCode
import com.tmplayer.data.QrPhase
import com.tmplayer.data.SignInMethod
import com.tmplayer.i18n.Messages
import com.tmplayer.web.td.WebAuth
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Signing in, as the desktop does it: the QR code first, since the phone in the viewer's hand is
 * already signed in, with "Sign in with a phone number" under it; then the code, the email code
 * and the two-step password, with recovery and the account reset behind it. The states and their
 * order are the shared AuthFlow's; the input rules (pasted codes and numbers, the QR's clock) are
 * :core's SignInSteps and PhoneCountries, as in the apps.
 */
@Composable
fun SignInScreen(auth: WebAuth) {
    val state by auth.state.collectAsState()
    val stalled by auth.stalled.collectAsState()
    val s = LocalStrings.current
    var proxyNote by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        if (state == AuthState.ChooseMethod) auth.choose(SignInMethod.Qr)
    }
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (val st = state) {
                AuthState.Connecting, AuthState.ChooseMethod -> Connecting(stalled, onRetry = auth::retry)
                is AuthState.Qr -> QrStep(st.link, onPhone = { auth.choose(SignInMethod.Phone) }, onRenew = { auth.restart(SignInMethod.Qr) })
                is AuthState.Phone -> PhoneStep(auth)
                is AuthState.Code -> CodeStep(auth, st)
                is AuthState.EmailAddress -> EmailStep(auth)
                is AuthState.EmailCode -> EmailCodeStep(auth, st)
                is AuthState.Password -> PasswordStep(auth, st)
                is AuthState.PasswordRecovery -> RecoveryStep(auth, st)
                AuthState.Ready -> Connecting(false, onRetry = {})
                is AuthState.Failed -> Failed(st, onStartOver = { auth.restart() })
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { proxyNote = true }, modifier = Modifier.heightIn(min = 44.dp)) { Text(s.loginProxySettings) }
            Spacer(Modifier.height(24.dp))
            Text(
                s.authUnofficial,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
    if (proxyNote) {
        AlertDialog(
            onDismissRequest = { proxyNote = false },
            confirmButton = { TextButton(onClick = { proxyNote = false }) { Text(s.commonGotIt) } },
            title = { Text(s.loginProxySettings) },
            text = { Text(s.webProxyUnavailable) },
        )
    }
}

@Composable
private fun Title(text: String, body: String? = null) {
    Text(text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    if (body != null) {
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun Connecting(stalled: Boolean, onRetry: () -> Unit) {
    val s = LocalStrings.current
    CircularProgressIndicator()
    Spacer(Modifier.height(16.dp))
    Text(s.signinConnecting, style = MaterialTheme.typography.bodyLarge)
    if (stalled) {
        Spacer(Modifier.height(16.dp))
        Text(s.signinUnreachable, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRetry) { Text(s.commonTryAgain) }
    }
}

@OptIn(ExperimentalTime::class)
private fun now(): Long = Clock.System.now().toEpochMilliseconds()

@Composable
private fun QrStep(link: String, onPhone: () -> Unit, onRenew: () -> Unit) {
    val s = LocalStrings.current
    // When each link arrived, so the cue under the code follows TDLib's own renewal rate.
    var shownAt by remember { mutableLongStateOf(now()) }
    var gap by remember { mutableStateOf<Long?>(null) }
    var lastLink by remember { mutableStateOf("") }
    if (link != lastLink) {
        val t = now()
        if (lastLink.isNotEmpty()) gap = t - shownAt
        shownAt = t
        lastLink = link
    }
    val age by produceState(0L, shownAt) {
        while (true) {
            value = now() - shownAt
            delay(250)
        }
    }
    val lifetime = QrClock.lifetime(gap)
    val phase = QrClock.phase(age, lifetime)
    Title(s.loginQrTitle, s.loginQrBlurb)
    val code = remember(link) { QrCode.encode(link) }
    Box(
        Modifier
            .size(248.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .padding(16.dp)
            .semantics { contentDescription = s.loginQrDescription },
        contentAlignment = Alignment.Center,
    ) {
        if (code != null && phase != QrPhase.Expired) {
            Canvas(Modifier.fillMaxSize()) {
                val cell = size.minDimension / code.size
                for (y in 0 until code.size) for (x in 0 until code.size) {
                    if (code[x, y]) {
                        drawRect(Color(0xFF0C0E10), topLeft = Offset(x * cell, y * cell), size = Size(cell + 0.5f, cell + 0.5f))
                    }
                }
            }
        } else {
            Button(onClick = onRenew) { Text(s.loginQrRefresh) }
        }
    }
    Spacer(Modifier.height(12.dp))
    LinearProgressIndicator(
        progress = { QrClock.remaining(age, lifetime) },
        modifier = Modifier.width(248.dp).height(4.dp).clip(RoundedCornerShape(2.dp)),
    )
    Spacer(Modifier.height(6.dp))
    Text(
        when (phase) {
            QrPhase.Fresh -> s.loginQrNote
            QrPhase.ExpiringSoon -> s.loginQrExpiresSoon
            QrPhase.Renewing -> s.loginQrRenewing
            QrPhase.Expired -> s.loginQrExpired
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(20.dp))
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Step(1, s.loginQrStepOpen)
        Step(2, s.loginQrStepDevices)
        Step(3, s.loginQrStepLink)
    }
    Spacer(Modifier.height(16.dp))
    TextButton(onClick = onPhone, modifier = Modifier.heightIn(min = 48.dp)) { Text(s.signinUsePhone) }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(LocalStrings.current.formatter.number(n), color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.size(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Field(
    value: String,
    onValue: (String) -> Unit,
    label: String,
    keyboard: KeyboardType,
    error: String?,
    onDone: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    transformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
    focus: Boolean = true,
) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (focus) runCatching { requester.requestFocus() } }
    val held = com.tmplayer.ui.components.rememberHeldText(value)
    OutlinedTextField(
        value = held.value,
        onValueChange = { held.edit(it, onValue) },
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        visualTransformation = transformation,
        trailingIcon = trailing,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier.focusRequester(requester),
    )
}

@Composable
private fun Submit(label: String, busy: Boolean, busyLabel: String, enabled: Boolean, onClick: () -> Unit) {
    Spacer(Modifier.height(16.dp))
    Button(onClick = onClick, enabled = enabled && !busy, modifier = Modifier.fillMaxWidth().height(48.dp)) {
        Text(if (busy) busyLabel else label)
    }
}

@Composable
private fun StartOver(auth: WebAuth) {
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = { auth.restart() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(LocalStrings.current.signinStartOver) }
}

/** National digits drawn grouped the way the country writes them, never typed that way. */
private class PhoneLayout(private val pattern: String?) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val formatted = PhoneFormat.format(text.text, pattern)
        return TransformedText(
            AnnotatedString(formatted),
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int): Int = PhoneFormat.toFormatted(offset, formatted)
                override fun transformedToOriginal(offset: Int): Int = PhoneFormat.toRaw(offset, formatted).coerceAtMost(text.length)
            },
        )
    }
}

@Composable
private fun PhoneStep(auth: WebAuth) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val countries by produceState(emptyList<Country>()) { value = auth.countries() }
    var dial by remember { mutableStateOf("") }
    var national by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(countries) {
        if (dial.isEmpty() && countries.isNotEmpty()) {
            PhoneCountries.byIso(countries, auth.guessedCountry())?.let { dial = it.dialCode }
        }
    }
    val country = PhoneCountries.byDialCode(countries, dial)
    val pattern by produceState<String?>(null, dial, national) { value = auth.formatPhone(dial, national) }
    val digits = (dial + national).count { it.isDigit() }
    val submit = {
        if (!busy && digits >= 5) {
            busy = true
            scope.launch {
                error = auth.submitPhone("+$dial$national")
                busy = false
            }
        }
    }
    Title(s.signinPhoneTitle, s.signinPhoneBody)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        val heldDial = com.tmplayer.ui.components.rememberHeldText("+$dial")
        OutlinedTextField(
            value = heldDial.value,
            onValueChange = { typed ->
                heldDial.edit(typed) { v ->
                    // A whole number pasted into the code field lands in both.
                    val split = PhoneCountries.splitInternational(v, countries)
                    if (split != null && split.second.isNotEmpty()) {
                        dial = split.first
                        national = split.second
                    } else {
                        dial = v.filter(Char::isDigit).take(PhoneCountries.MAX_DIAL_DIGITS)
                    }
                }
            },
            label = { Text(country?.let { it.flag + " " + it.name } ?: s.loginChooseCountry, maxLines = 1) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.width(120.dp),
        )
        Spacer(Modifier.width(8.dp))
        Field(
            national,
            { v ->
                val split = PhoneCountries.splitInternational(v, countries)
                if (split != null) {
                    dial = split.first
                    national = split.second
                } else {
                    national = v.filter(Char::isDigit)
                }
            },
            s.signinPhoneLabel,
            KeyboardType.Phone,
            error,
            { submit() },
            modifier = Modifier.weight(1f),
            transformation = PhoneLayout(pattern),
        )
    }
    TextButton(onClick = { picking = true }, modifier = Modifier.heightIn(min = 44.dp)) { Text(s.loginChooseCountry) }
    Submit(s.signinNext, busy, s.signinSending, digits >= 5) { submit() }
    TextButton(onClick = { auth.restart(SignInMethod.Qr) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(s.signinUseQr) }
    if (picking) {
        CountryDialog(countries, onPick = {
            dial = it.dialCode
            picking = false
        }, onDismiss = { picking = false })
    }
}

@Composable
private fun CountryDialog(countries: List<Country>, onPick: (Country) -> Unit, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    var query by remember { mutableStateOf("") }
    val shown = remember(query, countries) { countries.filter { PhoneCountries.matches(it, query) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(s.loginCloseCountryList) } },
        title = { Text(s.loginChooseCountry) },
        text = {
            Column {
                val held2 = com.tmplayer.ui.components.rememberHeldText(query)
                OutlinedTextField(
                    value = held2.value,
                    onValueChange = { held2.edit(it, { query = it }) },
                    placeholder = { Text(s.loginCountrySearch) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                if (shown.isEmpty()) {
                    Text(s.loginCountryNoMatch, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(shown, key = { it.iso }) { c ->
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { onPick(c) }.padding(horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(c.flag + "  " + c.name, modifier = Modifier.weight(1f))
                                Text("+" + c.dialCode, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
    )
}

private fun deliveryText(state: AuthState.Code, s: Messages): String = when (state.delivery) {
    CodeDelivery.TelegramApp -> s.loginDeliveryApp
    CodeDelivery.Sms -> s.loginDeliverySms(state.phoneNumber)
    CodeDelivery.Call -> s.loginDeliveryCall(state.phoneNumber)
    CodeDelivery.MissedCall -> s.loginDeliveryMissedCall
    CodeDelivery.FlashCall -> s.loginDeliveryFlashCall(state.phoneNumber)
    CodeDelivery.Fragment -> s.loginDeliveryFragment
    CodeDelivery.SmsWord -> s.loginDeliveryWord
    CodeDelivery.SmsPhrase -> s.loginDeliveryPhrase
    CodeDelivery.Firebase -> s.loginDeliveryFirebase(state.phoneNumber)
    CodeDelivery.Unknown -> s.loginDeliveryUnknown(state.phoneNumber)
}

/** Seconds counted down from [from] while this step is up. */
@Composable
private fun countdown(key: Any, from: Int): Int {
    val left by produceState(from, key) {
        var n = from
        while (n > 0) {
            value = n
            delay(1000)
            n--
        }
        value = 0
    }
    return left
}

@Composable
private fun CodeStep(auth: WebAuth, state: AuthState.Code) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var sentAt by remember { mutableStateOf(0) }
    val word = state.delivery == CodeDelivery.SmsWord || state.delivery == CodeDelivery.SmsPhrase
    val length = if (state.length > 0) state.length else 5
    val submit = {
        if (!busy && code.isNotBlank()) {
            busy = true
            submitted = code
            scope.launch {
                error = auth.submitCode(code)
                busy = false
            }
        }
    }
    Title(s.loginCodeTitle, deliveryText(state, s))
    Field(
        code,
        {
            code = CodeInput.clean(it, digits = !word, length = length)
            if (CodeInput.shouldAutoSubmit(code, length, !word, submitted)) submit()
        },
        s.loginCode,
        if (word) KeyboardType.Text else KeyboardType.NumberPassword,
        error,
        { submit() },
    )
    Submit(s.loginSignIn, busy, s.loginSigningIn, code.isNotBlank()) { submit() }
    if (state.canResend) {
        val left = countdown(sentAt to state, state.timeout)
        TextButton(
            onClick = {
                scope.launch {
                    error = auth.resendCode()
                    sentAt++
                }
            },
            enabled = left == 0,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(
                if (left > 0) {
                    s.loginResendIn(s.formatter.clock(left * 1000L))
                } else {
                    when (state.next) {
                        CodeDelivery.Sms -> s.loginResendSms
                        CodeDelivery.Call -> s.loginResendCall
                        CodeDelivery.TelegramApp -> s.loginResendApp
                        CodeDelivery.Fragment -> s.loginResendFragment
                        else -> s.loginResendAgain
                    }
                },
            )
        }
    }
    TextButton(onClick = { auth.restart(SignInMethod.Phone) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(s.loginWrongNumber) }
    StartOver(auth)
}

@Composable
private fun EmailStep(auth: WebAuth) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val submit = {
        if (!busy && '@' in email) {
            busy = true
            scope.launch {
                error = auth.submitEmail(email)
                busy = false
            }
        }
    }
    Title(s.loginEmailAddressTitle, s.loginEmailAddressBody)
    Field(email, { email = it }, s.loginEmailAddressLabel, KeyboardType.Email, error, { submit() })
    Submit(s.loginEmailSend, busy, s.loginSendingCode, '@' in email) { submit() }
    StartOver(auth)
}

@Composable
private fun EmailCodeStep(auth: WebAuth, state: AuthState.EmailCode) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val submit = {
        if (!busy && code.isNotBlank()) {
            busy = true
            submitted = code
            scope.launch {
                error = auth.submitEmailCode(code)
                busy = false
            }
        }
    }
    Title(s.loginEmailCodeTitle, s.loginEmailCodeBody(state.pattern))
    Field(
        code,
        {
            code = CodeInput.clean(it, digits = true, length = state.length)
            if (CodeInput.shouldAutoSubmit(code, state.length, true, submitted)) submit()
        },
        s.loginEmailCodeLabel,
        KeyboardType.NumberPassword,
        error,
        { submit() },
    )
    Submit(s.loginSignIn, busy, s.loginSigningIn, code.isNotBlank()) { submit() }
    TextButton(onClick = { scope.launch { error = auth.resendCode() } }, modifier = Modifier.heightIn(min = 48.dp)) { Text(s.loginEmailResend) }
    when (val reset = state.reset) {
        is EmailReset.Available -> {
            Text(
                s.loginEmailResetAvailable(Failures.waitPhrase(reset.waitSeconds)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = { scope.launch { error = auth.resetEmail() } }, modifier = Modifier.heightIn(min = 48.dp)) { Text(s.loginEmailReset) }
        }
        is EmailReset.Pending -> Text(
            s.loginEmailResetPending(Failures.waitPhrase(reset.resetInSeconds)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        EmailReset.None -> Unit
    }
    StartOver(auth)
}

@Composable
private fun PasswordStep(auth: WebAuth, state: AuthState.Password) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var noRecovery by remember { mutableStateOf(false) }
    var resetSure by remember { mutableStateOf(false) }
    val submit = {
        if (!busy && password.isNotEmpty()) {
            busy = true
            scope.launch {
                error = auth.submitPassword(password)
                busy = false
            }
        }
    }
    Title(s.loginPasswordTitle, s.loginPasswordBlurb)
    if (state.hint.isNotBlank()) {
        Text(s.loginPasswordHint(state.hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
    }
    Field(
        password,
        { password = it },
        s.loginPassword,
        KeyboardType.Password,
        error,
        { submit() },
        transformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailing = {
            TextButton(onClick = { visible = !visible }) { Text(if (visible) s.loginHidePassword else s.loginShowPassword) }
        },
    )
    Submit(s.loginSignIn, busy, s.loginSigningIn, password.isNotEmpty()) { submit() }
    TextButton(
        onClick = {
            if (state.recoveryEmail == null) {
                noRecovery = true
            } else {
                scope.launch { error = auth.forgotPassword() }
            }
        },
        modifier = Modifier.heightIn(min = 48.dp),
    ) { Text(s.loginForgotPassword) }
    StartOver(auth)
    if (noRecovery) {
        AlertDialog(
            onDismissRequest = { noRecovery = false },
            title = { Text(s.loginNoRecoveryTitle) },
            text = { Text(s.loginNoRecoveryBody) },
            confirmButton = {
                TextButton(onClick = {
                    noRecovery = false
                    resetSure = true
                }) { Text(s.loginResetAccount, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { noRecovery = false }) { Text(s.commonCancel) } },
        )
    }
    if (resetSure) {
        // The second confirmation, worded as what it does.
        AlertDialog(
            onDismissRequest = { resetSure = false },
            title = { Text(s.loginResetAccount) },
            text = { Text(s.loginResetAccountSure) },
            confirmButton = {
                TextButton(onClick = {
                    resetSure = false
                    scope.launch { error = auth.resetAccount() }
                }) { Text(s.loginResetAccountConfirm, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { resetSure = false }) { Text(s.commonCancel) } },
        )
    }
}

@Composable
private fun RecoveryStep(auth: WebAuth, state: AuthState.PasswordRecovery) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    if (state.code == null) {
        var code by remember { mutableStateOf("") }
        val submit = {
            if (!busy && code.isNotBlank()) {
                busy = true
                scope.launch {
                    error = auth.submitRecoveryCode(code)
                    busy = false
                }
            }
        }
        Title(s.loginRecoveryTitle, s.loginRecoveryBody(state.pattern))
        Field(code, { code = CodeInput.clean(it, digits = true, length = 0) }, s.loginRecoveryCodeLabel, KeyboardType.NumberPassword, error, { submit() })
        Submit(s.signinNext, busy, s.signinSending, code.isNotBlank()) { submit() }
    } else {
        var password by remember { mutableStateOf("") }
        var hint by remember { mutableStateOf("") }
        val submit = {
            if (!busy) {
                busy = true
                scope.launch {
                    error = auth.finishRecovery(password, hint)
                    busy = false
                }
            }
        }
        Title(s.loginRecoveryNewTitle, s.loginRecoveryNewBody)
        Field(password, { password = it }, s.loginRecoveryNewLabel, KeyboardType.Password, error, { submit() }, transformation = PasswordVisualTransformation())
        Spacer(Modifier.height(8.dp))
        if (password.isNotEmpty()) {
            Field(hint, { hint = it }, s.loginRecoveryHintLabel, KeyboardType.Text, null, { submit() }, focus = false)
        }
        Submit(if (password.isEmpty()) s.loginRecoveryTurnOff else s.loginRecoverySave, busy, s.loginSigningIn, true) { submit() }
    }
    TextButton(onClick = { auth.backToPassword() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(s.loginBackToPassword) }
}

@Composable
private fun Failed(state: AuthState.Failed, onStartOver: () -> Unit) {
    val s = LocalStrings.current
    Title(s.signinFailed, state.message)
    val left = countdown(state, state.waitSeconds)
    Button(onClick = onStartOver, enabled = left == 0, modifier = Modifier.height(48.dp)) {
        Text(if (left > 0) s.loginTryAgainIn(Failures.waitPhrase(left)) else s.signinStartOver)
    }
}
