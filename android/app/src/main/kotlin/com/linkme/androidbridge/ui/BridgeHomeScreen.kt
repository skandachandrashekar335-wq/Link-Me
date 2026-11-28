package com.linkme.androidbridge.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LaptopMac
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linkme.androidbridge.core.prefs.BridgeAccentTheme
import com.linkme.androidbridge.core.prefs.BridgePreferencesState
import com.linkme.androidbridge.core.prefs.ClipboardDirection
import com.linkme.androidbridge.core.protocol.BridgeState
import com.linkme.androidbridge.core.protocol.FileOfferPayload
import com.linkme.androidbridge.core.protocol.MediaControlAction
import com.linkme.androidbridge.core.protocol.NotificationEventPayload
import com.linkme.androidbridge.service.ActivityCategory
import com.linkme.androidbridge.service.BridgeActivityItem
import com.linkme.androidbridge.service.FileTransferUiState
import com.linkme.androidbridge.service.NotificationAddonStatus
import com.linkme.androidbridge.service.RadioDiagnosticsUiState
import com.linkme.androidbridge.service.ScreenMirrorUiState
import com.linkme.androidbridge.ui.theme.BridgeThemePalette
import com.linkme.androidbridge.ui.theme.LocalBridgePalette
import com.linkme.androidbridge.ui.theme.paletteForTheme
import kotlin.math.roundToInt

enum class AndroidStudioTab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.Home),
    MIRROR("Mirror", Icons.Rounded.Cast),
    SCAN_QR("Scan QR", Icons.Rounded.QrCodeScanner),
    ACTIVITY("Activity", Icons.Rounded.History),
    SETTINGS("Settings", Icons.Rounded.Settings)
}

