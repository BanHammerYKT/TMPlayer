package com.tmplayer.web.td

import com.tmplayer.data.AuthAction
import com.tmplayer.data.AuthFlow
import com.tmplayer.data.AuthState
import com.tmplayer.data.CodeDelivery
import com.tmplayer.data.CodeInfo
import com.tmplayer.data.Country
import com.tmplayer.data.CountryRow
import com.tmplayer.data.EmailReset
import com.tmplayer.data.Failures
import com.tmplayer.data.PhoneCountries
import com.tmplayer.data.SignInWaits
import com.tmplayer.data.SignInMethod
import com.tmplayer.data.TdAuthorization
import com.tmplayer.i18n.L
import com.tmplayer.web.BuildInfo
import com.tmplayer.web.js.TmHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Signing in, in the browser: TDLib's authorization updates read off tdweb's JSON, reduced by the
 * same [AuthFlow] the apps use, and the actions it asks for carried out here. Mirrors the apps'
 * `Td` (core/src/commonMain/.../Td.kt): the QR route by default, the phone route on request, a
 * sign in started over by logging the half signed in client out.
 */
class WebAuth(private val td: WebTd, private val scope: CoroutineScope) {

    private val _state = MutableStateFlow<AuthState>(AuthState.Connecting)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    /** True once another tab has taken TDLib over (tdweb allows one client per origin). */
    private val _elsewhere = MutableStateFlow(false)
    val elsewhere: StateFlow<Boolean> = _elsewhere.asStateFlow()

    /** Still Connecting after a while: the screen says what is likely wrong and offers a retry. */
    private val _stalled = MutableStateFlow(false)
    val stalled: StateFlow<Boolean> = _stalled.asStateFlow()

    /** Called once the account is signed in, and again after each new client signs in. */
    var onReady: () -> Unit = {}

    /** Called when the account signs out, so the session's data can be dropped. */
    var onSignedOut: () -> Unit = {}

    private var method = SignInMethod.Qr
    private var last: TdAuthorization? = null
    private var closingOnPurpose = false
    private var wasReady = false
    private var stallJob: Job? = null

    init {
        td.onUpdate { update ->
            if (update.type == "updateAuthorizationState") {
                update.obj("authorization_state")?.let { onState(parse(it)) }
            }
        }
    }

    fun start() {
        _elsewhere.value = false
        td.start()
        watchStall()
    }

    private fun onState(state: TdAuthorization) {
        if (state == last && state !is TdAuthorization.WaitOtherDevice) return
        last = state
        apply(state)
    }

    private fun apply(state: TdAuthorization) {
        val step = AuthFlow.reduce(state, method)
        if (_state.value !is AuthState.Failed || step.state !is AuthState.Connecting) {
            _state.value = step.state
        }
        watchStall()
        when (step.action) {
            AuthAction.SendParameters -> scope.launch { sendParameters() }
            AuthAction.RequestQrCode -> scope.launch {
                runCatching {
                    td.call("requestQrCodeAuthentication") { putJsonArray("other_user_ids") {} }
                }.onFailure { e ->
                    if (_state.value is AuthState.Connecting) _state.value = AuthState.Failed(humanise(e))
                }
            }
            AuthAction.OnReady -> {
                wasReady = true
                scope.launch { onReadyOptions() }
                onReady()
            }
            AuthAction.RecreateClient -> {
                val wanted = closingOnPurpose
                closingOnPurpose = false
                last = null
                if (wasReady) {
                    wasReady = false
                    onSignedOut()
                }
                if (wanted) {
                    scope.launch {
                        // tdweb terminates the old worker on Closed; give it a moment to let go
                        // of the database before the new client opens it.
                        delay(300)
                        start()
                    }
                } else {
                    // Closed without being asked: another TMPlayer tab took TDLib over.
                    _elsewhere.value = true
                }
            }
            AuthAction.Close -> scope.launch { td.close() }
            AuthAction.None -> Unit
        }
    }

    private fun watchStall() {
        stallJob?.cancel()
        _stalled.value = false
        if (_state.value is AuthState.Connecting) {
            stallJob = scope.launch {
                delay(STALL_MS)
                if (_state.value is AuthState.Connecting) _stalled.value = true
            }
        }
    }

