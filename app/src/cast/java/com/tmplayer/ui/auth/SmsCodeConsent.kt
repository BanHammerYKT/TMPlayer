package com.tmplayer.ui.auth

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import com.tmplayer.platform.Logger

/**
 * Fills the code from the text Telegram sends, with the viewer's say so: Google's SMS User
 * Consent. Play Services watches for one incoming text holding a code for five minutes and asks
 * "Allow TMPlayer to read this message?" before handing it over. No SMS permission is held and
 * no message is read without that answer; a "Deny" leaves the field to be typed as before.
 *
 * Only in the builds that carry Play Services already (the casting builds); the F-Droid build has
 * a version that does nothing ([active] is moot there). Asked only while [active], which the code
 * pane sets for a code that went by text.
 */
@Composable
fun SmsCodeConsent(active: Boolean, onMessage: (String) -> Unit) {
    if (!active) return
    val context = LocalContext.current
    val deliver by rememberUpdatedState(onMessage)
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringExtra(SmsRetriever.EXTRA_SMS_MESSAGE)?.let { deliver(it) }
        }
    }
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != SmsRetriever.SMS_RETRIEVED_ACTION) return
                val extras = intent.extras ?: return
                @Suppress("DEPRECATION")
                val status = extras.get(SmsRetriever.EXTRA_STATUS) as? Status ?: return
                if (status.statusCode != CommonStatusCodes.SUCCESS) return
                @Suppress("DEPRECATION")
                val ask = extras.getParcelable<Intent>(SmsRetriever.EXTRA_CONSENT_INTENT) ?: return
                runCatching { consent.launch(ask) }.onFailure { Logger.w(TAG, "consent prompt failed: ${it.message}") }
            }
        }
        val started = runCatching {
            SmsRetriever.getClient(context).startSmsUserConsent(null)
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION),
                SmsRetriever.SEND_PERMISSION,
                null,
                ContextCompat.RECEIVER_EXPORTED,
            )
        }.onFailure { Logger.w(TAG, "SMS User Consent unavailable: ${it.message}") }.isSuccess
        onDispose { if (started) runCatching { context.unregisterReceiver(receiver) } }
    }
}

private const val TAG = "SmsCodeConsent"
