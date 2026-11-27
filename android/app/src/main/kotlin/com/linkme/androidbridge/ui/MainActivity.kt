package com.linkme.androidbridge.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.linkme.androidbridge.core.crypto.TrustStore
import com.linkme.androidbridge.core.crypto.TrustedPeer
import com.linkme.androidbridge.core.protocol.BatteryStateEvent
import com.linkme.androidbridge.core.protocol.BatteryStateMachine
import com.linkme.androidbridge.service.BridgeForegroundService
import com.linkme.androidbridge.ui.theme.AndroidBridgeTheme

class MainActivity : ComponentActivity() {
    private lateinit var trustStore: TrustStore
    private val sharedUris = mutableListOf<Uri>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        trustStore = TrustStore.forContext(this)

        ContextCompat.startForegroundService(
            this,
            Intent(this, BridgeForegroundService::class.java)
        )
        extractSharedUris(intent)

        setContent {
            AndroidBridgeTheme {
                val stateMachine = remember { BatteryStateMachine() }
                var currentState by remember { mutableStateOf(stateMachine.state) }
                var showQrPairing by remember { mutableStateOf(false) }
                var queuedShares by remember { mutableIntStateOf(sharedUris.size) }
                var pairedPeerName by remember {
                    mutableStateOf(trustStore.allPeers().firstOrNull()?.deviceName ?: "No Mac Paired")
                }

                if (showQrPairing) {
                    QrScannerScreen(
                        onConfirmTrustedPeer = { peerId, peerName, certSha256, bleSecret ->
                            trustStore.savePeer(
                                TrustedPeer(
                                    deviceId = peerId,
                                    deviceName = peerName,
                                    pinnedCertSha256 = certSha256,
                                    sharedBleSecret = bleSecret
                                )
                            )
                            pairedPeerName = peerName
                            showQrPairing = false
                        },
                        onCancel = { showQrPairing = false }
                    )
                } else {
                    BridgeHomeScreen(
                        bridgeState = currentState,
                        peerName = pairedPeerName,
                        batteryPercent = 84,
                        queuedShareCount = queuedShares,
                        onWakeWifi = {
                            stateMachine.handle(BatteryStateEvent.LOCAL_ACTIVITY_TRIGGERED)
                            currentState = stateMachine.handle(BatteryStateEvent.MTLS_HANDSHAKE_SUCCEEDED)
                        },
                        onEnterCooldown = {
                            currentState = stateMachine.handle(BatteryStateEvent.INACTIVITY_THRESHOLD_REACHED)
                        },
                        onOpenPairing = { showQrPairing = true }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        extractSharedUris(intent)
    }

    private fun extractSharedUris(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (uri != null) sharedUris.add(uri)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                if (uris != null) sharedUris.addAll(uris)
            }
        }
    }
}