@Composable
fun BridgeHomeScreen(
    bridgeState: BridgeState,
    peerName: String,
    peerCertFingerprint: String,
    isPaired: Boolean,
    isBluetoothConnected: Boolean,
    isWifiActive: Boolean,
    batteryPercent: Int?,
    isCharging: Boolean,
    peerBatteryPercent: Int?,
    peerIsCharging: Boolean,
    clipboardSyncEnabled: Boolean,
    notificationMirrorEnabled: Boolean,
    addonStatus: NotificationAddonStatus,
    prefsState: BridgePreferencesState,
    latencyMs: Int?,
    activeFileTransfer: FileTransferUiState?,
    pendingFileOffer: FileOfferPayload?,
    mirrorState: ScreenMirrorUiState,
    radioDiagnostics: RadioDiagnosticsUiState,
    notifications: List<NotificationEventPayload>,
    activities: List<BridgeActivityItem>,
    onSendPing01: () -> Unit,
    onCopyDiagnostics: (String) -> Unit,
    onToggleClipboardSync: (Boolean) -> Unit,
    onToggleNotificationMirror: (Boolean) -> Unit,
    onConfirmTrustedPeer: (String, String, String, ByteArray, ByteArray, () -> Unit) -> Unit,
    onSendFileClick: () -> Unit,
    onSendUrlClick: () -> Unit,
    onRingMacClick: () -> Unit,
    onSyncClipboardClick: () -> Unit,
    onNotificationAccessClick: () -> Unit,
    onSendInlineReply: (sbnKey: String, replyText: String) -> Unit,
    onMediaAction: (MediaControlAction) -> Unit,
    onResolvePendingFile: (Boolean) -> Unit,
    onCopyActivitySnippet: (String) -> Unit,
    onOpenActivityFile: (String) -> Unit,
    onSelectTheme: (BridgeAccentTheme) -> Unit,
    onCompactModeChange: (Boolean) -> Unit,
    onTelemetryFooterChange: (Boolean) -> Unit,
    onWifiCooldownChange: (Int) -> Unit,
    onBleSaverChange: (Boolean) -> Unit,
    onAutoAcceptFilesChange: (Boolean) -> Unit,
    onClipboardDirectionChange: (ClipboardDirection) -> Unit,
    onDeviceAliasSave: (String) -> Unit,
    onUnpairDevices: () -> Unit
) {
    val pal = LocalBridgePalette.current
    var currentTab by remember { mutableStateOf(AndroidStudioTab.HOME) }
    var activityFilter by remember { mutableStateOf<ActivityCategory?>(null) }

    Scaffold(
        containerColor = pal.surface,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(pal.surfaceLow.copy(alpha = 0.9f))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Link-Me",
                        fontSize = 11.sp,
                        color = pal.secondaryText,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = currentTab.label,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = pal.onSurface
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = {
                            val next = if (prefsState.accentTheme == BridgeAccentTheme.ALABASTER) {
                                BridgeAccentTheme.LIQUID_BLUE
                            } else {
                                BridgeAccentTheme.ALABASTER
                            }
                            onSelectTheme(next)
                        },
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(pal.surfaceHigh)
                    ) {
                        Icon(
                            imageVector = if (pal.isLight) Icons.Rounded.DarkMode else Icons.Rounded.LightMode,
                            contentDescription = "Toggle Light/Dark",
                            tint = pal.onSurface,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(pal.surfaceHigh)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val connected = isBluetoothConnected || isWifiActive || bridgeState == BridgeState.ACTIVE_WIFI_STREAM
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(if (connected) pal.secondaryAccent else if (isPaired) Color(0xFFFF9F0A) else pal.secondaryText)
                        )
                        Text(
                            text = when {
                                isWifiActive || bridgeState == BridgeState.ACTIVE_WIFI_STREAM -> "Wi-Fi + BLE"
                                isBluetoothConnected -> "BLE E2EE"
                                isPaired -> "Standby"
                                else -> "Unpaired"
                            },
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = pal.onSurface
                        )
                    }
                }
            }
        },
        bottomBar = {
            Column {
                if (prefsState.showTelemetryFooter) {
                    HorizontalDivider(thickness = 1.dp, color = pal.outlineVariant)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(pal.surfaceLowest)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val latLabel = latencyMs?.let { "${it}ms" } ?: "--"
                        val channelLabel = if (isWifiActive || bridgeState == BridgeState.ACTIVE_WIFI_STREAM) "WIFI TLS 1.3" else "BLUETOOTH E2EE"
                        Text(
                            text = "LATENCY: $latLabel • $channelLabel",
                            fontSize = 10.sp,
                            letterSpacing = 0.5.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = pal.secondaryAccent
                        )
                        Text(
                            text = "CHACHA20 PINNED",
                            fontSize = 10.sp,
                            letterSpacing = 0.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = pal.secondaryText
                        )
                    }
                }
                HorizontalDivider(thickness = 1.dp, color = pal.outlineVariant)
                NavigationBar(containerColor = pal.surfaceLow) {
                    AndroidStudioTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = currentTab == tab,
                            onClick = { currentTab = tab },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label, fontSize = 11.sp, fontWeight = FontWeight.Medium) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = pal.activeCardInk,
                                selectedTextColor = pal.activeCardLight,
                                indicatorColor = pal.surfaceHigh,
                                unselectedIconColor = pal.secondaryText,
                                unselectedTextColor = pal.secondaryText
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(pal.surface, pal.surfaceLowest)
                    )
                )
                .padding(innerPadding)
        ) {
            when (currentTab) {
                AndroidStudioTab.SCAN_QR -> {
                    Box(modifier = Modifier.padding(16.dp)) {
                        QrScannerScreen(
                            onConfirmTrustedPeer = { id, name, fp, bleSec, btKey, onPersisted ->
                                onConfirmTrustedPeer(id, name, fp, bleSec, btKey) {
                                    onPersisted()
                                    currentTab = AndroidStudioTab.HOME
                                }
                            },
                            onCancel = { currentTab = AndroidStudioTab.HOME }
                        )
                    }
                }

                AndroidStudioTab.MIRROR -> {
                    ScreenMirrorScreen(
                        peerName = peerName,
                        isPaired = isPaired,
                        isWifiActive = isWifiActive || bridgeState == BridgeState.ACTIVE_WIFI_STREAM,
                        mirrorState = mirrorState
                    )
                }

                AndroidStudioTab.HOME -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(if (prefsState.compactMode) 12.dp else 16.dp)
                    ) {
                        HomeTabContent(
                            pal = pal,
                            bridgeState = bridgeState,
                            peerName = peerName,
                            isPaired = isPaired,
                            isBluetoothConnected = isBluetoothConnected,
                            isWifiActive = isWifiActive,
                            batteryPercent = batteryPercent,
                            isCharging = isCharging,
                            peerBatteryPercent = peerBatteryPercent,
                            peerIsCharging = peerIsCharging,
                            addonStatus = addonStatus,
                            prefsState = prefsState,
                            latencyMs = latencyMs,
                            activeFileTransfer = activeFileTransfer,
                            pendingFileOffer = pendingFileOffer,
                            radioDiagnostics = radioDiagnostics,
                            notifications = notifications,
                            onSendPing01 = onSendPing01,
                            onCopyDiagnostics = {
                                onCopyDiagnostics(radioDiagnostics.formatCopyableReport(peerName, latencyMs))
                            },
                            onOpenQrTab = { currentTab = AndroidStudioTab.SCAN_QR },
                            onSendFileClick = onSendFileClick,
                            onSendUrlClick = onSendUrlClick,
                            onRingMacClick = onRingMacClick,
                            onSyncClipboardClick = onSyncClipboardClick,
                            onNotificationAccessClick = onNotificationAccessClick,
                            onSendInlineReply = onSendInlineReply,
                            onMediaAction = onMediaAction,
                            onResolvePendingFile = onResolvePendingFile
                        )
                    }
                }

                AndroidStudioTab.ACTIVITY -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ActivityTabContent(
                            pal = pal,
                            activities = activities,
                            selectedFilter = activityFilter,
                            onSelectFilter = { activityFilter = it },
                            onCopySnippet = onCopyActivitySnippet,
                            onOpenFile = onOpenActivityFile
                        )
                    }
                }

                AndroidStudioTab.SETTINGS -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        SettingsTabContent(
                            pal = pal,
                            peerName = peerName,
                            peerCertFingerprint = peerCertFingerprint,
                            isPaired = isPaired,
                            isBluetoothConnected = isBluetoothConnected,
                            latencyMs = latencyMs,
                            clipboardSyncEnabled = clipboardSyncEnabled,
                            notificationMirrorEnabled = notificationMirrorEnabled,
                            addonStatus = addonStatus,
                            prefsState = prefsState,
                            onToggleClipboardSync = onToggleClipboardSync,
                            onToggleNotificationMirror = onToggleNotificationMirror,
                            onSelectTheme = onSelectTheme,
                            onCompactModeChange = onCompactModeChange,
                            onTelemetryFooterChange = onTelemetryFooterChange,
                            onWifiCooldownChange = onWifiCooldownChange,
                            onBleSaverChange = onBleSaverChange,
                            onAutoAcceptFilesChange = onAutoAcceptFilesChange,
                            onClipboardDirectionChange = onClipboardDirectionChange,
                            onDeviceAliasSave = onDeviceAliasSave,
                            onNotificationAccessClick = onNotificationAccessClick,
                            onOpenQrTab = { currentTab = AndroidStudioTab.SCAN_QR },
                            onUnpairDevices = onUnpairDevices
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeTabContent(
    pal: BridgeThemePalette,
    bridgeState: BridgeState,
    peerName: String,
    isPaired: Boolean,
    isBluetoothConnected: Boolean,
    isWifiActive: Boolean,
    batteryPercent: Int?,
    isCharging: Boolean,
    peerBatteryPercent: Int?,
    peerIsCharging: Boolean,
    addonStatus: NotificationAddonStatus,
    prefsState: BridgePreferencesState,
    latencyMs: Int?,
    activeFileTransfer: FileTransferUiState?,
    pendingFileOffer: FileOfferPayload?,
    radioDiagnostics: RadioDiagnosticsUiState,
    notifications: List<NotificationEventPayload>,
    onSendPing01: () -> Unit,
    onCopyDiagnostics: () -> Unit,
    onOpenQrTab: () -> Unit,
    onSendFileClick: () -> Unit,
    onSendUrlClick: () -> Unit,
    onRingMacClick: () -> Unit,
    onSyncClipboardClick: () -> Unit,
    onNotificationAccessClick: () -> Unit,
    onSendInlineReply: (String, String) -> Unit,
    onMediaAction: (MediaControlAction) -> Unit,
    onResolvePendingFile: (Boolean) -> Unit
) {
    // 1. Hero MacBook Card (matches android_home/screen.png)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = pal.surfaceLow),
        border = BorderStroke(1.dp, pal.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(pal.secondaryAccent.copy(alpha = 0.14f))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (isPaired) pal.secondaryAccent else Color(0xFFFF9F0A))
                        )
                        Text(
                            text = if (isWifiActive || bridgeState == BridgeState.ACTIVE_WIFI_STREAM) {
                                "Connected • Wi-Fi mTLS 1.3"
                            } else if (isBluetoothConnected) {
                                "Connected • Bluetooth E2EE"
                            } else if (isPaired) {
                                "Standby • BLE Auto-Reconnect"
                            } else {
                                "Ready • Scan Mac QR to Pair"
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = pal.secondaryAccent
                        )
                    }
                    Text(
                        text = peerName,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = pal.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${prefsState.deviceAlias} • ${if (prefsState.bleOnlyBatterySaver) "BLE-Only Saver" else "Dual BLE + Wi-Fi"}",
                        fontSize = 12.sp,
                        color = pal.secondaryText
                    )
                }

                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(pal.surfaceContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.LaptopMac,
                        contentDescription = null,
                        tint = pal.activeCardLight,
                        modifier = Modifier.size(34.dp)
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(pal.surfaceLowest)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val localBattLabel = batteryPercent?.let { "$it%${if (isCharging) "+" else ""}" } ?: "--"
                val macBattLabel = peerBatteryPercent?.let { "$it%${if (peerIsCharging) "+" else ""}" } ?: "--"
                Text(
                    text = "Phone $localBattLabel • Mac $macBattLabel",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = pal.secondaryText
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(pal.surfaceContainer)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Rounded.Speed, contentDescription = null, tint = pal.activeCardLight, modifier = Modifier.size(12.dp))
                        Text(latencyMs?.let { "${it}ms" } ?: "--", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = pal.onSurface)
                    }
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(pal.secondaryAccent.copy(alpha = 0.14f))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Rounded.Lock, contentDescription = null, tint = pal.secondaryAccent, modifier = Modifier.size(12.dp))
                        Text("E2EE", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = pal.secondaryAccent)
                    }
                }
            }
        }
    }

    // 2. Live Radio Connection Diagnostics Card (Visible & Copyable + Ping 0x01)
    var diagExpanded by remember { mutableStateOf(true) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = pal.surfaceContainer),
        border = BorderStroke(1.dp, pal.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { diagExpanded = !diagExpanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "LIVE RADIO DIAGNOSTICS",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = pal.secondaryAccent
                    )
                    Text(
                        "Connection Diagnostics",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = pal.onSurface
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = onCopyDiagnostics,
                        shape = CircleShape
                    ) {
                        Text("Copy", fontSize = 11.sp, color = pal.onSurface)
                    }
                    Button(
                        onClick = onSendPing01,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = pal.primaryAccent)
                    ) {
                        Text("Send Ping (0x01)", fontSize = 11.sp, color = Color.White)
                    }
                }
            }

            if (radioDiagnostics.pingStatusText != null) {
                Text(
                    text = radioDiagnostics.pingStatusText,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = pal.secondaryAccent
                )
            }

            if (diagExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(pal.surfaceLowest)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "BLE Radio: ${radioDiagnostics.bleRadioState}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = pal.onSurface
                    )
                    Text(
                        "BLE Link: ${radioDiagnostics.bleLinkState}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = pal.onSurface
                    )
                    Text(
                        "Wi-Fi mTLS: ${radioDiagnostics.wifiMtlsState}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = pal.onSurface
                    )
                    HorizontalDivider(thickness = 1.dp, color = pal.outlineVariant, modifier = Modifier.padding(vertical = 4.dp))
                    Text(
                        "LAST 10 WIRE EVENTS",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = pal.secondaryText
                    )
                    if (radioDiagnostics.lastWireEvents.isEmpty()) {
                        Text(
                            "Waiting for BLE GATT or Wi-Fi mTLS events...",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = pal.secondaryText
                        )
                    } else {
                        radioDiagnostics.lastWireEvents.take(10).forEach { line ->
                            Text(
                                text = line,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = pal.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }

    // 3. Quick Actions Strip (matches android_home)
    Text("Quick Actions", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = pal.onSurface)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        HomeQuickTile(Icons.Rounded.UploadFile, "Send File", pal.activeCardLight, pal, Modifier.weight(1f), onSendFileClick)
        HomeQuickTile(Icons.Rounded.ContentCopy, "Clipboard", pal.secondaryAccent, pal, Modifier.weight(1f), onSyncClipboardClick)
        HomeQuickTile(Icons.Rounded.Link, "Send Link", Color(0xFFFFB868), pal, Modifier.weight(1f), onSendUrlClick)
        HomeQuickTile(
            if (isPaired) Icons.Rounded.NotificationsActive else Icons.Rounded.QrCodeScanner,
            if (isPaired) "Ring Mac" else "Pair Mac",
            pal.primaryAccent,
            pal,
            Modifier.weight(1f),
            if (isPaired) onRingMacClick else onOpenQrTab
        )
    }

    if (pendingFileOffer != null) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = pal.surfaceContainer),
            border = BorderStroke(1.dp, pal.primaryAccent)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("INCOMING FILE OFFER", fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = pal.primaryAccent)
                Text("${pendingFileOffer.filename} (${pendingFileOffer.byteSize / 1024} KB)", color = pal.onSurface, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onResolvePendingFile(false) }, modifier = Modifier.weight(1f), shape = CircleShape) {
                        Text("Decline", color = pal.onSurface)
                    }
                    Button(
                        onClick = { onResolvePendingFile(true) },
                        modifier = Modifier.weight(1f),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = pal.primaryAccent)
                    ) {
                        Text("Accept", color = Color.White)
                    }
                }
            }
        }
    }

    // 3. Universal Clipboard Card
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = pal.surfaceContainer),
        border = BorderStroke(1.dp, pal.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(pal.secondaryAccent.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null, tint = pal.secondaryAccent, modifier = Modifier.size(18.dp))
                    }
                    Column {
                        Text("Universal Clipboard", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = pal.onSurface)
                        Text(prefsState.clipboardDirection.displayName, fontSize = 11.sp, color = pal.secondaryAccent)
                    }
                }
                Button(
                    onClick = onSyncClipboardClick,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = pal.surfaceHigh)
                ) {
                    Text("Push", fontSize = 11.sp, color = pal.onSurface)
                }
            }
            Text("Sensitive password-manager clips masked automatically • SHA-256 integrity", fontSize = 11.sp, color = pal.secondaryText)
        }
    }

    // 4. File Transfer Card (Live 0% -> 100% progress, zero stuck queue items)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = pal.surfaceContainer),
        border = BorderStroke(1.dp, pal.outlineVariant)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(pal.surfaceHigh),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = pal.activeCardLight, modifier = Modifier.size(18.dp))
                    }
                    Column {
                        Text("File Transfer", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = pal.onSurface)
                        Text(
                            if (activeFileTransfer != null) {
                                "${activeFileTransfer.filename} • ${activeFileTransfer.percent}% (${activeFileTransfer.transportLabel})"
                            } else {
                                "Saved to Downloads/Link-Me • Up to 4.0 GB P2P"
                            },
                            fontSize = 11.sp,
                            color = pal.secondaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Button(
                    onClick = onSendFileClick,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = pal.activeCardLight)
                ) {
                    Text("+ Send", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = pal.activeCardInk)
                }
            }
            if (activeFileTransfer != null) {
                LinearProgressIndicator(
                    progress = { activeFileTransfer.fraction },
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                    color = pal.primaryAccent,
                    trackColor = pal.surfaceHighest
                )
            }
        }
    }

    // 5. Now Playing Media Remote Card
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = pal.surfaceContainer),
        border = BorderStroke(1.dp, pal.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("NOW PLAYING CONTROL", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = pal.activeCardLight)
                    Text("Remote Media on $peerName", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = pal.onSurface)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MediaIconBtn(Icons.Rounded.SkipPrevious, pal, Modifier.weight(1f)) { onMediaAction(MediaControlAction.PREV) }
                MediaIconBtn(Icons.Rounded.Pause, pal, Modifier.weight(1.2f), primary = true) { onMediaAction(MediaControlAction.PLAY_PAUSE) }
                MediaIconBtn(Icons.Rounded.SkipNext, pal, Modifier.weight(1f)) { onMediaAction(MediaControlAction.NEXT) }
                MediaIconBtn(Icons.Rounded.VolumeDown, pal, Modifier.weight(1f)) { onMediaAction(MediaControlAction.VOL_DOWN) }
                MediaIconBtn(Icons.Rounded.VolumeOff, pal, Modifier.weight(1f)) { onMediaAction(MediaControlAction.MUTE) }
                MediaIconBtn(Icons.Rounded.VolumeUp, pal, Modifier.weight(1f)) { onMediaAction(MediaControlAction.VOL_UP) }
            }
        }
    }

    // 6. Optional Notification Add-on Card (Play-Protect Clean Architecture)
    if (addonStatus != NotificationAddonStatus.ACTIVE) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = pal.surfaceLow),
            border = BorderStroke(1.dp, pal.outlineVariant)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Enable Optional Notification Add-on", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = pal.onSurface)
                    Text(
                        if (addonStatus == NotificationAddonStatus.INSTALLED_NEEDS_PERMISSION) {
                            "Add-on installed — tap Grant to enable Notification Access."
                        } else {
                            "Core APK is Play-Protect clean. Install NotifyAddon APK to mirror alerts."
                        },
                        fontSize = 11.sp,
                        color = pal.secondaryText
                    )
                }
                Button(
                    onClick = onNotificationAccessClick,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = pal.primaryAccent)
                ) {
                    Text(
                        if (addonStatus == NotificationAddonStatus.INSTALLED_NEEDS_PERMISSION) "Grant" else "Add-on",
                        fontSize = 11.sp,
                        color = Color.White
                    )
                }
            }
        }
    }

    if (notifications.isNotEmpty()) {
        notifications.take(3).forEach { item ->
            ReplyableNotificationCard(pal, item) { reply ->
                onSendInlineReply(item.sbnKey, reply)
            }
        }
    }
}

