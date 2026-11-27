package com.linkme.androidbridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.linkme.androidbridge.core.protocol.BridgeState

@Composable
fun BridgeHomeScreen(
    bridgeState: BridgeState,
    peerName: String,
    batteryPercent: Int,
    queuedShareCount: Int,
    onWakeWifi: () -> Unit,
    onEnterCooldown: () -> Unit,
    onOpenPairing: () -> Unit
) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Link-Me AndroidBridge",
                style = MaterialTheme.typography.headlineSmall
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(text = "Paired Mac: $peerName", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = if (bridgeState == BridgeState.ACTIVE_WIFI_STREAM) "Wi-Fi Turbo" else "BLE Standby",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(text = "Battery Telemetry: $batteryPercent%", style = MaterialTheme.typography.bodySmall)
                    if (queuedShareCount > 0) {
                        Text(
                            text = "Queued Share Sheet files: $queuedShareCount",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    Text(
                        text = "Security: mTLS 1.3 · AndroidKeyStore Pinned",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onWakeWifi) {
                    Text("Wake Wi-Fi")
                }
                OutlinedButton(onClick = onEnterCooldown) {
                    Text("Cooldown")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(onClick = onOpenPairing, modifier = Modifier.fillMaxWidth()) {
                Text("Pair Mac via QR + 6-Digit SAS")
            }
        }
    }
}
