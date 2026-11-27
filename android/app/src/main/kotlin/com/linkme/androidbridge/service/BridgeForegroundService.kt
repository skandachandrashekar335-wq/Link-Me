package com.linkme.androidbridge.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.linkme.androidbridge.core.crypto.BlePrivacyBeacon
import com.linkme.androidbridge.core.crypto.IdentityManager
import com.linkme.androidbridge.core.crypto.TrustStore
import com.linkme.androidbridge.core.protocol.BridgeState
import com.linkme.androidbridge.core.protocol.ClipboardSyncPayload
import com.linkme.androidbridge.core.protocol.DeviceCommandPayload
import com.linkme.androidbridge.core.protocol.FileChunkPacket
import com.linkme.androidbridge.core.protocol.FileOfferPayload
import com.linkme.androidbridge.core.protocol.WakeWifiPayload
import com.linkme.androidbridge.core.protocol.WireFrame
import com.linkme.androidbridge.core.protocol.WireMessageType
import com.linkme.androidbridge.core.transport.BlePeripheralManager
import com.linkme.androidbridge.core.transport.TransferPriority
import com.linkme.androidbridge.core.transport.TransportManager
import com.linkme.androidbridge.core.transport.WifiTlsClient
import com.linkme.androidbridge.core.transport.WifiTlsServer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * connectedDevice ForegroundService wiring IdentityManager, Keystore-encrypted TrustStore,
 * BlePeripheralManager, on-demand WifiTlsServer, and all 5 continuity features.
 */
class BridgeForegroundService : Service() {
    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var identityManager: IdentityManager
    private lateinit var trustStore: TrustStore
    private lateinit var transportManager: TransportManager
    private lateinit var clipboardSyncManager: ClipboardSyncManager
    private lateinit var fileTransferManager: FileTransferManager
    private lateinit var telemetryFeature: TelemetryFeature