@Composable
private fun ActivityTabContent(
    pal: BridgeThemePalette,
    activities: List<BridgeActivityItem>,
    selectedFilter: ActivityCategory?,
    onSelectFilter: (ActivityCategory?) -> Unit,
    onCopySnippet: (String) -> Unit,
    onOpenFile: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        listOf(
            "All" to null,
            "Files" to ActivityCategory.FILES,
            "Clipboard" to ActivityCategory.CLIPBOARD,
            "Media" to ActivityCategory.MEDIA,
            "System" to ActivityCategory.SYSTEM
        ).forEach { (label, cat) ->
            val selected = selectedFilter == cat
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (selected) pal.activeCardLight else pal.surfaceContainer)
                    .clickable { onSelectFilter(cat) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = label,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) pal.activeCardInk else pal.onSurface
                )
            }
        }
    }

    val filtered = if (selectedFilter == null) activities else activities.filter { it.category == selectedFilter }
    if (filtered.isEmpty()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = pal.surfaceContainer),
            border = BorderStroke(1.dp, pal.outlineVariant)
        ) {
            Text(
                text = "No activity recorded yet. Scan Mac QR, send a file, or control media playback.",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = pal.secondaryText,
                modifier = Modifier.padding(16.dp)
            )
        }
    } else {
        filtered.forEach { item ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = pal.surfaceContainer),
                border = BorderStroke(1.dp, pal.outlineVariant)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("${item.category.name} • ${item.title}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = pal.secondaryAccent, fontWeight = FontWeight.Bold)
                        Text(item.detail, fontSize = 13.sp, color = pal.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (item.fileUriString != null) {
                        OutlinedButton(onClick = { onOpenFile(item.fileUriString) }, shape = CircleShape) {
                            Text("Open", fontSize = 11.sp, color = pal.onSurface)
                        }
                    } else if (item.clipboardText != null) {
                        OutlinedButton(onClick = { onCopySnippet(item.clipboardText) }, shape = CircleShape) {
                            Text("Copy Again", fontSize = 11.sp, color = pal.onSurface)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsTabContent(
    pal: BridgeThemePalette,
    peerName: String,
    peerCertFingerprint: String,
    isPaired: Boolean,
    isBluetoothConnected: Boolean,
    latencyMs: Int?,
    clipboardSyncEnabled: Boolean,
    notificationMirrorEnabled: Boolean,
    addonStatus: NotificationAddonStatus,
    prefsState: BridgePreferencesState,
    onToggleClipboardSync: (Boolean) -> Unit,
    onToggleNotificationMirror: (Boolean) -> Unit,
    onSelectTheme: (BridgeAccentTheme) -> Unit,
    onCompactModeChange: (Boolean) -> Unit,
    onTelemetryFooterChange: (Boolean) -> Unit,
    onWifiCooldownChange: (Int) -> Unit,
    onBleSaverChange: (Boolean) -> Unit,
    onAutoAcceptFilesChange: (Boolean) -> Unit,
    onClipboardDirectionChange: (ClipboardDirection) -> Unit,
    onDeviceAliasSave: (String) -> Unit,
    onNotificationAccessClick: () -> Unit,
    onOpenQrTab: () -> Unit,
    onUnpairDevices: () -> Unit
) {
    var aliasDraft by remember(prefsState.deviceAlias) { mutableStateOf(prefsState.deviceAlias) }

    // Continuity Engine Live Card (matches android_settings/screen.png)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = pal.surfaceContainer),
        border = BorderStroke(1.dp, pal.outlineVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(pal.primaryAccent.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Bolt, contentDescription = null, tint = pal.activeCardLight)
                }
                Column {
                    Text("Continuity Engine", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = pal.onSurface)
                    Text(
                        "${if (isBluetoothConnected) "Bluetooth E2EE Active" else "BLE Background"} • ${latencyMs?.let { "${it}ms" } ?: "--"}",
                        fontSize = 11.sp,
                        color = pal.secondaryText
                    )
                }
            }
            Row(
                modifier = Modifier.clip(CircleShape).background(pal.secondaryAccent.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(pal.secondaryAccent))
                Text("Live", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = pal.secondaryAccent)
            }
        }
    }

    // Continuity Service & Battery Controls
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = pal.surfaceContainer),
        border = BorderStroke(1.dp, pal.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Continuity Service & Power", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = pal.secondaryText)
            Column {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Wi-Fi Cooldown Timer", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = pal.onSurface)
                    Text("${prefsState.wifiCooldownSeconds}s", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = pal.primaryAccent)
                }
                Slider(
                    value = prefsState.wifiCooldownSeconds.toFloat(),
                    onValueChange = { onWifiCooldownChange(it.roundToInt()) },
                    valueRange = 5f..60f,
                    colors = SliderDefaults.colors(thumbColor = pal.primaryAccent, activeTrackColor = pal.primaryAccent)
                )
            }
            HorizontalDivider(thickness = 1.dp, color = pal.outlineVariant)
            SettingsSwitchRow(pal, "Bluetooth-Only Battery Saver", "Operate cleanly with zero Wi-Fi wakeups", prefsState.bleOnlyBatterySaver, onBleSaverChange)
            SettingsSwitchRow(pal, "Auto-Accept Files", "Prompt before writing incoming files if OFF", prefsState.autoAcceptFiles, onAutoAcceptFilesChange)
            SettingsSwitchRow(pal, "Clipboard Sync", "Universal copy & paste with Mac", clipboardSyncEnabled, onToggleClipboardSync)
            SettingsSwitchRow(
                pal,
                "Notification Mirroring",
                if (addonStatus == NotificationAddonStatus.ACTIVE) "Forward Android alerts to macOS" else "Tap to configure Notify Add-on",
                notificationMirrorEnabled && addonStatus == NotificationAddonStatus.ACTIVE
            ) { want ->
                if (want && addonStatus != NotificationAddonStatus.ACTIVE) {
                    onNotificationAccessClick()
                } else {
                    onToggleNotificationMirror(want)
                }
            }
        }
    }

    // Appearance & Custom Device Alias
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = pal.surfaceContainer),
        border = BorderStroke(1.dp, pal.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Appearance & Clipboard Direction", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = pal.secondaryText)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BridgeAccentTheme.entries.forEach { themeOpt ->
                    val swatch = paletteForTheme(themeOpt)
                    val selected = prefsState.accentTheme == themeOpt
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(pal.surfaceLow)
                            .clickable { onSelectTheme(themeOpt) }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .background(swatch.primaryAccent)
                            )
                            Text(
                                text = themeOpt.displayName.split(" ").first(),
                                fontSize = 9.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) pal.primaryAccent else pal.secondaryText
                            )
                        }
                    }
                }
            }
            SettingsSwitchRow(pal, "Compact Mode", "Dense layout spacing", prefsState.compactMode, onCompactModeChange)
            SettingsSwitchRow(pal, "Telemetry Footer", "Show LATENCY & E2EE bar", prefsState.showTelemetryFooter, onTelemetryFooterChange)

            ClipboardDirection.entries.chunked(2).forEach { pair ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { dir ->
                        val selected = prefsState.clipboardDirection == dir
                        OutlinedButton(
                            onClick = { onClipboardDirectionChange(dir) },
                            modifier = Modifier.weight(1f),
                            shape = CircleShape,
                            border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) pal.primaryAccent else pal.outlineVariant)
                        ) {
                            Text(dir.displayName, fontSize = 11.sp, color = if (selected) pal.primaryAccent else pal.onSurface)
                        }
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = aliasDraft,
                    onValueChange = { aliasDraft = it },
                    label = { Text("Device Name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
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
                    onClick = { onDeviceAliasSave(aliasDraft) },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = pal.primaryAccent)
                ) {
                    Text("Save", color = Color.White)
                }
            }
        }
    }

    // Paired Mac & Fingerprint Card
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = pal.surfaceContainer),
        border = BorderStroke(1.dp, pal.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.LaptopMac, contentDescription = null, tint = pal.activeCardLight)
                    Text(peerName, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = pal.onSurface)
                }
                Text(
                    text = if (isPaired) "● Paired" else "○ Unpaired",
                    fontSize = 11.sp,
                    color = if (isPaired) pal.secondaryAccent else pal.secondaryText
                )
            }

            if (isPaired && peerCertFingerprint.isNotEmpty()) {
                Text(
                    text = "PINNED TLS / X25519 FINGERPRINT\n${peerCertFingerprint.chunked(2).take(12).joinToString(":").uppercase()}…",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = pal.secondaryText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(pal.surfaceLowest)
                        .padding(10.dp)
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onNotificationAccessClick, modifier = Modifier.weight(1f), shape = CircleShape) {
                    Text("Notify Add-on", fontSize = 11.sp, color = pal.onSurface)
                }
                if (isPaired) {
                    Button(
                        onClick = onUnpairDevices,
                        modifier = Modifier.weight(1f),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0x33FF453A))
                    ) {
                        Text("Unpair Mac", fontSize = 11.sp, color = Color(0xFFFF453A))
                    }
                } else {
                    Button(
                        onClick = onOpenQrTab,
                        modifier = Modifier.weight(1f),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = pal.primaryAccent)
                    ) {
                        Text("Scan QR", fontSize = 11.sp, color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeQuickTile(
    icon: ImageVector,
    label: String,
    tint: Color,
    pal: BridgeThemePalette,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = pal.surfaceContainer),
        border = BorderStroke(1.dp, pal.outlineVariant)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier.size(34.dp).clip(CircleShape).background(tint.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(18.dp))
            }
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = pal.onSurface, maxLines = 1)
        }
    }
}

