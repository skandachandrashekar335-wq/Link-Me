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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linkme.androidbridge.core.protocol.BridgeState
import com.linkme.androidbridge.core.protocol.NotificationEventPayload
import com.linkme.androidbridge.ui.theme.StitchTokens

@Composable
fun BridgeHomeScreen(
    bridgeState: BridgeState,
    peerName: String,
    isPaired: Boolean,
    batteryPercent: Int,
    isCharging: Boolean,
    clipboardSyncEnabled: Boolean,
    notificationMirrorEnabled: Boolean,
    latencyMs: Int?,
    queuedShareCount: Int,
    notifications: List<NotificationEventPayload>,
    onToggleClipboardSync: (Boolean) -> Unit,
    onToggleNotificationMirror: (Boolean) -> Unit,
    onScanQrClick: () -> Unit,
    onSendFileClick: () -> Unit,
    onSyncClipboardClick: () -> Unit,
    onNotificationAccessClick: () -> Unit,
    onSendInlineReply: (sbnKey: String, replyText: String) -> Unit
) {
    val isTurbo = bridgeState == BridgeState.ACTIVE_WIFI_STREAM

    Scaffold(
        containerColor = StitchTokens.Surface,
        bottomBar = {
            Column {
                HorizontalDivider(thickness = 1.dp, color = StitchTokens.OutlineVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(StitchTokens.SurfaceLowest)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val latLabel = latencyMs?.let { "${it}MS" } ?: "-- MS"
                    Text(
                        text = "LATENCY: $latLabel",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = StitchTokens.TertiaryMint
                    )
                    Text(
                        text = "E2EE: TLS 1.3 PINNED",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = StitchTokens.SecondaryText
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Header: 40x40 Circular Battery Ring (#74D8BD) + Bolt + Device + Status Pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier.size(40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.size(40.dp)) {
                            val strokePx = 3.5.dp.toPx()
                            drawArc(
                                color = StitchTokens.SurfaceHigh,
                                startAngle = 0f,
                                sweepAngle = 360f,
                                useCenter = false,
                                style = Stroke(width = strokePx)
                            )
                            drawArc(
                                color = StitchTokens.TertiaryMint,
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
                            color = StitchTokens.TertiaryMint
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = peerName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = StitchTokens.OnSurface
                        )
                        val chargeLabel = if (isCharging) "CHARGING" else "BATTERY"
                        val pairLabel = if (isPaired) "PAIRED" else "UNPAIRED"
                        Text(
                            text = "$batteryPercent% $chargeLabel • $pairLabel",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = StitchTokens.SecondaryText
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(
                            if (isTurbo) StitchTokens.TertiaryMint.copy(alpha = 0.16f)
                            else StitchTokens.SurfaceHigh
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (isTurbo) "WI-FI TURBO 5GHZ" else "BLE STANDBY",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = if (isTurbo) StitchTokens.TertiaryMint else StitchTokens.OnSurface
                    )
                }
            }

            // 2. Beam Files Card (#0D0E10 dashed/hairline)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(StitchTokens.SurfaceLowest)
                    .clickable(onClick = onSendFileClick)
                    .padding(1.dp)
            ) {
                Canvas(modifier = Modifier.matchParentSize()) {
                    drawRoundRect(
                        color = StitchTokens.OutlineVariant,
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
                        .padding(vertical = 18.dp, horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        text = "↑ BEAM FILES TO MAC",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = StitchTokens.OnSurface
                    )
                    Text(
                        text = if (queuedShareCount > 0) {
                            "$queuedShareCount QUEUED • MAX 4.0 GB • DIRECT P2P"
                        } else {
                            "MAX 4.0 GB • DIRECT P2P"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = StitchTokens.SecondaryText
                    )
                }
            }

            // 3. Feature Switch Cards (#1F2022 + #5C3F40 hairline + #E11D48 Switch)
            StitchSwitchCard(
                title = "Universal Clipboard Sync",
                subtitle = "macOS ↔ Android",
                checked = clipboardSyncEnabled,
                onCheckedChange = onToggleClipboardSync
            )
            StitchSwitchCard(
                title = "Notification Mirroring",
                subtitle = "Interactive replies",
                checked = notificationMirrorEnabled,
                onCheckedChange = onToggleNotificationMirror
            )

            // Quick Actions Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onScanQrClick,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StitchTokens.PrimaryCrimson,
                        contentColor = Color.White
                    )
                ) {
                    Text("Scan Mac QR", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(
                    onClick = onSyncClipboardClick,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, StitchTokens.OutlineVariant)
                ) {
                    Text("Sync Clip", fontSize = 12.sp, color = StitchTokens.OnSurface)
                }
                OutlinedButton(
                    onClick = onNotificationAccessClick,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, StitchTokens.OutlineVariant)
                ) {
                    Text("Notifs", fontSize = 12.sp, color = StitchTokens.OnSurface)
                }
            }

            // 4. Recent Notifications Feed (#E4E2DE replyable vs #1F2022 non-replyable)
            Text(
                text = "RECENT NOTIFICATIONS",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = StitchTokens.SecondaryText
            )

            if (notifications.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = StitchTokens.SurfaceContainer),
                    border = BorderStroke(1.dp, StitchTokens.OutlineVariant)
                ) {
                    Text(
                        text = "No mirrored notifications yet",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = StitchTokens.SecondaryText,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            } else {
                notifications.take(4).forEach { item ->
                    if (item.canReply) {
                        ReplyableNotificationCard(
                            item = item,
                            onSendReply = { text -> onSendInlineReply(item.sbnKey, text) }
                        )
                    } else {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = StitchTokens.SurfaceContainer),
                            border = BorderStroke(1.dp, StitchTokens.OutlineVariant)
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text(
                                    text = "${item.appName.uppercase()} • ${item.title}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = StitchTokens.SecondaryText
                                )
                                Text(
                                    text = item.body,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = StitchTokens.OnSurface
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}

@Composable
private fun StitchSwitchCard(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = StitchTokens.SurfaceContainer),
        border = BorderStroke(1.dp, StitchTokens.OutlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = StitchTokens.OnSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = StitchTokens.SecondaryText
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = StitchTokens.PrimaryCrimson,
                    uncheckedThumbColor = StitchTokens.SecondaryText,
                    uncheckedTrackColor = StitchTokens.SurfaceHigh
                )
            )
        }
    }
}

@Composable
private fun ReplyableNotificationCard(
    item: NotificationEventPayload,
    onSendReply: (String) -> Unit
) {
    var replyDraft by remember { mutableStateOf("") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = StitchTokens.ActiveCardLight)
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
                color = StitchTokens.ActiveCardInk.copy(alpha = 0.75f)
            )
            Text(
                text = item.body,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = StitchTokens.ActiveCardInk
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = replyDraft,
                    onValueChange = { replyDraft = it },
                    placeholder = { Text("Reply inline...", fontSize = 12.sp, color = StitchTokens.SecondaryText) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = StitchTokens.SurfaceLowest,
                        unfocusedContainerColor = StitchTokens.SurfaceLowest,
                        focusedTextColor = StitchTokens.OnSurface,
                        unfocusedTextColor = StitchTokens.OnSurface,
                        focusedBorderColor = StitchTokens.PrimaryCrimson,
                        unfocusedBorderColor = StitchTokens.OutlineVariant
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
                        containerColor = StitchTokens.PrimaryCrimson,
                        contentColor = Color.White
                    )
                ) {
                    Text("Send", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