    private var blePeripheralManager: BlePeripheralManager? = null
    private var wifiTlsServer: WifiTlsServer? = null
    private var wifiTlsClient: WifiTlsClient? = null
    private var powerSaveReceiver: BroadcastReceiver? = null
    private var batteryReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        if (!hasRequiredTransportPermissions()) {
            stopSelf()
            return
        }

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(BridgeState.DORMANT_BLE),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            }
        )

        identityManager = IdentityManager(useAndroidKeyStore = true)
        val localIdentity = identityManager.loadOrCreateIdentity()
        trustStore = TrustStore.forContext(this)

        val sysClipboard = getSystemService(ClipboardManager::class.java)
        val clipAdapter = sysClipboard?.let { AndroidClipboardAdapter(it) }
        fileTransferManager = FileTransferManager(File(cacheDir, "incoming_transfers"))
        telemetryFeature = TelemetryFeature(localIdentity.deviceId)

        transportManager = TransportManager(
            bleSender = { frame -> blePeripheralManager?.sendControlFrame(frame) },
            wifiOpener = { startOnDemandWifiServer() },
            wifiSender = { frame -> wifiTlsClient?.send(frame) },
            wifiCloser = { stopWifiTransport() }
        )

        clipboardSyncManager = ClipboardSyncManager(
            deviceId = localIdentity.deviceId,
            adapter = clipAdapter,
            transport = transportManager
        )

        val pairedPeer = trustStore.allPeers().firstOrNull()
        val beacon = pairedPeer?.let { BlePrivacyBeacon(it.sharedBleSecret) }
        val peripheral = BlePeripheralManager(
            context = this,
            beacon = beacon,
            onInboundFrame = { frame -> routeInboundFrame(frame) }
        )
        blePeripheralManager = peripheral

        val powerMgr = getSystemService(PowerManager::class.java)
        peripheral.setBatterySaverActive(powerMgr?.isPowerSaveMode == true)

        val powerReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) {
                    val isSaver = context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
                    blePeripheralManager?.setBatterySaverActive(isSaver)
                }
            }
        }
        powerSaveReceiver = powerReceiver
        registerReceiver(powerReceiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))

        val battReceiver = telemetryFeature.createBatteryReceiver(
            currentStateProvider = { transportManager.bridgeState },
            onTelemetryReady = { telemetry ->
                val payloadBytes = json.encodeToString(telemetry).toByteArray(Charsets.UTF_8)
                transportManager.sendFrame(
                    WireMessageType.PING_TELEMETRY,
                    payloadBytes,
                    TransferPriority.LOW_LATENCY_CONTROL,
                    localIdentity.deviceId
                )
            }
        )
        batteryReceiver = battReceiver
        registerReceiver(battReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        BridgeNotificationListener.onNotificationEvent = { event ->
            val bytes = json.encodeToString(event).toByteArray(Charsets.UTF_8)
            transportManager.sendFrame(
                WireMessageType.NOTIFICATION_EVENT,
                bytes,
                TransferPriority.LOW_LATENCY_CONTROL,
                localIdentity.deviceId
            )
        }

        onManualClipboardSyncRequested = {
            val clipText = sysClipboard?.primaryClip?.getItemAt(0)?.text?.toString()
            val sensitive = clipboardSyncManager.isSensitiveClip(sysClipboard?.primaryClipDescription?.extras)
            if (!clipText.isNullOrEmpty()) {
                clipboardSyncManager.processLocalCopy(clipText, sensitive)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SEND_CLIPBOARD_TO_MAC -> onManualClipboardSyncRequested?.invoke()
            ACTION_START_PAIRING_LISTENER -> startOnDemandWifiServer()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        powerSaveReceiver?.let { runCatching { unregisterReceiver(it) } }
        batteryReceiver?.let { runCatching { unregisterReceiver(it) } }
        BridgeNotificationListener.onNotificationEvent = null
        onManualClipboardSyncRequested = null
        stopWifiTransport()
        blePeripheralManager?.shutdown()
        if (::transportManager.isInitialized) {
            transportManager.shutdown()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startOnDemandWifiServer(): Int {
        wifiTlsServer?.let { if (it.localPort > 0) return it.localPort }
        val peerId = trustStore.allPeers().firstOrNull()?.deviceId ?: return 49152
        val server = WifiTlsServer(
            identityManager = identityManager,
            trustStore = trustStore,
            expectedPeerDeviceId = peerId,
            onFrameReceived = { frame -> routeInboundFrame(frame) }
        )
        wifiTlsServer = server
        return server.start(0)
    }

    private fun stopWifiTransport() {
        wifiTlsClient?.close()
        wifiTlsClient = nilRef()
        wifiTlsServer?.stop()
        wifiTlsServer = nilRef()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> nilRef(): T? = null

    private fun routeInboundFrame(frame: WireFrame) {
        when (frame.messageType) {
            WireMessageType.WAKE_WIFI -> {
                runCatching { json.decodeFromString<WakeWifiPayload>(frame.payload.decodeToString()) }
                    .onSuccess { startOnDemandWifiServer() }
            }

            WireMessageType.CLIPBOARD_SYNC -> {
                runCatching { json.decodeFromString<ClipboardSyncPayload>(frame.payload.decodeToString()) }
                    .onSuccess { clipboardSyncManager.applyRemoteClipboard(it) }
            }

            WireMessageType.FILE_OFFER -> {
                runCatching { json.decodeFromString<FileOfferPayload>(frame.payload.decodeToString()) }
                    .onSuccess { offer ->
                        val response = fileTransferManager.beginIncomingTransfer(offer)
                        val respBytes = json.encodeToString(response).toByteArray(Charsets.UTF_8)
                        transportManager.sendFrame(
                            WireMessageType.FILE_RESPONSE,
                            respBytes,
                            TransferPriority.LOW_LATENCY_CONTROL
                        )
                    }
            }

            WireMessageType.FILE_CHUNK -> {
                runCatching { FileChunkPacket.decode(frame.payload) }
                    .onSuccess { packet ->
                        val progress = fileTransferManager.receiveChunk(packet)
                        if (progress.bytesTransferred >= progress.totalBytes) {
                            val verifiedFile = fileTransferManager.finalizeIncomingTransfer(packet.transferId)
                            fileTransferManager.exportToMediaStoreDownloads(
                                this,
                                verifiedFile,
                                "application/octet-stream"
                            )
                        }
                    }
            }

            WireMessageType.DEVICE_COMMAND -> {
                runCatching { json.decodeFromString<DeviceCommandPayload>(frame.payload.decodeToString()) }
                    .onSuccess { command ->
                        telemetryFeature.handleDeviceCommand(this, command)
                        BridgeNotificationListener.activeInstance?.handleDeviceCommand(command)
                    }
            }

            else -> Unit
        }
    }

    private fun hasRequiredTransportPermissions(): Boolean {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_ADVERTISE
            perms += Manifest.permission.BLUETOOTH_CONNECT
            perms += Manifest.permission.BLUETOOTH_SCAN
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        return perms.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Link-Me Continuity Bridge",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(state: BridgeState): Notification {
        val syncIntent = Intent(this, BridgeForegroundService::class.java).apply {
            action = ACTION_SEND_CLIPBOARD_TO_MAC
        }
        val syncPendingIntent = PendingIntent.getService(
            this,
            1,
            syncIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Link-Me Bridge Active")
            .setContentText("State: ${state.name}")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .addAction(0, "Send Clipboard to Mac", syncPendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "linkme_bridge_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_SEND_CLIPBOARD_TO_MAC = "com.linkme.androidbridge.ACTION_SEND_CLIPBOARD_TO_MAC"
        const val ACTION_START_PAIRING_LISTENER = "com.linkme.androidbridge.ACTION_START_PAIRING_LISTENER"

        @Volatile
        var onManualClipboardSyncRequested: (() -> Unit)? = null
    }
}
