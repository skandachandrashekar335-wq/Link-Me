package com.linkme.androidbridge.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.linkme.androidbridge.core.protocol.BridgeState
import com.linkme.androidbridge.ui.theme.WarmIndustrialPalette

@Composable
fun BridgeHomeScreen(
    bridgeState: BridgeState,
    peerName: String,
    batteryPercent: Int,
    serviceActive: Boolean,
    queuedShareCount: Int,
    logs: List<String>,
    onScanQrClick: () -> Unit,
    onSendFileClick: () -> Unit,
    onSyncClipboardClick: () -> Unit,
    onNotificationAccessClick: () -> Unit,
    onToggleServiceClick: () -> Unit
) {
    val borderColor = MaterialTheme.colorScheme.outline
    val isTurbo = bridgeState == BridgeState.ACTIVE_WIFI_STREAM

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "LINK-ME // ANDROIDBRIDGE",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = WarmIndustrialPalette.CrimsonAccent,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Continuity Node",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    text = "v0.1.1",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Live Connection & mTLS 1.3 Security Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, borderColor)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isTurbo) WarmIndustrialPalette.SignalGreen
                                        else WarmIndustrialPalette.CrimsonAccent
                                    )
                            )
                            Text(
                                text = peerName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            text = if (isTurbo) "WI-FI TURBO" else "BLE STANDBY",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = if (isTurbo) WarmIndustrialPalette.SignalGreen else WarmIndustrialPalette.CrimsonAccent
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "mTLS 1.3 · Keystore Pinned",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "BAT $batteryPercent%",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    if (queuedShareCount > 0) {
                        Text(
                            text = "QUEUED SHARE ITEMS: $queuedShareCount",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = WarmIndustrialPalette.CrimsonAccent
                        )
                    }
                }
            }

            // Primary CTA: Scan Mac QR Code
            Button(
                onClick = onScanQrClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = WarmIndustrialPalette.CrimsonAccent,
                    contentColor = androidx.compose.ui.graphics.Color.White
                )
            ) {
                Text(
                    text = "Scan Mac QR Code",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // 2x2 Quick Actions Grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                IndustrialActionTile(
                    title = "Send File",
                    subtitle = "64 KB mTLS Stream",
                    modifier = Modifier.weight(1f),
                    borderColor = borderColor,
                    onClick = onSendFileClick
                )
                IndustrialActionTile(
                    title = "Sync Clipboard",
                    subtitle = "Loop-Safe Push",
                    modifier = Modifier.weight(1f),
                    borderColor = borderColor,
                    onClick = onSyncClipboardClick
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                IndustrialActionTile(
                    title = "Notification Access",
                    subtitle = "Inline Reply Listener",
                    modifier = Modifier.weight(1f),
                    borderColor = borderColor,
                    onClick = onNotificationAccessClick
                )
                IndustrialActionTile(
                    title = if (serviceActive) "Bridge Online" else "Start Bridge",
                    subtitle = "connectedDevice FGS",
                    modifier = Modifier.weight(1f),
                    borderColor = borderColor,
                    onClick = onToggleServiceClick
                )
            }

            // Security & Transfer Telemetry Feed
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, borderColor)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "SECURITY & TRANSFER LOG",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    logs.forEach { entry ->
                        Text(
                            text = "> $entry",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IndustrialActionTile(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    borderColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(68.dp),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