@Composable
private fun MediaIconBtn(
    icon: ImageVector,
    pal: BridgeThemePalette,
    modifier: Modifier,
    primary: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(CircleShape)
            .background(if (primary) pal.activeCardLight else pal.surfaceHigh)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = if (primary) pal.activeCardInk else pal.onSurface, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun SettingsSwitchRow(
    pal: BridgeThemePalette,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = pal.onSurface)
            Text(subtitle, fontSize = 11.sp, color = pal.secondaryText)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = pal.primaryAccent,
                uncheckedThumbColor = pal.secondaryText,
                uncheckedTrackColor = pal.surfaceHigh
            )
        )
    }
}

@Composable
private fun ReplyableNotificationCard(
    pal: BridgeThemePalette,
    item: NotificationEventPayload,
    onSendReply: (String) -> Unit
) {
    var replyDraft by remember { mutableStateOf("") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = pal.surfaceContainer),
        border = BorderStroke(1.dp, pal.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${item.appName} • ${item.title}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = pal.onSurface)
            Text(item.body, fontSize = 12.sp, color = pal.secondaryText)
            if (item.canReply) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = replyDraft,
                        onValueChange = { replyDraft = it },
                        placeholder = { Text("Reply inline...", fontSize = 12.sp, color = pal.secondaryText) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
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
                        onClick = {
                            if (replyDraft.isNotBlank()) {
                                onSendReply(replyDraft.trim())
                                replyDraft = ""
                            }
                        },
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = pal.primaryAccent)
                    ) {
                        Text("Send", fontSize = 12.sp, color = Color.White)
                    }
                }
            }
        }
    }
}
