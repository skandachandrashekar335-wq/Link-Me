package com.linkme.androidbridge.ui

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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.linkme.androidbridge.service.NotificationAddonStatus
import com.linkme.androidbridge.ui.theme.BridgeThemePalette
import com.linkme.androidbridge.ui.theme.LocalBridgePalette
import com.linkme.androidbridge.ui.theme.paletteForTheme
import kotlin.math.roundToInt

enum class AndroidStudioTab(val label: String, val iconGlyph: String) {
    BRIDGE("Bridge", "⇄"),
    ACTIVITY("Activity", "☰"),
    CUSTOMIZE("Customize", "⚙")
}

@Composable
fun BridgeHomeScreen(
    bridgeState: BridgeState,
    peerName: String,
    isPaired: Boolean,
    batteryPercent: Int,
    isCharging: Boolean,
    clipboardSyncEnabled: Boolean,
    notificationMirrorEnabled: Boolean,
    addonStatus: NotificationAddonStatus,
    prefsState: BridgePreferencesState,
    latencyMs: Int?,
    queuedShareCount: Int,
    pendingFileOffer: FileOfferPayload?,
    notifications: List<NotificationEventPayload>,
    activities: List<BridgeActivityItem>,
    onToggleClipboardSync: (Boolean) -> Unit,
    onToggleNotificationMirror: (Boolean) -> Unit,
    onScanQrClick: () -> Unit,
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
    var currentTab by remember { mutableStateOf(AndroidStudioTab.BRIDGE) }
    var activityFilter by remember { mutableStateOf<ActivityCategory?>(null) }

    Scaffold(
        containerColor = pal.surface,
        bottomBar = {
            Column {
                if (prefsState.showTelemetryFooter) {
                    HorizontalDivider(thickness = 1.dp, color = pal.outlineVariant)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(pal.surfaceLowest)
                            .padding(horizontal = 16.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val latLabel = latencyMs?.let { "${it}MS" } ?: "-- MS"
                        val transportTag = if (bridgeState == BridgeState.ACTIVE_WIFI_STREAM) "WIFI" else "BLE"
                        Text(
                            text = "LATENCY: $latLabel • $transportTag",
                            fontSize = 10.sp,
                            letterSpacing = 0.6.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = pal.secondaryAccent
                        )
                        Text(
                            text = "E2EE: TLS 1.3 PINNED",
                            fontSize = 10.sp,
                            letterSpacing = 0.6.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
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
                            icon = {
                                Text(
                                    text = tab.iconGlyph,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            label = {
                                Text(
                                    text = tab.label,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color.White,
                                selectedTextColor = pal.primaryAccent,
                                indicatorColor = pal.primaryAccent,
                                unselectedIconColor = pal.secondaryText,
                                unselectedTextColor = pal.secondaryText
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        val verticalGap = if (prefsState.compactMode) 10.dp else 14.dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(verticalGap)
        ) {
            when (currentTab) {
                AndroidStudioTab.BRIDGE -> BridgeTabView(
                    pal = pal,
                    bridgeState = bridgeState,
                    peerName = peerName,
                    isPaired = isPaired,
                    batteryPercent = batteryPercent,
                    isCharging = isCharging,
                    clipboardSyncEnabled = clipboardSyncEnabled,
                    notificationMirrorEnabled = notificationMirrorEnabled,
                    addonStatus = addonStatus,
                    prefsState = prefsState,
                    queuedShareCount = queuedShareCount,
                    pendingFileOffer = pendingFileOffer,
                    notifications = notifications,
                    onToggleClipboardSync = onToggleClipboardSync,
                    onToggleNotificationMirror = onToggleNotificationMirror,
                    onScanQrClick = onScanQrClick,
                    onSendFileClick = onSendFileClick,
                    onSendUrlClick = onSendUrlClick,
                    onRingMacClick = onRingMacClick,
                    onSyncClipboardClick = onSyncClipboardClick,
                    onNotificationAccessClick = onNotificationAccessClick,
                    onSendInlineReply = onSendInlineReply,
                    onMediaAction = onMediaAction,
                    onResolvePendingFile = onResolvePendingFile
                )

                AndroidStudioTab.ACTIVITY -> ActivityTabView(
                    pal = pal,
                    activities = activities,
                    selectedFilter = activityFilter,
                    onSelectFilter = { activityFilter = it },
                    onCopySnippet = onCopyActivitySnippet,
                    onOpenFile = onOpenActivityFile
                )

                AndroidStudioTab.CUSTOMIZE -> CustomizeTabView(
                    pal = pal,
                    prefsState = prefsState,
                    addonStatus = addonStatus,
                    isPaired = isPaired,
                    onSelectTheme = onSelectTheme,
                    onCompactModeChange = onCompactModeChange,
                    onTelemetryFooterChange = onTelemetryFooterChange,
                    onWifiCooldownChange = onWifiCooldownChange,
                    onBleSaverChange = onBleSaverChange,
                    onAutoAcceptFilesChange = onAutoAcceptFilesChange,
                    onClipboardDirectionChange = onClipboardDirectionChange,
                    onDeviceAliasSave = onDeviceAliasSave,
                    onNotificationAccessClick = onNotificationAccessClick,
                    onScanQrClick = onScanQrClick,
                    onUnpairDevices = onUnpairDevices
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
private fun BridgeTabView(
    pal: BridgeThemePalette,
    bridgeState: BridgeState,
    peerName: String,
    isPaired: Boolean,
    batteryPercent: Int,
    isCharging: Boolean,
    clipboardSyncEnabled: Boolean,
    notificationMirrorEnabled: Boolean,
    addonStatus: NotificationAddonStatus,
    prefsState: BridgePreferencesState,
    queuedShareCount: Int,
    pendingFileOffer: FileOfferPayload?,
    notifications: List<NotificationEventPayload>,
    onToggleClipboardSync: (Boolean) -> Unit,
    onToggleNotificationMirror: (Boolean) -> Unit,
    onScanQrClick: () -> Unit,
    onSendFileClick: () -> Unit,
    onSendUrlClick: () -> Unit,
    onRingMacClick: () -> Unit,
    onSyncClipboardClick: () -> Unit,
    onNotificationAccessClick: () -> Unit,
    onSendInlineReply: (String, String) -> Unit,
    onMediaAction: (MediaControlAction) -> Unit,
    onResolvePendingFile: (Boolean) -> Unit
) {
    val isTurbo = bridgeState == BridgeState.ACTIVE_WIFI_STREAM

    // 1. Header Card (#1B1C1E or theme equivalent)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = pal.surfaceLow),
        border = BorderStroke(1.dp, pal.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    Canvas(modifier = Modifier.size(40.dp)) {
                        val strokePx = 3.5.dp.toPx()
                        drawArc(
                            color = pal.surfaceHigh,
                            startAngle = 0f,
                            sweepAngle = 360f,
                            useCenter = false,
                            style = Stroke(width = strokePx)
                        )
                        drawArc(
                            color = pal.secondaryAccent,
                            startAngle = -90f,
                            sweepAngle = 360f * (batteryPercent.coerceIn(0, 100) / 100f),
                            useCenter = false,
                            style = Stroke(width = strokePx, cap = StrokeCap.Round)
                        )
                    }
                    Text(
                        text = if (isCharging) "⚡" else "$batteryPercent",
                        fontSize = if (isCharging) 14.sp else 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = pal.secondaryAccent
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = peerName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = pal.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val chargeLabel = if (isCharging) "CHARGING" else "BATTERY"
                    val pairLabel = if (isPaired) "PAIRED" else "UNPAIRED"
                    Text(
                        text = "$batteryPercent% $chargeLabel • $pairLabel • ${prefsState.deviceAlias.uppercase()}",
                        fontSize = 10.sp,
                        letterSpacing = 0.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = pal.secondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(pal.surfaceHigh)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (isTurbo) pal.secondaryAccent else pal.primaryAccent)
                )
                Text(
                    text = if (prefsState.bleOnlyBatterySaver) {
                        "BLE ECO LOCK"
                    } else if (isTurbo) {
                        "WI-FI TURBO 5GHZ"
                    } else {
                        "BLE STANDBY"
                    },
                    fontSize = 10.sp,
                    letterSpacing = 0.6.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = if (isTurbo) pal.secondaryAccent else pal.onSurface
                )
            }
        }
    }

    // Pending Incoming File Approval Prompt (when autoAcceptFiles == false)
    if (pendingFileOffer != null) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = pal.surfaceContainer),
            border = BorderStroke(1.dp, pal.primaryAccent)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "INCOMING FILE OFFER",
                    fontSize = 10.sp,
                    letterSpacing = 0.6.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = pal.primaryAccent
                )
                Text(
                    text = "${pendingFileOffer.filename} (${pendingFileOffer.byteSize / 1024} KB)",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = pal.onSurface
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onResolvePendingFile(false) },
                        modifier = Modifier.weight(1f),
                        border = BorderStroke(1.dp, pal.outlineVariant)
                    ) {
                        Text("Decline", color = pal.onSurface)
                    }
                    Button(
                        onClick = { onResolvePendingFile(true) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = pal.primaryAccent)
                    ) {
                        Text("Accept", color = Color.White)
                    }
                }
            }
        }
    }

    // 2. Beam Files Dropzone
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(pal.surfaceLowest)
            .clickable(onClick = onSendFileClick)
            .padding(1.dp)
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            drawRoundRect(
                color = pal.outlineVariant,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()),
                style = Stroke(
                    width = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                )
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = if (prefsState.compactMode) 12.dp else 18.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(pal.surfaceHigh),
                contentAlignment = Alignment.Center
            ) {
                Text("↑", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = pal.secondaryAccent)
            }
            Text(
                text = "Beam files to Mac",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = pal.onSurface
            )
            Text(
                text = if (queuedShareCount > 0) {
                    "$queuedShareCount QUEUED • MAX 4.0 GB • DIRECT P2P"
                } else {
                    "MAX 4.0 GB • DIRECT P2P"
                },
                fontSize = 10.sp,
                letterSpacing = 0.6.sp,
                fontFamily = FontFamily.Monospace,
                color = pal.secondaryText
            )
        }
    }

    // 3. 2x2 Action Grid: Scan Mac QR / Send File / Send URL / Ring Mac
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onScanQrClick,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = pal.primaryAccent, contentColor = Color.White)
            ) {
                Text("Scan Mac QR", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(
                onClick = onSendFileClick,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, pal.outlineVariant)
            ) {
                Text("Send File", fontSize = 12.sp, color = pal.onSurface)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onSendUrlClick,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, pal.outlineVariant)
            ) {
                Text("Send URL", fontSize = 12.sp, color = pal.onSurface)
            }
            OutlinedButton(
                onClick = onRingMacClick,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, pal.outlineVariant)
            ) {
                Text("Ring Mac", fontSize = 12.sp, color = pal.onSurface)
            }
        }
        OutlinedButton(
            onClick = onSyncClipboardClick,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, pal.outlineVariant)
        ) {
            Text("Sync Clipboard Now", fontSize = 12.sp, color = pal.onSurface)
        }
    }

    // 4. Remote Media Deck (Controls Mac playback & volume via 0x0A MEDIA_CONTROL)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = pal.surfaceContainer),
        border = BorderStroke(1.dp, pal.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "REMOTE MEDIA DECK (MAC)",
                fontSize = 10.sp,
                letterSpacing = 0.6.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = pal.secondaryText
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MediaDeckButton("⏮", Modifier.weight(1f), pal) { onMediaAction(MediaControlAction.PREV) }
                MediaDeckButton("⏯", Modifier.weight(1f), pal, primary = true) { onMediaAction(MediaControlAction.PLAY_PAUSE) }
                MediaDeckButton("⏭", Modifier.weight(1f), pal) { onMediaAction(MediaControlAction.NEXT) }
                MediaDeckButton("🔉", Modifier.weight(1f), pal) { onMediaAction(MediaControlAction.VOL_DOWN) }
                MediaDeckButton("🔇", Modifier.weight(1f), pal) { onMediaAction(MediaControlAction.MUTE) }
                MediaDeckButton("🔊", Modifier.weight(1f), pal) { onMediaAction(MediaControlAction.VOL_UP) }
            }
        }
    }

    // 5. Quick Switches + Optional Notification Add-on Status
    StitchSwitchCard(
        pal = pal,
        title = "Universal Clipboard Sync",
        subtitle = prefsState.clipboardDirection.displayName,
        checked = clipboardSyncEnabled,
        onCheckedChange = onToggleClipboardSync
    )
    StitchSwitchCard(
        pal = pal,
        title = "Notification Mirroring",
        subtitle = when (addonStatus) {
            NotificationAddonStatus.ACTIVE -> "Interactive replies"
            NotificationAddonStatus.INSTALLED_NEEDS_PERMISSION -> "Notification access required"
            NotificationAddonStatus.NOT_INSTALLED -> "Optional add-on required"
        },
        checked = notificationMirrorEnabled && addonStatus == NotificationAddonStatus.ACTIVE,
        onCheckedChange = { want ->
            if (want && addonStatus != NotificationAddonStatus.ACTIVE) {
                onNotificationAccessClick()
            } else {
                onToggleNotificationMirror(want)
            }
        }
    )

    if (addonStatus != NotificationAddonStatus.ACTIVE) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = pal.surfaceLow),
            border = BorderStroke(1.dp, pal.outlineVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f).padding(end = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = "Enable Optional Notification Add-on",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = pal.onSurface
                    )
                    Text(
                        text = if (addonStatus == NotificationAddonStatus.INSTALLED_NEEDS_PERMISSION) {
                            "Add-on installed — grant Notification Access in Android Settings."
                        } else {
                            "Core APK is Play-Protect clean. Install AndroidBridge-NotifyAddon APK."
                        },
                        fontSize = 11.sp,
                        color = pal.secondaryText
                    )
                }
                Button(
                    onClick = onNotificationAccessClick,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = pal.primaryAccent, contentColor = Color.White)
                ) {
                    Text(
                        text = if (addonStatus == NotificationAddonStatus.INSTALLED_NEEDS_PERMISSION) "Grant" else "Get Add-on",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }

    if (notifications.isNotEmpty()) {
        Text(
            text = "RECENT NOTIFICATIONS",
            fontSize = 10.sp,
            letterSpacing = 0.6.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = pal.secondaryText
        )
        notifications.take(3).forEach { item ->
            if (item.canReply) {
                ReplyableNotificationCard(
                    pal = pal,
                    item = item,
                    onSendReply = { text -> onSendInlineReply(item.sbnKey, text) }
                )
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = pal.surfaceContainer),
                    border = BorderStroke(1.dp, pal.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(pal.secondaryAccent.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("•", fontSize = 14.sp, color = pal.secondaryAccent)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                text = "${item.appName.uppercase()} • ${item.title}",
                                fontSize = 10.sp,
                                letterSpacing = 0.6.sp,
                                fontFamily = FontFamily.Monospace,
                                color = pal.secondaryText
                            )
                            Text(text = item.body, style = MaterialTheme.typography.bodyMedium, color = pal.onSurface)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityTabView(
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
        val options: List<Pair<String, ActivityCategory?>> = listOf(
            "All" to null,
            "Files" to ActivityCategory.FILES,
            "Clipboard" to ActivityCategory.CLIPBOARD,
            "Transport" to ActivityCategory.TRANSPORT
        )
        options.forEach { (label, cat) ->
            val selected = selectedFilter == cat
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) pal.primaryAccent else pal.surfaceContainer)
                    .clickable { onSelectFilter(cat) }
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            ) {
                Text(
                    text = label,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) Color.White else pal.onSurface
                )
            }
        }
    }

    val filtered = if (selectedFilter == null) activities else activities.filter { it.category == selectedFilter }
    if (filtered.isEmpty()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = pal.surfaceContainer),
            border = BorderStroke(1.dp, pal.outlineVariant)
        ) {
            Text(
                text = "No activity recorded yet. Beam a file, sync clipboard, or send a URL.",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = pal.secondaryText,
                modifier = Modifier.padding(14.dp)
            )
        }
    } else {
        filtered.forEach { item ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = pal.surfaceContainer),
                border = BorderStroke(1.dp, pal.outlineVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            text = "${item.category.name} • ${item.title}",
                            fontSize = 10.sp,
                            letterSpacing = 0.5.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = pal.secondaryAccent
                        )
                        Text(
                            text = item.detail,
                            style = MaterialTheme.typography.bodyMedium,
                            color = pal.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (item.fileUriString != null) {
                        OutlinedButton(
                            onClick = { onOpenFile(item.fileUriString) },
                            border = BorderStroke(1.dp, pal.outlineVariant)
                        ) {
                            Text("Open", fontSize = 11.sp, color = pal.onSurface)
                        }
                    } else if (item.clipboardText != null) {
                        OutlinedButton(
                            onClick = { onCopySnippet(item.clipboardText) },
                            border = BorderStroke(1.dp, pal.outlineVariant)
                        ) {
                            Text("Copy Again", fontSize = 11.sp, color = pal.onSurface)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomizeTabView(
    pal: BridgeThemePalette,
    prefsState: BridgePreferencesState,
    addonStatus: NotificationAddonStatus,
    isPaired: Boolean,
    onSelectTheme: (BridgeAccentTheme) -> Unit,
    onCompactModeChange: (Boolean) -> Unit,
    onTelemetryFooterChange: (Boolean) -> Unit,
    onWifiCooldownChange: (Int) -> Unit,
    onBleSaverChange: (Boolean) -> Unit,
    onAutoAcceptFilesChange: (Boolean) -> Unit,
    onClipboardDirectionChange: (ClipboardDirection) -> Unit,
    onDeviceAliasSave: (String) -> Unit,
    onNotificationAccessClick: () -> Unit,
    onScanQrClick: () -> Unit,
    onUnpairDevices: () -> Unit
) {
    var aliasDraft by remember(prefsState.deviceAlias) { mutableStateOf(prefsState.deviceAlias) }

    // 1. Live Accent Theme Swatches
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = pal.surfaceContainer),
        border = BorderStroke(1.dp, pal.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "ACCENT THEME PALETTE",
                fontSize = 10.sp,
                letterSpacing = 0.6.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = pal.secondaryText
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BridgeAccentTheme.entries.forEach { themeOption ->
                    val swatch = paletteForTheme(themeOption)
                    val selected = prefsState.accentTheme == themeOption
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onSelectTheme(themeOption) },
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = pal.surfaceLow),
                        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) pal.primaryAccent else pal.outlineVariant)
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                Box(Modifier.size(10.dp).clip(CircleShape).background(swatch.surface))
                                Box(Modifier.size(10.dp).clip(CircleShape).background(swatch.primaryAccent))
                                Box(Modifier.size(10.dp).clip(CircleShape).background(swatch.secondaryAccent))
                            }
                            Text(
                                text = themeOption.displayName,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = pal.onSurface
                            )
                        }
                    }
                }
            }
        }
    }

    // 2. Transport & Battery Engine Controls
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = pal.surfaceContainer),
        border = BorderStroke(1.dp, pal.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "TRANSPORT & BATTERY ENGINE",
                fontSize = 10.sp,
                letterSpacing = 0.6.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = pal.secondaryText
            )
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Wi-Fi Cooldown Timer", style = MaterialTheme.typography.titleSmall, color = pal.onSurface)
                    Text(
                        "${prefsState.wifiCooldownSeconds}s",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = pal.primaryAccent
                    )
                }
                Slider(
                    value = prefsState.wifiCooldownSeconds.toFloat(),
                    onValueChange = { onWifiCooldownChange(it.roundToInt()) },
                    valueRange = 5f..60f,
                    colors = SliderDefaults.colors(
                        thumbColor = pal.primaryAccent,
                        activeTrackColor = pal.primaryAccent,
                        inactiveTrackColor = pal.surfaceHigh
                    )
                )
            }
            HorizontalDivider(thickness = 1.dp, color = pal.outlineVariant)
            StitchSwitchRow(pal, "BLE-Only Battery Saver", "Block auto Wi-Fi wakeups", prefsState.bleOnlyBatterySaver, onBleSaverChange)
            StitchSwitchRow(pal, "Auto-Accept Files", "Prompt before writing if OFF", prefsState.autoAcceptFiles, onAutoAcceptFilesChange)
            StitchSwitchRow(pal, "Compact Density Mode", "Dense telemetry layout", prefsState.compactMode, onCompactModeChange)
            StitchSwitchRow(pal, "Show Telemetry Footer", "LATENCY • E2EE • TRANSPORT", prefsState.showTelemetryFooter, onTelemetryFooterChange)
        }
    }

    // 3. Clipboard Direction & Device Alias
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = pal.surfaceContainer),
        border = BorderStroke(1.dp, pal.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "CLIPBOARD DIRECTION",
                fontSize = 10.sp,
                letterSpacing = 0.6.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = pal.secondaryText
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ClipboardDirection.entries.chunked(2).forEach { pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        pair.forEach { dir ->
                            val selected = prefsState.clipboardDirection == dir
                            OutlinedButton(
                                onClick = { onClipboardDirectionChange(dir) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(
                                    width = if (selected) 2.dp else 1.dp,
                                    color = if (selected) pal.primaryAccent else pal.outlineVariant
                                )
                            ) {
                                Text(
                                    text = dir.displayName,
                                    fontSize = 11.sp,
                                    color = if (selected) pal.primaryAccent else pal.onSurface
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(thickness = 1.dp, color = pal.outlineVariant)

            Text(
                text = "CUSTOM DEVICE NAME",
                fontSize = 10.sp,
                letterSpacing = 0.6.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = pal.secondaryText
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = aliasDraft,
                    onValueChange = { aliasDraft = it },
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
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = pal.primaryAccent, contentColor = Color.White)
                ) {
                    Text("Save", fontSize = 12.sp)
                }
            }
        }
    }

    // 4. Add-on & Pairing Management
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = pal.surfaceContainer),
        border = BorderStroke(1.dp, pal.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "NOTIFICATION ADD-ON & PAIRING",
                fontSize = 10.sp,
                letterSpacing = 0.6.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = pal.secondaryText
            )
            Text(
                text = when (addonStatus) {
                    NotificationAddonStatus.ACTIVE -> "Notification Add-on status: ACTIVE"
                    NotificationAddonStatus.INSTALLED_NEEDS_PERMISSION -> "Add-on installed — tap below to grant Notification Access."
                    NotificationAddonStatus.NOT_INSTALLED -> "Add-on not installed — core app remains 100% Play-Protect clean."
                },
                fontSize = 12.sp,
                color = pal.onSurface
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onNotificationAccessClick,
                    modifier = Modifier.weight(1f),
                    border = BorderStroke(1.dp, pal.outlineVariant)
                ) {
                    Text("Setup Add-on", fontSize = 12.sp, color = pal.onSurface)
                }
                if (isPaired) {
                    OutlinedButton(
                        onClick = onUnpairDevices,
                        modifier = Modifier.weight(1f),
                        border = BorderStroke(1.dp, pal.primaryAccent)
                    ) {
                        Text("Unpair", fontSize = 12.sp, color = pal.primaryAccent)
                    }
                } else {
                    Button(
                        onClick = onScanQrClick,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = pal.primaryAccent, contentColor = Color.White)
                    ) {
                        Text("Pair Mac", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaDeckButton(
    glyph: String,
    modifier: Modifier,
    pal: BridgeThemePalette,
    primary: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (primary) pal.primaryAccent else pal.surfaceHigh)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = glyph,
            fontSize = 14.sp,
            color = if (primary) Color.White else pal.onSurface
        )
    }
}

@Composable
private fun StitchSwitchCard(
    pal: BridgeThemePalette,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = pal.surfaceContainer),
        border = BorderStroke(1.dp, pal.outlineVariant)
    ) {
        Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            StitchSwitchRow(pal, title, subtitle, checked, onCheckedChange)
        }
    }
}

@Composable
private fun StitchSwitchRow(
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
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = pal.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = pal.secondaryText
            )
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
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = pal.activeCardLight)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "${item.appName.uppercase()} • ${item.title}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = pal.activeCardInk.copy(alpha = 0.75f)
            )
            Text(
                text = item.body,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = pal.activeCardInk
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
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
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = pal.primaryAccent,
                        contentColor = Color.White
                    )
                ) {
                    Text("Send", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
