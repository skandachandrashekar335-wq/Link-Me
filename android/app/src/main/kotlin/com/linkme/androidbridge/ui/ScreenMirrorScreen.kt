package com.linkme.androidbridge.ui

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FitScreen
import androidx.compose.material.icons.rounded.LaptopMac
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.linkme.androidbridge.service.BridgeActivityHub
import com.linkme.androidbridge.service.CursorControlAddonStatus
import com.linkme.androidbridge.service.RemoteControlIpcBridge
import com.linkme.androidbridge.service.ScreenMirrorService
import com.linkme.androidbridge.service.ScreenMirrorUiState
import com.linkme.androidbridge.ui.theme.LocalBridgePalette

@Composable
fun ScreenMirrorScreen(
    peerName: String,
    isPaired: Boolean,
    isWifiActive: Boolean,
    mirrorState: ScreenMirrorUiState
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val pal = LocalBridgePalette.current

    var zoomScale by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var useCropFill by remember { mutableStateOf(false) }
    var cursorStatus by remember {
        mutableStateOf(RemoteControlIpcBridge.inspectCursorStatus(context))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                cursorStatus = RemoteControlIpcBridge.inspectCursorStatus(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val svcIntent = Intent(context, ScreenMirrorService::class.java).apply {
                action = ScreenMirrorService.ACTION_START
                putExtra(ScreenMirrorService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenMirrorService.EXTRA_RESULT_DATA, result.data)
                putExtra(ScreenMirrorService.EXTRA_FPS, if (isWifiActive) 24 else 3)
                putExtra(ScreenMirrorService.EXTRA_QUALITY, 60)
            }
            ContextCompat.startForegroundService(context, svcIntent)
        }
    }

    val decodedBitmap = remember(mirrorState.latestJpegBytes) {
        mirrorState.latestJpegBytes?.let { bytes ->
            runCatching {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Non-blocking Cursor Control Add-on / Accessibility Banner (viewing & casting stay active)
        val cursorReady = cursorStatus == CursorControlAddonStatus.ACTIVE
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = pal.surfaceLow),
            border = BorderStroke(1.dp, if (cursorReady) pal.secondaryAccent else Color(0xFFFF9F0A))
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        if (cursorReady) Icons.Rounded.CheckCircle else Icons.Rounded.AccessibilityNew,
                        contentDescription = null,
                        tint = if (cursorReady) pal.secondaryAccent else Color(0xFFFF9F0A),
                        modifier = Modifier.size(22.dp)
                    )
                    Column(modifier = Modifier.padding(end = 8.dp)) {
                        Text(
                            text = when (cursorStatus) {
                                CursorControlAddonStatus.ACTIVE -> "Mac Cursor Control Ready"
                                CursorControlAddonStatus.COMPANION_NOT_INSTALLED -> "Install Companion Add-on for Remote Cursor Control"
                                CursorControlAddonStatus.ACCESSIBILITY_DISABLED -> "Enable Cursor Control (Accessibility)"
                            },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = pal.onSurface
                        )
                        Text(
                            text = when (cursorStatus) {
                                CursorControlAddonStatus.ACTIVE ->
                                    "Clicks, swipes & keyboard input from macOS Mirror route live via Companion Add-on."
                                CursorControlAddonStatus.COMPANION_NOT_INSTALLED ->
                                    "Screen viewing & casting stay active now. Install AndroidBridge-NotifyAddon APK for remote cursor control."
                                CursorControlAddonStatus.ACCESSIBILITY_DISABLED ->
                                    "Grant Accessibility inside Companion Add-on to control Android with your Mac mouse & keyboard."
                            },
                            fontSize = 11.sp,
                            color = pal.secondaryText
                        )
                    }
                }
                when (cursorStatus) {
                    CursorControlAddonStatus.COMPANION_NOT_INSTALLED -> {
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(
                                            Intent.ACTION_VIEW,
                                            Uri.parse("https://github.com/skandachandrashekar335-wq/Link-Me/releases")
                                        )
                                    )
                                }
                            },
                            shape = CircleShape,
                            border = BorderStroke(1.dp, pal.outlineVariant)
                        ) {
                            Text("Get Add-on", fontSize = 11.sp, color = pal.onSurface, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    CursorControlAddonStatus.ACCESSIBILITY_DISABLED -> {
                        Button(
                            onClick = {
                                runCatching {
                                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                }
                            },
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(containerColor = pal.primaryAccent)
                        ) {
                            Text("Enable", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    CursorControlAddonStatus.ACTIVE -> Unit
                }
            }
        }

        // Mirroring Controls Card
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
                    Column {
                        Text(
                            text = "LIVE SCREEN MIRROR",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = pal.secondaryAccent
                        )
                        Text(
                            text = peerName,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = pal.onSurface
                        )
                    }
                    Text(
                        text = if (isWifiActive) "Wi-Fi TLS 1.3 (24 fps)" else "BLE Preview (3 fps)",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = pal.secondaryText,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(pal.surfaceHigh)
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            val nextStart = !mirrorState.isViewingRemote
                            BridgeActivityHub.updateMirrorState {
                                it.copy(
                                    isViewingRemote = nextStart,
                                    statusText = if (nextStart) "Requesting Mac screen..." else "Idle"
                                )
                            }
                            BridgeActivityHub.onRequestRemoteScreenMirror?.invoke(
                                nextStart,
                                if (isWifiActive) 24 else 3,
                                60
                            )
                        },
                        enabled = isPaired,
                        modifier = Modifier.weight(1f),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (mirrorState.isViewingRemote) Color(0xFFFF453A) else pal.primaryAccent
                        )
                    ) {
                        Icon(
                            if (mirrorState.isViewingRemote) Icons.Rounded.StopCircle else Icons.Rounded.LaptopMac,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.size(6.dp))
                        Text(
                            if (mirrorState.isViewingRemote) "Stop View" else "View Mac Screen",
                            fontSize = 12.sp,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            if (mirrorState.isSharingLocal) {
                                val stopIntent = Intent(context, ScreenMirrorService::class.java).apply {
                                    action = ScreenMirrorService.ACTION_STOP
                                }
                                context.startService(stopIntent)
                            } else {
                                val mgr = context.getSystemService(MediaProjectionManager::class.java)
                                if (mgr != null) {
                                    projectionLauncher.launch(mgr.createScreenCaptureIntent())
                                }
                            }
                        },
                        enabled = isPaired,
                        modifier = Modifier.weight(1f),
                        shape = CircleShape,
                        border = BorderStroke(1.dp, if (mirrorState.isSharingLocal) Color(0xFFFF453A) else pal.outlineVariant)
                    ) {
                        Icon(
                            Icons.Rounded.Cast,
                            contentDescription = null,
                            tint = if (mirrorState.isSharingLocal) Color(0xFFFF453A) else pal.onSurface,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.size(6.dp))
                        Text(
                            if (mirrorState.isSharingLocal) "Stop Share" else "Share Phone Screen",
                            fontSize = 12.sp,
                            color = if (mirrorState.isSharingLocal) Color(0xFFFF453A) else pal.onSurface,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // Pinch-to-Zoom Viewport
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(400.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = pal.surfaceLowest),
            border = BorderStroke(1.dp, pal.outlineVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val nextScale = (zoomScale * zoom).coerceIn(1f, 4f)
                            zoomScale = nextScale
                            panOffset = if (nextScale > 1f) panOffset + pan else Offset.Zero
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                zoomScale = 1f
                                panOffset = Offset.Zero
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                if (decodedBitmap != null) {
                    Image(
                        bitmap = decodedBitmap,
                        contentDescription = "Mirrored Mac Screen",
                        contentScale = if (useCropFill) ContentScale.Crop else ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = zoomScale
                                scaleY = zoomScale
                                translationX = panOffset.x
                                translationY = panOffset.y
                            }
                    )

                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${mirrorState.remoteWidth}×${mirrorState.remoteHeight} • ${mirrorState.remoteFps} fps • ${"%.1f".format(zoomScale)}x",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color.White,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.65f))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        )

                        Button(
                            onClick = { useCropFill = !useCropFill },
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = 0.65f))
                        ) {
                            Icon(Icons.Rounded.FitScreen, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.size(4.dp))
                            Text(if (useCropFill) "Fill" else "Fit", fontSize = 10.sp, color = Color.White)
                        }
                    }
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Icon(Icons.Rounded.Cast, contentDescription = null, tint = pal.secondaryAccent, modifier = Modifier.size(36.dp))
                        Text(
                            text = if (isPaired) mirrorState.statusText else "Pair your Mac first to enable live screen mirroring.",
                            fontSize = 12.sp,
                            color = pal.secondaryText
                        )
                        Text(
                            text = "Double-tap or pinch to zoom viewport • E2EE ChaCha20 / TLS 1.3",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = pal.secondaryText
                        )
                    }
                }
            }
        }
    }
}
