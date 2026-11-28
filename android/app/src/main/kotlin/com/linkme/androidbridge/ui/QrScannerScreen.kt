package com.linkme.androidbridge.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlashlightOff
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.LaptopMac
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.linkme.androidbridge.core.crypto.IdentityManager
import com.linkme.androidbridge.core.crypto.PairingCrypto
import com.linkme.androidbridge.core.crypto.PairingUriPayload
import com.linkme.androidbridge.core.prefs.BridgePreferencesStore
import com.linkme.androidbridge.core.protocol.PairCommitPayload
import com.linkme.androidbridge.core.protocol.PairHelloPayload
import com.linkme.androidbridge.core.protocol.PairingCommitMac
import com.linkme.androidbridge.service.BridgeActivityHub
import com.linkme.androidbridge.service.PairingCommitStatus
import com.linkme.androidbridge.service.PendingPairingCandidate
import com.linkme.androidbridge.service.TelemetryFeature
import com.linkme.androidbridge.ui.theme.LocalBridgePalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

private data class ParsedPairingState(
    val peerId: String,
    val peerName: String,
    val sasCode: String,
    val pinnedCertSha256: String,
    val sharedBleSecret: ByteArray,
    val sessionKey: ByteArray,
    val pairToken: String,
    val lanHost: String?,
    val tcpPort: Int,
    val localDeviceId: String,
    val localDeviceName: String,
    val localEphemeralPublicKey: String,
    val localSpkiSha256: String
)

/**
 * Live CameraX + MLKit QR pairing & 6-digit SAS verification screen matching Liquid Continuity design.
 * Waits for bidirectional PAIR_ACK from macOS before trusting peer in TrustStore.
 */