    /** This tab takes TDLib back from the other one. */
    fun useHere() {
        _elsewhere.value = false
        _state.value = AuthState.Connecting
        start()
    }

    private suspend fun sendParameters() {
        if (BuildInfo.TG_API_ID == 0 || BuildInfo.TG_API_HASH.isBlank()) {
            _state.value = AuthState.Failed(L.errorsNoApiCredentials)
            return
        }
        runCatching {
            td.call("setTdlibParameters") {
                put("use_test_dc", false)
                // tdweb sets its own directories and database switches over these.
                put("database_directory", "")
                put("files_directory", "")
                put("database_encryption_key", "")
                put("use_file_database", true)
                put("use_chat_info_database", true)
                put("use_message_database", true)
                put("use_secret_chats", false)
                put("api_id", BuildInfo.TG_API_ID)
                put("api_hash", BuildInfo.TG_API_HASH)
                put("system_language_code", TmHost.language().substringBefore('-').ifBlank { "en" })
                put("device_model", deviceModel())
                put("system_version", "")
                put("application_version", BuildInfo.VERSION)
            }
        }.onFailure { e ->
            _state.value = AuthState.Failed(L.errorsTdlibParameters(e.message ?: ""))
        }
    }

    private suspend fun onReadyOptions() {
        for ((name, value) in listOf(
            "ignore_inline_thumbnails" to false,
            "disable_animated_emoji" to true,
        )) {
            td.tryCall("setOption") {
                put("name", name)
                putJsonObject("value") {
                    put("@type", "optionValueBoolean")
                    put("value", value)
                }
            }
        }
    }

    /** Switches the route. A QR code already up has to be abandoned first, as in the apps. */
    fun choose(chosen: SignInMethod) {
        if (chosen == SignInMethod.Phone && _state.value is AuthState.Qr) {
            restart(SignInMethod.Phone)
            return
        }
        method = chosen
        last?.let { apply(it) }
    }

    /** Throws a half finished sign in away and starts again with [next]. */
    fun restart(next: SignInMethod = SignInMethod.Qr) {
        method = next
        closingOnPurpose = true
        _state.value = AuthState.Connecting
        scope.launch {
            if (td.tryCall("logOut") == null) {
                // A client that cannot log out (none yet, or a broken one) is closed instead.
                td.close()
            }
        }
    }

    /** A new client over the same database, for a sign in stuck on Connecting. */
    fun retry() {
        _stalled.value = false
        closingOnPurpose = true
        scope.launch { td.close() }
        // If TDLib never reports the old client closed, start a new one anyway.
        scope.launch {
            delay(4_000)
            if (!td.started) start()
        }
    }

    fun signOut() {
        closingOnPurpose = true
        _state.value = AuthState.Connecting
        scope.launch { td.tryCall("logOut") }
    }

    suspend fun submitPhone(number: String): String? = attempt({ _state.value = AuthState.Phone(wrong = true) }) {
        td.call("setAuthenticationPhoneNumber") { put("phone_number", number.trim()) }
    }

    suspend fun submitCode(code: String): String? {
        val asked = _state.value as? AuthState.Code ?: AuthState.Code("")
        return attempt({ _state.value = asked.copy(wrong = true) }) {
            td.call("checkAuthenticationCode") { put("code", code.trim()) }
        }
    }

    suspend fun resendCode(): String? = attempt({}) { td.call("resendAuthenticationCode") }

    suspend fun submitEmail(address: String): String? = attempt({ _state.value = AuthState.EmailAddress(wrong = true) }) {
        td.call("setAuthenticationEmailAddress") { put("email_address", address.trim()) }
    }

    suspend fun submitEmailCode(code: String): String? {
        val asked = _state.value as? AuthState.EmailCode ?: AuthState.EmailCode("")
        return attempt({ _state.value = asked.copy(wrong = true) }) {
            td.call("checkAuthenticationEmailCode") {
                putJsonObject("code") {
                    put("@type", "emailAddressAuthenticationCode")
                    put("code", code.trim())
                }
            }
        }
    }

