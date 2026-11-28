package com.linkme.androidbridge.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.linkme.androidbridge.core.crypto.TrustStore
import com.linkme.androidbridge.core.crypto.TrustedPeer
import com.linkme.androidbridge.core.prefs.BridgePreferencesStore
import com.linkme.androidbridge.core.protocol.BatteryStateEvent
import com.linkme.androidbridge.core.protocol.BatteryStateMachine
import com.linkme.androidbridge.core.protocol.DeviceCommandPayload
import com.linkme.androidbridge.core.protocol.UrlSanitizer
import com.linkme.androidbridge.service.BridgeActivityHub
import com.linkme.androidbridge.service.BridgeForegroundService
import com.linkme.androidbridge.service.NotificationAddonStatus
import com.linkme.androidbridge.service.NotificationCompanionBridge
import com.linkme.androidbridge.service.TelemetryFeature
import com.linkme.androidbridge.ui.theme.AndroidBridgeTheme
import com.linkme.androidbridge.ui.theme.paletteForTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ANR-free Compose Studio entrypoint: zero blocking Keystore/crypto/disk I/O on Main Thread.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ingestIntent(intent)

        val prefsStore = BridgePreferencesStore.forContext(applicationContext)
        val initialBattery = TelemetryFeature("local").readCurrentBattery(applicationContext)
        BridgeActivityHub.updateLocalBattery(initialBattery.level, initialBattery.isCharging)

        setContent {
            val prefsState by prefsStore.state.collectAsState()
            val pal = paletteForTheme(prefsState.accentTheme)

            AndroidBridgeTheme(accentTheme = prefsState.accentTheme) {
                val scope = rememberCoroutineScope()
                val lifecycleOwner = this@MainActivity
                val stateMachine = remember { BatteryStateMachine() }
                var currentState by remember { mutableStateOf(stateMachine.state) }
                var showAddonGuideDialog by remember { mutableStateOf(false) }
                var showSendUrlDialog by remember { mutableStateOf(false) }
                var urlInput by remember { mutableStateOf("https://") }
                var urlError by remember { mutableStateOf<String?>(null) }
                var trustStore by remember { mutableStateOf<TrustStore?>(null) }
                var pairedPeerName by remember { mutableStateOf("No Mac Paired") }
                var pairedPeerFingerprint by remember { mutableStateOf("") }
                var isPaired by remember { mutableStateOf(false) }
                var clipboardSyncEnabled by remember { mutableStateOf(true) }
                var notificationMirrorEnabled by remember { mutableStateOf(true) }
                var addonStatus by remember { mutableStateOf(NotificationAddonStatus.NOT_INSTALLED) }
                var permissionsGranted by remember { mutableStateOf(hasRequiredPermissions()) }

                val liveNotifications by NotificationCompanionBridge.recentFeed.collectAsState()
                val activities by BridgeActivityHub.activities.collectAsState()
                val pendingOffer by BridgeActivityHub.pendingFileOffer.collectAsState()
                val activeFileTransfer by BridgeActivityHub.activeFileTransfer.collectAsState()
                val isBluetoothConnected by BridgeActivityHub.bluetoothConnected.collectAsState()
                val isWifiActive by BridgeActivityHub.wifiConnected.collectAsState()
                val latencyMs by BridgeActivityHub.latencyMs.collectAsState()
                val localBatteryPercent by BridgeActivityHub.localBatteryPercent.collectAsState()
                val localIsCharging by BridgeActivityHub.localIsCharging.collectAsState()
                val peerDeviceName by BridgeActivityHub.peerDeviceName.collectAsState()
                val peerBatteryPercent by BridgeActivityHub.peerBatteryPercent.collectAsState()
                val peerIsCharging by BridgeActivityHub.peerIsCharging.collectAsState()
                val mirrorState by BridgeActivityHub.mirrorState.collectAsState()
                val pairingStatus by BridgeActivityHub.pairingStatus.collectAsState()
                val radioDiagnostics by BridgeActivityHub.radioDiagnostics.collectAsState()

                LaunchedEffect(pairingStatus) {
                    val st = pairingStatus
                    if (st is com.linkme.androidbridge.service.PairingCommitStatus.Connected) {
                        pairedPeerName = st.peerName
                        pairedPeerFingerprint = st.pinnedCertSha256
                        isPaired = true
                    }
                }

                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            scope.launch(Dispatchers.IO) {
                                val nextStatus = NotificationCompanionBridge.inspectAddonStatus(applicationContext)
                                withContext(Dispatchers.Main) {
                                    addonStatus = nextStatus
                                }
                            }
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { results ->
                    val allGranted = results.values.all { it }
                    permissionsGranted = allGranted || hasRequiredPermissions()
                    if (permissionsGranted) {
                        startBridgeServiceSafely()
                    }
                }

                LaunchedEffect(Unit) {
                    val loaded = withContext(Dispatchers.IO) {
                        runCatching { TrustStore.forContext(applicationContext) }.getOrNull()
                    }
                    val initialAddonStatus = withContext(Dispatchers.IO) {
                        NotificationCompanionBridge.inspectAddonStatus(applicationContext)
                    }
                    addonStatus = initialAddonStatus
                    trustStore = loaded
                    val firstPeer = loaded?.allPeers()?.firstOrNull()
                    if (firstPeer != null) {
                        pairedPeerName = firstPeer.deviceName
                        pairedPeerFingerprint = firstPeer.pinnedCertSha256
                        isPaired = true
                    }
                    if (permissionsGranted) {
                        startBridgeServiceSafely()
                    } else {
                        permissionLauncher.launch(requiredPermissionsList())
                    }
                }

                val filePickerLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenMultipleDocuments()
                ) { uris ->
                    if (uris.isNotEmpty()) {
                        currentState = stateMachine.handle(BatteryStateEvent.LOCAL_ACTIVITY_TRIGGERED)
                        uris.forEach { uri ->
                            BridgeActivityHub.enqueueOutboundFileUri(uri.toString())
                        }
                    }
                }

                BridgeHomeScreen(
                    bridgeState = currentState,
                    peerName = peerDeviceName ?: pairedPeerName,
                    peerCertFingerprint = pairedPeerFingerprint,
                    isPaired = isPaired,
                    isBluetoothConnected = isBluetoothConnected,
                    isWifiActive = isWifiActive,
                    batteryPercent = localBatteryPercent,
                    isCharging = localIsCharging,
                    peerBatteryPercent = peerBatteryPercent,
                    peerIsCharging = peerIsCharging,
                    clipboardSyncEnabled = clipboardSyncEnabled,
                    notificationMirrorEnabled = notificationMirrorEnabled,
                    addonStatus = addonStatus,
                    prefsState = prefsState,
                    latencyMs = latencyMs,
                    activeFileTransfer = activeFileTransfer,
                    pendingFileOffer = pendingOffer,
                    mirrorState = mirrorState,
                    radioDiagnostics = radioDiagnostics,
                    notifications = liveNotifications,
                    activities = activities,
                    onSendPing01 = { BridgeActivityHub.onSendPing01?.invoke() },
                    onCopyDiagnostics = { report ->
                        val cm = getSystemService(ClipboardManager::class.java)
                        cm?.setPrimaryClip(ClipData.newPlainText("Link-Me Diagnostics", report))
                    },
                    onToggleClipboardSync = { clipboardSyncEnabled = it },
                    onToggleNotificationMirror = { notificationMirrorEnabled = it },
                    onConfirmTrustedPeer = { peerId, peerName, certSha256, bleSecret, btSessionKey, onPersisted ->
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                trustStore?.savePeer(
                                    TrustedPeer(
                                        deviceId = peerId,
                                        deviceName = peerName,
                                        pinnedCertSha256 = certSha256,
                                        sharedBleSecret = bleSecret,
                                        bluetoothSessionKey = btSessionKey
                                    )
                                )
                            }
                            pairedPeerName = peerName
                            pairedPeerFingerprint = certSha256
                            isPaired = true
                            BridgeActivityHub.onPeerTrustedUpdated?.invoke()
                            onPersisted()
                        }
                    },
                    onSendFileClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                    onSendUrlClick = {
                        urlError = null
                        showSendUrlDialog = true
                    },
                    onRingMacClick = {
                        BridgeActivityHub.onSendDeviceCommand?.invoke(
                            DeviceCommandPayload(
                                command = "ringPhone",
                                durationSeconds = 10,
                                timestampMs = System.currentTimeMillis()
                            )
                        )
                    },
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
                        val opened = runCatching {
                            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            true
                        }.getOrDefault(false)
                        if (!opened || addonStatus != NotificationAddonStatus.ACTIVE) {
                            showAddonGuideDialog = true
                        }
                    },
                    onSendInlineReply = { key, replyText ->
                        NotificationCompanionBridge.dispatchDeviceCommand(
                            this,
                            DeviceCommandPayload(
                                command = "replyToNotification",
                                sbnKey = key,
                                replyText = replyText,
                                timestampMs = System.currentTimeMillis()
                            )
                        )
                    },
                    onMediaAction = { action ->
                        BridgeActivityHub.onSendMediaAction?.invoke(action)
                    },
                    onResolvePendingFile = { accepted ->
                        BridgeActivityHub.resolvePendingOffer(accepted)
                    },
                    onCopyActivitySnippet = { snippet ->
                        val cm = getSystemService(ClipboardManager::class.java)
                        cm?.setPrimaryClip(ClipData.newPlainText("Link-Me", snippet))
                    },
                    onOpenActivityFile = { uriString ->
                        runCatching {
                            startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(uriString)).apply {
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                            )
                        }
                    },
                    onSelectTheme = { prefsStore.setAccentTheme(it) },
                    onCompactModeChange = { prefsStore.setCompactMode(it) },
                    onTelemetryFooterChange = { prefsStore.setShowTelemetryFooter(it) },
                    onWifiCooldownChange = { prefsStore.setWifiCooldownSeconds(it) },
                    onBleSaverChange = { prefsStore.setBleOnlyBatterySaver(it) },
                    onAutoAcceptFilesChange = { prefsStore.setAutoAcceptFiles(it) },
                    onClipboardDirectionChange = { prefsStore.setClipboardDirection(it) },
                    onDeviceAliasSave = { prefsStore.setDeviceAlias(it) },
                    onUnpairDevices = {
                        scope.launch(Dispatchers.IO) {
                            val store = trustStore ?: return@launch
                            store.allPeers().forEach { store.removePeer(it.deviceId) }
                            withContext(Dispatchers.Main) {
                                BridgeActivityHub.notifyUnpaired()
                                isPaired = false
                                pairedPeerName = "No Mac Paired"
                                pairedPeerFingerprint = ""
                            }
                        }
                    }
                )

                if (showSendUrlDialog) {
                    AlertDialog(
                        onDismissRequest = { showSendUrlDialog = false },
                        containerColor = pal.surfaceContainer,
                        titleContentColor = pal.onSurface,
                        textContentColor = pal.secondaryText,
                        title = { Text("Send URL to Mac") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = urlInput,
                                    onValueChange = {
                                        urlInput = it
                                        urlError = null
                                    },
                                    label = { Text("https://...") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                if (urlError != null) {
                                    Text(
                                        text = urlError!!,
                                        fontSize = 11.sp,
                                        color = Color(0xFFFF453A)
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    try {
                                        val validUrl = UrlSanitizer.validateStrict(urlInput)
                                        BridgeActivityHub.onSendOpenUrl?.invoke(validUrl)
                                        showSendUrlDialog = false
                                    } catch (ex: Exception) {
                                        urlError = "Blocked: only http/https URLs allowed"
                                    }
                                }
                            ) {
                                Text("Send URL", color = pal.primaryAccent)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showSendUrlDialog = false }) {
                                Text("Cancel", color = pal.secondaryText)
                            }
                        }
                    )
                }

                if (showAddonGuideDialog) {
                    AlertDialog(
                        onDismissRequest = { showAddonGuideDialog = false },
                        containerColor = pal.surfaceContainer,
                        titleContentColor = pal.onSurface,
                        textContentColor = pal.secondaryText,
                        title = { Text("Enable Notification Access") },
                        text = {
                            Text(
                                "To mirror notifications to your Mac, AndroidBridge requires Notification Access in system settings.\n\n" +
                                    "If the toggle in Settings is disabled ('Restricted setting'):\n" +
                                    "1. Long press AndroidBridge app icon -> App info\n" +
                                    "2. Tap the top-right three dots (⋮) -> 'Allow restricted settings'\n" +
                                    "3. Return to Notification Access and toggle AndroidBridge ON.",
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Default
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    showAddonGuideDialog = false
                                    runCatching {
                                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                    }
                                }
                            ) {
                                Text("Open Settings", color = pal.primaryAccent)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showAddonGuideDialog = false }) {
                                Text("Close", color = pal.secondaryText)
                            }
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
                forwarded.forEach { BridgeActivityHub.enqueueOutboundFileUri(it.toString()) }
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