@Composable
fun QrScannerScreen(
    onConfirmTrustedPeer: (
        deviceId: String,
        deviceName: String,
        pinnedCertSha256: String,
        sharedBleSecret: ByteArray,
        bluetoothSessionKey: ByteArray,
        onPersisted: () -> Unit
    ) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val pal = LocalBridgePalette.current
    val scope = rememberCoroutineScope()
    val pairingStatus by BridgeActivityHub.pairingStatus.collectAsState()
    val pairingTrace by BridgeActivityHub.pairingTrace.collectAsState()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var torchEnabled by remember { mutableStateOf(false) }
    var showManualFallback by remember { mutableStateOf(false) }
    var manualUri by remember { mutableStateOf("") }
    var manualFingerprintOverride by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var qrDetectedBadge by remember { mutableStateOf(false) }
    var parsedPairing by remember { mutableStateOf<ParsedPairingState?>(null) }
    var cameraRef by remember { mutableStateOf<Camera?>(null) }

    LaunchedEffect(pairingStatus) {
        when (val st = pairingStatus) {
            is PairingCommitStatus.Connected -> {
                onCancel()
            }
            is PairingCommitStatus.Failed -> {
                errorMessage = st.reason
            }
            else -> Unit
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    fun processScannedUri(rawUri: String) {
        val trimmed = rawUri.trim()
        if (trimmed.isEmpty()) return
        scope.launch {
            runCatching {
                val parsed = PairingUriPayload.parse(trimmed)
                val crypto = PairingCrypto()
                val nonce = decodeHex(parsed.nonceHex)
                val derived = crypto.deriveSecrets(parsed.publicKeyBase64Url, nonce)

                val localIdentity = withContext(Dispatchers.IO) {
                    IdentityManager(useAndroidKeyStore = true).loadOrCreateIdentity()
                }
                val prefsAlias = BridgePreferencesStore.forContext(context).state.value.deviceAlias
                val hardwareModel = if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) {
                    Build.MODEL
                } else {
                    "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
                }
                val advertisedDeviceName = if (prefsAlias.isBlank() || prefsAlias == "AndroidBridge") {
                    hardwareModel
                } else {
                    "$prefsAlias ($hardwareModel)"
                }
                val resolvedFp = if (parsed.hasFullCertFingerprint) {
                    parsed.certSha256Prefix.lowercase()
                } else {
                    manualFingerprintOverride.trim().lowercase().ifEmpty { parsed.certSha256Prefix.lowercase() }
                }

                val state = ParsedPairingState(
                    peerId = parsed.deviceId,
                    peerName = parsed.deviceName ?: "MacBook Pro",
                    sasCode = derived.sasCode,
                    pinnedCertSha256 = resolvedFp,
                    sharedBleSecret = derived.sharedBleSecret,
                    sessionKey = derived.sessionKey,
                    pairToken = parsed.pairToken,
                    lanHost = parsed.lanHost,
                    tcpPort = parsed.tcpPort,
                    localDeviceId = localIdentity.deviceId,
                    localDeviceName = advertisedDeviceName,
                    localEphemeralPublicKey = crypto.publicKeyBase64Url,
                    localSpkiSha256 = localIdentity.certificateSha256Hex
                )
                if (resolvedFp.length == 64 && resolvedFp.all { it in '0'..'9' || it in 'a'..'f' }) {
                    val snap = TelemetryFeature(localIdentity.deviceId).readCurrentBattery(context)
                    val macHex = PairingCommitMac.computeHex(
                        sharedBleSecret = derived.sharedBleSecret,
                        deviceId = localIdentity.deviceId,
                        spkiSha256 = localIdentity.certificateSha256Hex,
                        ephemeralPublicKey = crypto.publicKeyBase64Url
                    )
                    val commit = PairCommitPayload(
                        deviceId = localIdentity.deviceId,
                        deviceName = advertisedDeviceName,
                        platform = "android",
                        ephemeralPublicKey = crypto.publicKeyBase64Url,
                        spkiSha256 = localIdentity.certificateSha256Hex,
                        sasMacHex = macHex,
                        batteryLevel = snap.level ?: 0,
                        isCharging = snap.isCharging,
                        pairToken = parsed.pairToken
                    )
                    val candidate = PendingPairingCandidate(
                        peerId = state.peerId,
                        peerName = state.peerName,
                        pinnedCertSha256 = resolvedFp,
                        sharedBleSecret = derived.sharedBleSecret,
                        bluetoothSessionKey = derived.sessionKey,
                        pairToken = parsed.pairToken
                    )
                    BridgeActivityHub.onStartPairingExchange?.invoke(candidate, commit, parsed.lanHost, parsed.tcpPort)
                }
                state
            }.onSuccess { result ->
                qrDetectedBadge = true
                errorMessage = null
                parsedPairing = result
            }.onFailure { err ->
                errorMessage = err.message ?: "Invalid pairing URI"
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Camera Viewfinder Card (matches android_qr_scanner_verification)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(310.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(pal.surfaceLowest),
            contentAlignment = Alignment.Center
        ) {
            if (hasCameraPermission && parsedPairing == null) {
                CameraXQrPreview(
                    onQrScanned = { scannedText ->
                        if ((scannedText.startsWith("linkme://", ignoreCase = true) ||
                                scannedText.startsWith("andromac://", ignoreCase = true)) &&
                            parsedPairing == null
                        ) {
                            processScannedUri(scannedText)
                        }
                    },
                    onCameraBound = { cam ->
                        cameraRef = cam
                        cam.cameraControl.enableTorch(torchEnabled)
                    }
                )
            } else if (!hasCameraPermission) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(20.dp)
                ) {
                    Text("Camera permission needed to scan MacBridge QR", color = pal.onSurface, fontSize = 13.sp)
                    Button(
                        onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = pal.primaryAccent)
                    ) {
                        Text("Grant Camera Permission", color = Color.White, fontSize = 12.sp)
                    }
                }
            }

            // Subtle dark scrim + corner reticle overlay
            Canvas(modifier = Modifier.size(180.dp)) {
                val strokeWidth = 3.5.dp.toPx()
                val cornerLen = 28.dp.toPx()
                val r = 14.dp.toPx()
                val c = pal.activeCardLight

                // Top-left
                drawPath(
                    Path().apply {
                        moveTo(0f, cornerLen)
                        lineTo(0f, r)
                        quadraticBezierTo(0f, 0f, r, 0f)
                        lineTo(cornerLen, 0f)
                    },
                    color = c,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
                // Top-right
                drawPath(
                    Path().apply {
                        moveTo(size.width - cornerLen, 0f)
                        lineTo(size.width - r, 0f)
                        quadraticBezierTo(size.width, 0f, size.width, r)
                        lineTo(size.width, cornerLen)
                    },
                    color = c,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
                // Bottom-right
                drawPath(
                    Path().apply {
                        moveTo(size.width, size.height - cornerLen)
                        lineTo(size.width, size.height - r)
                        quadraticBezierTo(size.width, size.height, size.width - r, size.height)
                        lineTo(size.width - cornerLen, size.height)
                    },
                    color = c,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
                // Bottom-left
                drawPath(
                    Path().apply {
                        moveTo(cornerLen, size.height)
                        lineTo(r, size.height)
                        quadraticBezierTo(0f, size.height, 0f, size.height - r)
                        lineTo(0f, size.height - cornerLen)
                    },
                    color = c,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
                // Scan beam
                drawLine(
                    color = pal.primaryAccent,
                    start = Offset(16.dp.toPx(), size.height / 2f),
                    end = Offset(size.width - 16.dp.toPx(), size.height / 2f),
                    strokeWidth = 2.dp.toPx()
                )
            }

            // Flashlight toggle button top-right
            if (hasCameraPermission) {
                IconButton(
                    onClick = {
                        torchEnabled = !torchEnabled
                        cameraRef?.cameraControl?.enableTorch(torchEnabled)
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .clip(CircleShape)
                        .background(pal.surfaceContainer.copy(alpha = 0.85f))
                ) {
                    Icon(
                        imageVector = if (torchEnabled) Icons.Rounded.FlashlightOn else Icons.Rounded.FlashlightOff,
                        contentDescription = "Toggle Flashlight",
                        tint = if (torchEnabled) pal.primaryAccent else pal.onSurface
                    )
                }
            }

            // Bottom overlay pill + Manual fallback drawer trigger
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(pal.surfaceHigh.copy(alpha = 0.9f))
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (qrDetectedBadge) pal.secondaryAccent else pal.primaryAccent)
                    )
                    Text(
                        text = if (qrDetectedBadge) "QR Detected" else "Point camera at MacBridge QR",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = pal.onSurface
                    )
                }

                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(pal.surfaceContainer.copy(alpha = 0.9f))
                        .clickable { showManualFallback = !showManualFallback }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Rounded.Key, contentDescription = null, tint = pal.activeCardLight, modifier = Modifier.size(15.dp))
                    Text("Manual Pairing URI / SAS Fallback", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = pal.onSurface)
                }
            }
        }

        // Collapsible Manual / Paste URI Drawer
        AnimatedVisibility(visible = showManualFallback) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = pal.surfaceContainer),
                border = BorderStroke(1.dp, pal.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "MANUAL PAIRING URI FALLBACK",
                        fontSize = 10.sp,
                        letterSpacing = 0.6.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = pal.secondaryText
                    )
                    OutlinedTextField(
                        value = manualUri,
                        onValueChange = { manualUri = it },
                        label = { Text("linkme://pair?... or andromac://pair?...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = pal.surfaceLowest,
                            unfocusedContainerColor = pal.surfaceLowest,
                            focusedTextColor = pal.onSurface,
                            unfocusedTextColor = pal.onSurface,
                            focusedBorderColor = pal.primaryAccent,
                            unfocusedBorderColor = pal.outlineVariant
                        )
                    )
                    OutlinedTextField(
                        value = manualFingerprintOverride,
                        onValueChange = { manualFingerprintOverride = it },
                        label = { Text("Optional 64-hex Cert Pin (for legacy URIs)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = pal.surfaceLowest,
                            unfocusedContainerColor = pal.surfaceLowest,
                            focusedTextColor = pal.onSurface,
                            unfocusedTextColor = pal.onSurface,
                            focusedBorderColor = pal.primaryAccent,
                            unfocusedBorderColor = pal.outlineVariant
                        )
                    )
                    Button(
                        onClick = { processScannedUri(manualUri) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = pal.primaryAccent)
                    ) {
                        Text("Parse URI & Derive SAS", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        errorMessage?.let {
            Text(it, color = Color(0xFFFF453A), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }

        // Security Verification Sheet (Step 4 matching screen.png)
        val active = parsedPairing
        if (active != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = pal.surfaceLow),
                border = BorderStroke(1.dp, pal.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(pal.primaryAccent.copy(alpha = 0.18f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.VerifiedUser, contentDescription = null, tint = pal.activeCardLight)
                            }
                            Column {
                                Text(
                                    "SECURITY VERIFICATION",
                                    fontSize = 10.sp,
                                    letterSpacing = 0.7.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = pal.primaryAccent,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "Verify Pairing Code",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = pal.onSurface
                                )
                            }
                        }

                        Row(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(pal.surfaceHigh)
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(Icons.Rounded.LaptopMac, contentDescription = null, tint = pal.secondaryAccent, modifier = Modifier.size(14.dp))
                            Text(active.peerName, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = pal.onSurface)
                        }
                    }

                    // 6-digit SAS card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = pal.surfaceContainer)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "SHORT AUTHENTICATION STRING (SAS)",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = pal.secondaryText
                            )
                            val formattedSas = if (active.sasCode.length == 6) {
                                "${active.sasCode.substring(0, 3)} ${active.sasCode.substring(3, 6)}"
                            } else {
                                active.sasCode
                            }
                            Text(
                                text = formattedSas,
                                fontSize = 34.sp,
                                letterSpacing = 4.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = pal.activeCardLight
                            )
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                Icon(Icons.Rounded.Lock, contentDescription = null, tint = pal.secondaryAccent, modifier = Modifier.size(14.dp))
                                Text(
                                    "End-to-End Encrypted • ChaCha20 BLE + TLS 1.3",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = pal.secondaryText
                                )
                            }
                        }
                    }

                    // Live 4-step handshake trace (1. QR Decoded -> 2. Radio Linked -> 3. Key Exchange -> 4. Paired & Synced)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(pal.surfaceLowest)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        val steps = listOf(
                            1 to "1. QR Decoded",
                            2 to "2. Radio Linked (BLE / Wi-Fi)",
                            3 to "3. Key Exchange",
                            4 to "4. Paired & Synced"
                        )
                        steps.forEach { (num, label) ->
                            val isDone = pairingTrace.step > num || (pairingTrace.step == 4 && num == 4)
                            val isCurrent = pairingTrace.step == num
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(
                                            when {
                                                isDone -> pal.secondaryAccent
                                                isCurrent -> pal.primaryAccent
                                                else -> pal.outlineVariant
                                            }
                                        )
                                )
                                Text(
                                    text = label,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = if (isDone || isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (isDone || isCurrent) pal.onSurface else pal.secondaryText
                                )
                            }
                        }
                        Text(
                            text = pairingTrace.errorMessage ?: pairingTrace.statusText,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (pairingTrace.errorMessage != null) Color(0xFFFF453A) else pal.secondaryAccent
                        )
                    }

                    Text(
                        text = "Confirm that ${active.sasCode} matches the 6-digit code displayed on ${active.peerName} before trusting.",
                        fontSize = 12.sp,
                        color = pal.secondaryText
                    )

                    val isWaitingForAck = pairingStatus is PairingCommitStatus.Connecting
                    Button(
                        onClick = {
                            if (active.pinnedCertSha256.length != 64 || !active.pinnedCertSha256.all { it in '0'..'9' || it in 'a'..'f' }) {
                                errorMessage = "Full 64-hex TLS certificate pin required; scan a v2.0.0 MacBridge QR or enter pin below."
                                showManualFallback = true
                                return@Button
                            }
                            errorMessage = null
                            val snap = TelemetryFeature(active.localDeviceId).readCurrentBattery(context)
                            val macHex = PairingCommitMac.computeHex(
                                sharedBleSecret = active.sharedBleSecret,
                                deviceId = active.localDeviceId,
                                spkiSha256 = active.localSpkiSha256,
                                ephemeralPublicKey = active.localEphemeralPublicKey
                            )
                            val commit = PairCommitPayload(
                                deviceId = active.localDeviceId,
                                deviceName = active.localDeviceName,
                                platform = "android",
                                ephemeralPublicKey = active.localEphemeralPublicKey,
                                spkiSha256 = active.localSpkiSha256,
                                sasMacHex = macHex,
                                batteryLevel = snap.level ?: 0,
                                isCharging = snap.isCharging,
                                pairToken = active.pairToken
                            )
                            // Never trust locally until PAIR_ACK arrives back from Mac
                            BridgeActivityHub.beginPairingCommit(
                                PendingPairingCandidate(
                                    peerId = active.peerId,
                                    peerName = active.peerName,
                                    pinnedCertSha256 = active.pinnedCertSha256,
                                    sharedBleSecret = active.sharedBleSecret,
                                    bluetoothSessionKey = active.sessionKey,
                                    pairToken = active.pairToken
                                )
                            )
                            BridgeActivityHub.onSendPairCommit?.invoke(commit, active.lanHost, active.tcpPort)
                        },
                        enabled = !isWaitingForAck,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = pal.primaryAccent)
                    ) {
                        if (isWaitingForAck) {
                            CircularProgressIndicator(
                                color = Color.White,
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.size(10.dp))
                            Text("Connecting to ${active.peerName}...", color = Color.White, fontWeight = FontWeight.SemiBold)
                        } else {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(
                                if (pairingStatus is PairingCommitStatus.Failed) "Retry Confirm & Trust" else "Confirm & Trust",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            BridgeActivityHub.resetPairingStatus()
                            parsedPairing = null
                            qrDetectedBadge = false
                            errorMessage = null
                            onCancel()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = CircleShape,
                        border = BorderStroke(1.dp, pal.outlineVariant)
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = null, tint = Color(0xFFFF453A))
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("Reject & Cancel", color = Color(0xFFFF453A), fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
private fun CameraXQrPreview(
    onQrScanned: (String) -> Unit,
    onCameraBound: (Camera) -> Unit
) {
    val context = LocalContext.current
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            analyzerExecutor.shutdown()
            runCatching { scanner.close() }
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener(
                {
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(analyzerExecutor) { imageProxy ->
                        val mediaImage = imageProxy.image
                        if (mediaImage != null) {
                            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                            scanner.process(image)
                                .addOnSuccessListener { barcodes ->
                                    val raw = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue
                                    if (raw != null) {
                                        onQrScanned(raw)
                                    }
                                }
                                .addOnCompleteListener {
                                    imageProxy.close()
                                }
                        } else {
                            imageProxy.close()
                        }
                    }

                    val lifecycleOwner = ctx as? LifecycleOwner
                    if (lifecycleOwner != null) {
                        runCatching {
                            cameraProvider.unbindAll()
                            val cam = cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                analysis
                            )
                            onCameraBound(cam)
                        }
                    }
                },
                ContextCompat.getMainExecutor(ctx)
            )
            previewView
        }
    )
}

private fun decodeHex(hex: String): ByteArray =
    ByteArray(hex.length / 2) { idx ->
        hex.substring(idx * 2, idx * 2 + 2).toInt(16).toByte()
    }
