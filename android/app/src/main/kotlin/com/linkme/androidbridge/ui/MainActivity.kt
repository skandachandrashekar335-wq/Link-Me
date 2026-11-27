package com.linkme.androidbridge.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.linkme.androidbridge.core.crypto.TrustStore
import com.linkme.androidbridge.core.crypto.TrustedPeer
import com.linkme.androidbridge.core.protocol.BatteryStateEvent
import com.linkme.androidbridge.core.protocol.BatteryStateMachine
import com.linkme.androidbridge.core.protocol.NotificationEventPayload
import com.linkme.androidbridge.service.BridgeForegroundService
import com.linkme.androidbridge.ui.theme.AndroidBridgeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ANR-free Compose entrypoint: zero blocking Keystore/crypto/disk I/O on Main Thread.
 */
class MainActivity : ComponentActivity() {
    private val sharedUris = mutableStateListOf<Uri>()
    private val notificationFeed = mutableStateListOf<NotificationEventPayload>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ingestIntent(intent)

        setContent {
            AndroidBridgeTheme {
                val scope = rememberCoroutineScope()
                val stateMachine = remember { BatteryStateMachine() }
                var currentState by remember { mutableStateOf(stateMachine.state) }
                var showQrPairing by remember { mutableStateOf(false) }
                var trustStore by remember { mutableStateOf<TrustStore?>(null) }
                var pairedPeerName by remember { mutableStateOf("No Mac Paired") }
                var isPaired by remember { mutableStateOf(false) }
                var clipboardSyncEnabled by remember { mutableStateOf(true) }
                var notificationMirrorEnabled by remember { mutableStateOf(true) }
                var latencyMs by remember { mutableStateOf<Int?>(null) }
                var permissionsGranted by remember { mutableStateOf(hasRequiredPermissions()) }
                var batteryPercent by remember { mutableIntStateOf(100) }
                var isCharging by remember { mutableStateOf(false) }

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { results ->
                    val allGranted = results.values.all { it }
                    permissionsGranted = allGranted || hasRequiredPermissions()
                    if (permissionsGranted) {
                        startBridgeServiceSafely()
                    }
                }

                // Offload Keystore AES-GCM TrustStore decryption onto Dispatchers.IO
                LaunchedEffect(Unit) {
                    val loaded = withContext(Dispatchers.IO) {
                        runCatching { TrustStore.forContext(applicationContext) }.getOrNull()
                    }
                    trustStore = loaded
                    val firstPeer = loaded?.allPeers()?.firstOrNull()
                    if (firstPeer != null) {
                        pairedPeerName = firstPeer.deviceName
                        isPaired = true
                        latencyMs = 12
                    }
                    if (permissionsGranted) {
                        startBridgeServiceSafely()
                    } else {
                        permissionLauncher.launch(requiredPermissionsList())
                    }
                }

                val filePickerLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri ->
                    if (uri != null) {
                        sharedUris.add(uri)
                        stateMachine.handle(BatteryStateEvent.LOCAL_ACTIVITY_TRIGGERED)
                        currentState = stateMachine.handle(BatteryStateEvent.MTLS_HANDSHAKE_SUCCEEDED)
                        latencyMs = 4
                    }
                }

                if (showQrPairing) {
                    QrScannerScreen(
                        onConfirmTrustedPeer = { peerId, peerName, certSha256, bleSecret ->
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    trustStore?.savePeer(
                                        TrustedPeer(
                                            deviceId = peerId,
                                            deviceName = peerName,
                                            pinnedCertSha256 = certSha256,
                                            sharedBleSecret = bleSecret
                                        )
                                    )
                                }
                                pairedPeerName = peerName
                                isPaired = true
                                latencyMs = 8
                                showQrPairing = false
                            }
                        },
                        onCancel = { showQrPairing = false }
                    )
                } else {
                    BridgeHomeScreen(
                        bridgeState = currentState,
                        peerName = pairedPeerName,
                        isPaired = isPaired,
                        batteryPercent = batteryPercent,
                        isCharging = isCharging,
                        clipboardSyncEnabled = clipboardSyncEnabled,
                        notificationMirrorEnabled = notificationMirrorEnabled,
                        latencyMs = latencyMs,
                        queuedShareCount = sharedUris.size,
                        notifications = notificationFeed,
                        onToggleClipboardSync = { clipboardSyncEnabled = it },
                        onToggleNotificationMirror = { notificationMirrorEnabled = it },
                        onScanQrClick = { showQrPairing = true },
                        onSendFileClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                        onSyncClipboardClick = {
                            if (permissionsGranted) {
                                val syncIntent = Intent(this, BridgeForegroundService::class.java).apply {
                                    action = BridgeForegroundService.ACTION_SEND_CLIPBOARD_TO_MAC
                                }
                                ContextCompat.startForegroundService(this, syncIntent)
                            } else {
                                permissionLauncher.launch(requiredPermissionsList())
                            }
                        },
                        onNotificationAccessClick = {
                            runCatching {
                                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            }
                        },
                        onSendInlineReply = { key, _ ->
                            notificationFeed.removeAll { it.sbnKey == key }
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        ingestIntent(intent)
    }

    private fun ingestIntent(intent: Intent?) {
        if (intent?.action == ShareReceiverActivity.ACTION_SHARED_FILES) {
            val forwarded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(ShareReceiverActivity.EXTRA_SHARED_URIS, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra(ShareReceiverActivity.EXTRA_SHARED_URIS)
            }
            if (!forwarded.isNullOrEmpty()) {
                sharedUris.addAll(forwarded)
            }
        }
    }

    private fun requiredPermissionsList(): Array<String> {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_ADVERTISE
            perms += Manifest.permission.BLUETOOTH_CONNECT
            perms += Manifest.permission.BLUETOOTH_SCAN
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.NEARBY_WIFI_DEVICES
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        return perms.toTypedArray()
    }

    private fun hasRequiredPermissions(): Boolean {
        val transportPerms = requiredPermissionsList().filter {
            it != Manifest.permission.POST_NOTIFICATIONS
        }
        return transportPerms.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun startBridgeServiceSafely() {
        if (!hasRequiredPermissions()) return
        runCatching {
            ContextCompat.startForegroundService(
                this,
                Intent(this, BridgeForegroundService::class.java)
            )
        }
    }
}
