package com.tmplayer.web.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tmplayer.web.WebAppState
import com.tmplayer.web.js.TmHost
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The offer to install the web app, for a browser tab only. iOS has no install prompt, so an
 * iPhone or iPad is told how to add it to the Home Screen (where it opens full screen and keeps
 * its storage, notifications and lock screen controls); Chromium's own prompt is offered behind
 * an Install button once the browser says the app can be installed. Dismissed for good.
 */
@Composable
fun InstallBanner(app: WebAppState) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    var dismissed by remember { mutableStateOf(app.prefs.string("installDismissed") == "1" || TmHost.isStandalone()) }
    val ios = remember { TmHost.isIos() }
    val canInstall by produceState(TmHost.canInstall()) {
        while (!value) {
            delay(3000)
            value = TmHost.canInstall()
        }
    }
    if (dismissed || (!ios && !canInstall)) return
    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Text(s.webInstallTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (ios) {
            Spacer(Modifier.height(4.dp))
            Text(s.webInstallIos, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!ios) {
                Button(onClick = {
                    scope.launch {
                        if (TmHost.install().await<kotlin.js.JsBoolean>().toBoolean()) dismissed = true
                    }
                }, modifier = Modifier.height(44.dp)) { Text(s.webInstallButton) }
            }
            TextButton(onClick = {
                dismissed = true
                app.prefs.setString("installDismissed", "1")
            }, modifier = Modifier.height(44.dp)) { Text(s.webInstallLater) }
        }
    }
}
