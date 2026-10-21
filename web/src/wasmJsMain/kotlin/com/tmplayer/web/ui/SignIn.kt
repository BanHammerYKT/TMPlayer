package com.tmplayer.web.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tmplayer.data.AuthState
import com.tmplayer.data.CodeDelivery
import com.tmplayer.data.QrCode
import com.tmplayer.data.SignInMethod
import com.tmplayer.web.td.WebAuth
import kotlinx.coroutines.launch

/**
 * Signing in, as the desktop does it: the QR code first, since the phone in the viewer's hand is
 * already signed in, with "Sign in with a phone number" under it; then the code, the email code
 * and the two-step password. States and order are the shared AuthFlow's.
 */
@Composable
fun SignInScreen(auth: WebAuth) {
    val state by auth.state.collectAsState()
    val stalled by auth.stalled.collectAsState()
    val s = LocalStrings.current
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
                is AuthState.Qr -> QrStep(st.link, onPhone = { auth.choose(SignInMethod.Phone) })
                is AuthState.Phone -> PhoneStep(auth, st.wrong)
                is AuthState.Code -> CodeStep(auth, st)
                is AuthState.EmailAddress -> EmailStep(auth)
                is AuthState.EmailCode -> EmailCodeStep(auth, st)
                is AuthState.Password -> PasswordStep(auth, st)
                AuthState.Ready -> Connecting(false, onRetry = {})
                is AuthState.Failed -> Failed(st.message, onStartOver = { auth.restart() })
            }
            Spacer(Modifier.height(32.dp))
            Text(
                s.authUnofficial,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
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

@Composable
private fun QrStep(link: String, onPhone: () -> Unit) {
    val s = LocalStrings.current
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
        if (code != null) {
            Canvas(Modifier.fillMaxSize()) {
                val cell = size.minDimension / code.size
                for (y in 0 until code.size) for (x in 0 until code.size) {
                    if (code[x, y]) {
                        drawRect(Color(0xFF0C0E10), topLeft = Offset(x * cell, y * cell), size = Size(cell + 0.5f, cell + 0.5f))
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(24.dp))
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Step(1, s.loginQrStepOpen)
        Step(2, s.loginQrStepDevices)
        Step(3, s.loginQrStepLink)
    }
    Spacer(Modifier.height(16.dp))
    Text(s.loginQrNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    Spacer(Modifier.height(16.dp))
    TextButton(onClick = onPhone, modifier = Modifier.height(48.dp)) { Text(s.signinUsePhone) }
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
    password: Boolean = false,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
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
    TextButton(onClick = { auth.restart() }, modifier = Modifier.height(48.dp)) { Text(LocalStrings.current.signinStartOver) }
}

@Composable
private fun PhoneStep(auth: WebAuth, wrong: Boolean) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    var number by remember { mutableStateOf("+") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val submit = {
        if (!busy && number.count { it.isDigit() } >= 5) {
            busy = true
            scope.launch {
                error = auth.submitPhone(number)
                busy = false
            }
        }
    }
    Title(s.signinPhoneTitle, s.signinPhoneBody)
    Field(number, { number = it.filter { c -> c.isDigit() || c == '+' || c == ' ' } }, s.signinPhoneLabel, KeyboardType.Phone, error, { submit() })
    Submit(s.signinNext, busy, s.signinSending, number.count { it.isDigit() } >= 5) { submit() }
    TextButton(onClick = { auth.choose(SignInMethod.Qr); auth.restart(SignInMethod.Qr) }, modifier = Modifier.height(48.dp)) { Text(s.signinUseQr) }
}

private fun deliveryText(state: AuthState.Code, s: com.tmplayer.i18n.Messages): String = when (state.delivery) {
    CodeDelivery.TelegramApp -> s.loginDeliveryApp
    CodeDelivery.Sms -> s.loginDeliverySms(state.phoneNumber)
    CodeDelivery.Call -> s.loginDeliveryCall(state.phoneNumber)
    CodeDelivery.MissedCall -> s.loginDeliveryMissedCall
    CodeDelivery.FlashCall -> s.loginDeliveryMissedCall
    CodeDelivery.Fragment -> s.loginDeliveryFragment
    CodeDelivery.SmsWord -> s.loginDeliveryWord
    CodeDelivery.SmsPhrase -> s.loginDeliveryPhrase
    CodeDelivery.Firebase -> s.loginDeliveryFirebase(state.phoneNumber)
    CodeDelivery.Unknown -> s.loginDeliveryUnknown(state.phoneNumber)
}

@Composable
private fun CodeStep(auth: WebAuth, state: AuthState.Code) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val word = state.delivery == CodeDelivery.SmsWord || state.delivery == CodeDelivery.SmsPhrase
    val wanted = if (state.length > 0) state.length else 5
    val submit = {
        if (!busy && code.isNotBlank()) {
            busy = true
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
            code = if (word) it else it.filter(Char::isDigit).take(wanted)
            if (!word && code.length == wanted) submit()
        },
        s.loginCode,
        if (word) KeyboardType.Text else KeyboardType.NumberPassword,
        error,
        { submit() },
    )
    Submit(s.loginSignIn, busy, s.loginSigningIn, code.isNotBlank()) { submit() }
    TextButton(onClick = { scope.launch { error = auth.resendCode() } }, modifier = Modifier.height(48.dp)) {
        Text(
            when (state.next) {
                CodeDelivery.Sms -> s.loginResendSms
                CodeDelivery.Call -> s.loginResendCall
                CodeDelivery.TelegramApp -> s.loginResendApp
                CodeDelivery.Fragment -> s.loginResendFragment
                else -> s.loginResendAgain
            },
        )
    }
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
    var error by remember { mutableStateOf<String?>(null) }
    val submit = {
        if (!busy && code.isNotBlank()) {
            busy = true
            scope.launch {
                error = auth.submitEmailCode(code)
                busy = false
            }
        }
    }
    Title(s.loginEmailCodeTitle, s.loginEmailCodeBody(state.pattern))
    Field(code, { code = it.filter(Char::isDigit) }, s.loginEmailCodeLabel, KeyboardType.NumberPassword, error, { submit() })
    Submit(s.loginSignIn, busy, s.loginSigningIn, code.isNotBlank()) { submit() }
    StartOver(auth)
}

@Composable
private fun PasswordStep(auth: WebAuth, state: AuthState.Password) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
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
    Field(password, { password = it }, s.loginPassword, KeyboardType.Password, error, { submit() }, password = true)
    Submit(s.loginSignIn, busy, s.loginSigningIn, password.isNotEmpty()) { submit() }
    StartOver(auth)
}

@Composable
private fun Failed(message: String, onStartOver: () -> Unit) {
    val s = LocalStrings.current
    Title(s.signinFailed, message)
    Button(onClick = onStartOver, modifier = Modifier.height(48.dp)) { Text(s.signinStartOver) }
}
