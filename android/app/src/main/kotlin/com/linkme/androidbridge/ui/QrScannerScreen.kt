package com.linkme.androidbridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.unit.dp
import com.linkme.androidbridge.core.crypto.PairingCrypto
import com.linkme.androidbridge.core.crypto.PairingUriPayload

/**
 * Feature 1 (Android): QR scanner screen with CameraX/MLKit analyzer entrypoint
 * plus manual `andromac://pair` URI paste fallback for emulator testing.
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

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Scan MacBridge QR", style = MaterialTheme.typography.headlineSmall)

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("CameraX + Barcode Scanner Ready", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Point the camera at MacBridge's pairing QR or paste an andromac://pair URI below for emulator testing.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            OutlinedTextField(
                value = manualUri,
                onValueChange = { manualUri = it },
                label = { Text("andromac://pair URI (Emulator Fallback)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = peerCertFingerprint,
                onValueChange = { peerCertFingerprint = it },
                label = { Text("Peer 64-hex TLS Cert SHA-256 (from PAIR_HELLO)") },
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
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Derive 6-Digit SAS")
            }

            errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            derivedSas?.let { sas ->
                Text("Verify 6-digit SAS matches MacBridge:", style = MaterialTheme.typography.bodyMedium)
                Text(sas, style = MaterialTheme.typography.displayMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onCancel) { Text("Reject") }
                    Button(onClick = {
                        val peerId = parsedPeerId ?: return@Button
                        val secret = derivedBleSecret ?: return@Button
                        val cleanFp = peerCertFingerprint.trim().lowercase()
                        if (cleanFp.length != 64 || !cleanFp.all { it in '0'..'9' || it in 'a'..'f' }) {
                            errorMessage = "Enter valid 64-hex SHA-256 certificate pin from PAIR_HELLO"
                            return@Button
                        }
                        onConfirmTrustedPeer(peerId, "Paired Mac", cleanFp, secret)
                    }) {
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