    /** Telegram's countries with their dial codes, for the picker; empty until TDLib answers. */
    suspend fun countries(): List<Country> {
        val r = td.tryCall("getCountries") ?: return emptyList()
        return PhoneCountries.fromRows(
            r.objects("countries").map {
                CountryRow(it.str("country_code"), it.str("name"), it.arr("calling_codes").map { c -> (c as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty() }, it.bool("is_hidden"))
            },
        )
    }

    /** The country TDLib guesses from the connection, as an ISO code, or "". */
    suspend fun guessedCountry(): String = td.tryCall("getCountryCode")?.str("text").orEmpty()

    /** TDLib's layout for [national] after [dial] ("+44"), with "-" for digits to come; see PhoneFormat. */
    suspend fun formatPhone(dial: String, national: String): String? {
        if (dial.isEmpty()) return null
        val r = td.tryCall("getPhoneNumberInfo") { put("phone_number_prefix", "+$dial$national") } ?: return null
        return r.str("formatted_phone_number").takeIf { r.str("country_calling_code") == dial && it.isNotBlank() }
    }

    /** "Forgot password": Telegram mails a recovery code to the account's recovery address. */
    suspend fun forgotPassword(): String? {
        val asked = _state.value as? AuthState.Password ?: return null
        val pattern = asked.recoveryEmail ?: return L.loginRecoveryNoEmail
        return attempt({}) {
            td.call("requestAuthenticationPasswordRecovery")
            _state.value = AuthState.PasswordRecovery(pattern)
        }
    }

    suspend fun submitRecoveryCode(code: String): String? {
        val asked = _state.value as? AuthState.PasswordRecovery ?: return null
        val clean = code.trim()
        return attempt({ _state.value = asked.copy(wrong = true) }) {
            td.call("checkAuthenticationPasswordRecoveryCode") { put("recovery_code", clean) }
            _state.value = asked.copy(code = clean, wrong = false)
        }
    }

    /** A blank [newPassword] turns two-step verification off, which the screen says first. */
    suspend fun finishRecovery(newPassword: String, hint: String): String? {
        val asked = _state.value as? AuthState.PasswordRecovery ?: return null
        val code = asked.code ?: return null
        return attempt({}) {
            td.call("recoverAuthenticationPassword") {
                put("recovery_code", code)
                put("new_password", newPassword)
                put("new_hint", if (newPassword.isEmpty()) "" else hint.trim())
            }
        }
    }

    /** Back from the recovery to the password question, which TDLib never left. */
    fun backToPassword() {
        if (_state.value !is AuthState.PasswordRecovery) return
        last?.let { apply(it) }
    }

    /**
     * Telegram's own account reset, for somebody with neither the password nor a recovery email:
     * deletes the account's chats and messages. Only called after the second confirmation.
     */
    suspend fun resetAccount(): String? {
        if (_state.value !is AuthState.Password) return null
        return try {
            td.call("deleteAccount") {
                put("reason", "Forgot password")
                put("password", "")
            }
            null
        } catch (e: TdError) {
            SignInWaits.resetWaitSeconds(e.message)?.let { L.loginResetAccountWait(Failures.waitPhrase(it)) } ?: humanise(e)
        }
    }

    /** Asks Telegram to reset the login email ([EmailReset.Available]). */
    suspend fun resetEmail(): String? = attempt({}) { td.call("resetAuthenticationEmailAddress") }

    suspend fun submitPassword(password: String): String? {
        val hint = (_state.value as? AuthState.Password)?.hint.orEmpty()
        return attempt({ _state.value = AuthState.Password(hint, wrong = true) }) {
            td.call("checkAuthenticationPassword") { put("password", password) }
        }
    }

    private suspend fun attempt(onWrong: () -> Unit, block: suspend () -> Unit): String? =
        try {
            block()
            null
        } catch (e: TdError) {
            onWrong()
            humanise(e)
        } catch (e: Throwable) {
            humanise(e)
        }

    companion object {
        private const val STALL_MS = 20_000L

        /** tdweb's authorization state JSON as the shared flow reads it. */
        fun parse(o: JsonObject): TdAuthorization = when (o.type) {
            "authorizationStateWaitTdlibParameters" -> TdAuthorization.WaitParameters
            "authorizationStateWaitPhoneNumber" -> TdAuthorization.WaitPhoneNumber
            "authorizationStateWaitCode" -> TdAuthorization.WaitCode(codeInfo(o.obj("code_info") ?: JsonObject(emptyMap())))
            "authorizationStateWaitOtherDeviceConfirmation" -> TdAuthorization.WaitOtherDevice(o.str("link"))
            "authorizationStateWaitPassword" -> TdAuthorization.WaitPassword(
                hint = o.str("password_hint"),
                recoveryEmail = o.str("recovery_email_address_pattern").takeIf { o.bool("has_recovery_email_address") },
            )
            "authorizationStateWaitEmailAddress" -> TdAuthorization.WaitEmailAddress
            "authorizationStateWaitEmailCode" -> o.obj("code_info").let {
                TdAuthorization.WaitEmailCode(it?.str("email_address_pattern").orEmpty(), it?.int("length") ?: 0, emailReset(o.obj("email_address_reset_state")))
            }
            "authorizationStateWaitRegistration" -> TdAuthorization.WaitRegistration
            "authorizationStateWaitPremiumPurchase" -> TdAuthorization.WaitPremiumPurchase
            "authorizationStateReady" -> TdAuthorization.Ready
            "authorizationStateLoggingOut" -> TdAuthorization.LoggingOut
            "authorizationStateClosing" -> TdAuthorization.Closing
            "authorizationStateClosed" -> TdAuthorization.Closed
            else -> TdAuthorization.Unknown
        }

        fun codeInfo(info: JsonObject): CodeInfo {
            val type = info.obj("type")
            return CodeInfo(
                phoneNumber = info.str("phone_number"),
                delivery = delivery(type),
                length = type?.int("length") ?: 0,
                timeout = info.int("timeout"),
                next = info.obj("next_type")?.let(::delivery),
                hasNext = info.obj("next_type") != null,
            )
        }

        fun emailReset(o: JsonObject?): EmailReset = when (o?.type) {
            "emailAddressResetStateAvailable" -> EmailReset.Available(o.int("wait_period"))
            "emailAddressResetStatePending" -> EmailReset.Pending(o.int("reset_in"))
            else -> EmailReset.None
        }

        fun delivery(type: JsonObject?): CodeDelivery = when (type?.type) {
            "authenticationCodeTypeTelegramMessage" -> CodeDelivery.TelegramApp
            "authenticationCodeTypeSms" -> CodeDelivery.Sms
            "authenticationCodeTypeCall" -> CodeDelivery.Call
            "authenticationCodeTypeMissedCall" -> CodeDelivery.MissedCall
            "authenticationCodeTypeFlashCall" -> CodeDelivery.FlashCall
            "authenticationCodeTypeFragment" -> CodeDelivery.Fragment
            "authenticationCodeTypeSmsWord" -> CodeDelivery.SmsWord
            "authenticationCodeTypeSmsPhrase" -> CodeDelivery.SmsPhrase
            "authenticationCodeTypeFirebaseAndroid", "authenticationCodeTypeFirebaseIos" -> CodeDelivery.Firebase
            else -> CodeDelivery.Unknown
        }

        /** The browser, as Telegram's list of devices shows it ("Chrome on Windows"). */
        fun deviceModel(): String {
            val ua = TmHost.userAgent()
            val browser = when {
                "Edg/" in ua -> "Edge"
                "Firefox/" in ua -> "Firefox"
                "CriOS" in ua || "Chrome/" in ua || "Chromium/" in ua -> "Chrome"
                "Safari/" in ua -> "Safari"
                else -> "Browser"
            }
            val os = when (TmHost.platform()) {
                "android" -> "Android"
                "ios" -> if ("iPad" in ua) "iPad" else "iPhone"
                "windows" -> "Windows"
                "macos" -> "macOS"
                "linux" -> "Linux"
                else -> ""
            }
            return if (os.isEmpty()) "Web ($browser)" else "Web ($browser on $os)" // i18n-ok: the device name Telegram lists, not UI
        }

        fun humanise(e: Throwable): String {
            val message = e.message.orEmpty()
            return when {
                "PHONE_NUMBER_INVALID" in message -> L.errorsPhoneInvalid
                "PHONE_CODE_INVALID" in message || "CODE_INVALID" in message -> L.errorsWrongCode
                "PHONE_CODE_EXPIRED" in message || "CODE_EXPIRED" in message -> L.errorsCodeExpired
                "PASSWORD_HASH_INVALID" in message -> L.errorsWrongPassword
                "EMAIL_INVALID" in message -> L.errorsEmailInvalid
                message.isBlank() -> L.errorsNotConnected
                else -> Failures.humanise(message)
            }
        }
    }
}
