package com.linkme.androidbridge.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.linkme.androidbridge.core.crypto.PairingCrypto
import com.linkme.androidbridge.core.crypto.PairingUriPayload
import com.linkme.androidbridge.ui.theme.WarmIndustrialPalette

/**
 * Feature 1 (Android): Warm Industrial QR pairing & 6-digit SAS confirmation screen.
 */
@Composable
fun QrScannerScreen(
    onConfirmTrustedPeer: (
        deviceId: String,
        deviceName: String,
        pinnedCertSha256: String,
        sharedBleSecret: ByteArray
    ) -> Unit,
    onCancel: () -> Unit
) {
    var manualUri by remember { mutableStateOf("") }
    var peerCertFingerprint by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var derivedSas by remember { mutableStateOf<String?>(null) }
    var parsedPeerId by remember { mutableStateOf<String?>(null) }
    var derivedBleSecret by remember { mutableStateOf<ByteArray?>(null) }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "CURVE25519 // QR PAIRING",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = WarmIndustrialPalette.CrimsonAccent,
                fontWeight = FontWeight.Bold
            )
            Text("Scan MacBridge QR", style = MaterialTheme.typography.headlineSmall)

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Out-of-Band X25519 Bootstrap", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Scan MacBridge's pairing QR or paste an andromac://pair URI below for emulator verification.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            OutlinedTextField(
                value = manualUri,
                onValueChange = { manualUri = it },
                label = { Text("andromac://pair URI") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = peerCertFingerprint,
                onValueChange = { peerCertFingerprint = it },
                label = { Text("Peer 64-hex TLS Cert SHA-256") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Button(
                onClick = {
                    runCatching {
                        val parsed = PairingUriPayload.parse(manualUri.trim())
                        val crypto = PairingCrypto()
                        val nonce = decodeHex(parsed.nonceHex)
                        val derived = crypto.deriveSecrets(parsed.publicKeyBase64Url, nonce)
                        parsedPeerId = parsed.deviceId
                        derivedSas = derived.sasCode
                        derivedBleSecret = derived.sharedBleSecret
                        errorMessage = null
                    }.onFailure { err ->
                        errorMessage = err.message ?: "Invalid pairing URI"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = WarmIndustrialPalette.CrimsonAccent,
                    contentColor = Color.White
                )
            ) {
                Text("Derive 6-Digit SAS")
            }

            errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            derivedSas?.let { sas ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, WarmIndustrialPalette.CrimsonAccent)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("VERIFY MATCHING SAS ON MAC", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        Text(
                            text = sas,
                            style = MaterialTheme.typography.displayMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onCancel) { Text("Reject") }
                    Button(
                        onClick = {
                            val peerId = parsedPeerId ?: return@Button
                            val secret = derivedBleSecret ?: return@Button
                            val cleanFp = peerCertFingerprint.trim().lowercase()
                            if (cleanFp.length != 64 || !cleanFp.all { it in '0'..'9' || it in 'a'..'f' }) {
                                errorMessage = "Enter valid 64-hex SHA-256 certificate pin from PAIR_HELLO"
                                return@Button
                            }
                            onConfirmTrustedPeer(peerId, "Paired Mac", cleanFp, secret)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = WarmIndustrialPalette.CrimsonAccent,
                            contentColor = Color.White
                        )
                    ) {
                        Text("Confirm & Trust")
                    }
                }
            } ?: OutlinedButton(onClick = onCancel) {
                Text("Cancel")
            }
        }
    }
}

private fun decodeHex(hex: String): ByteArray =
    ByteArray(hex.length / 2) { idx ->
        hex.substring(idx * 2, idx * 2 + 2).toInt(16).toByte()
    }
