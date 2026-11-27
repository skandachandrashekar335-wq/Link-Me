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
import com.linkme.androidbridge.R
import com.linkme.androidbridge.core.crypto.BlePrivacyBeacon
import com.linkme.androidbridge.core.crypto.IdentityManager
import com.linkme.androidbridge.core.crypto.TrustStore
import com.linkme.androidbridge.core.prefs.BridgePreferencesStore
import com.linkme.androidbridge.core.protocol.BridgeState
import com.linkme.androidbridge.core.protocol.ClipboardSyncPayload
import com.linkme.androidbridge.core.protocol.DeviceCommandPayload
import com.linkme.androidbridge.core.protocol.FileChunkPacket
import com.linkme.androidbridge.core.protocol.FileOfferPayload
import com.linkme.androidbridge.core.protocol.FileResponsePayload
import com.linkme.androidbridge.core.protocol.FrameEncoder
import com.linkme.androidbridge.core.protocol.MediaControlPayload
import com.linkme.androidbridge.core.protocol.OpenUrlPayload
import com.linkme.androidbridge.core.protocol.PairAckPayload
import com.linkme.androidbridge.core.protocol.PairCommitPayload
import com.linkme.androidbridge.core.protocol.PingTelemetryPayload
import com.linkme.androidbridge.core.protocol.RemoteInputPayload
import com.linkme.androidbridge.core.protocol.ScreenControlPayload
import com.linkme.androidbridge.core.protocol.ScreenFramePacket
import com.linkme.androidbridge.core.protocol.WakeWifiPayload
import com.linkme.androidbridge.core.protocol.WireFrame
import com.linkme.androidbridge.core.protocol.WireMessageType
import com.linkme.androidbridge.core.transport.BleGattClient
import com.linkme.androidbridge.core.transport.BlePeripheralManager
import com.linkme.androidbridge.core.transport.NsdDiscoveryManager
import com.linkme.androidbridge.core.transport.TransferPriority
import com.linkme.androidbridge.core.transport.TransportManager
import com.linkme.androidbridge.core.transport.WifiTlsClient
import com.linkme.androidbridge.core.transport.WifiTlsServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ANR-safe connectedDevice ForegroundService.
 * Calls ServiceCompat.startForeground() immediately (< 5ms) with a lightweight cached notification
 * and dispatches ALL Keystore, TrustStore, BLE, and socket initialization onto Dispatchers.IO.
 */
class BridgeForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val initialized = AtomicBoolean(false)

    private val cachedNotification: Notification by lazy {
        buildNotification(BridgeState.DORMANT_BLE)
    }

    @Volatile private var identityManager: IdentityManager? = null
    @Volatile private var trustStore: TrustStore? = null
    @Volatile private var transportManager: TransportManager? = null
    @Volatile private var clipboardSyncManager: ClipboardSyncManager? = null
    @Volatile private var fileTransferManager: FileTransferManager? = null
    @Volatile private var telemetryFeature: TelemetryFeature? = null
    @Volatile private var continuityFeature: ContinuityFeature? = null
    @Volatile private var preferencesStore: BridgePreferencesStore? = null
    @Volatile private var blePeripheralManager: BlePeripheralManager? = null
    @Volatile private var bleGattClient: BleGattClient? = null
    @Volatile private var nsdDiscoveryManager: NsdDiscoveryManager? = null
    @Volatile private var wifiTlsServer: WifiTlsServer? = null
    @Volatile private var wifiTlsClient: WifiTlsClient? = null

    private var powerSaveReceiver: BroadcastReceiver? = null
    private var batteryReceiver: BroadcastReceiver? = null
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var telemetryScheduler: ScheduledExecutorService? = null
    private val fileSendQueue = ConcurrentLinkedQueue<String>()
    private val fileSendBusy = AtomicBoolean(false)
    @Volatile private var mirrorFpsWindowStartMs: Long = 0L
    @Volatile private var mirrorFrameCounter: Int = 0

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        if (!enterForegroundSafely()) {
            stopSelf()
            return
        }

        // Offload ALL Keystore / crypto / BLE / receiver setup off the Main Thread
        serviceScope.launch {
            initializeAsync()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!enterForegroundSafely()) {
            stopSelf()
            return START_NOT_STICKY
        }

        val action = intent?.action
        if (action == ACTION_SEND_CLIPBOARD_TO_MAC || action == ACTION_START_PAIRING_LISTENER) {
            serviceScope.launch {
                runCatching {
                    when (action) {
                        ACTION_SEND_CLIPBOARD_TO_MAC -> onManualClipboardSyncRequested?.invoke()
                        ACTION_START_PAIRING_LISTENER -> startOnDemandWifiServer()
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { powerSaveReceiver?.let { unregisterReceiver(it) } }
        runCatching { batteryReceiver?.let { unregisterReceiver(it) } }
        runCatching {
            val sysClipboard = getSystemService(ClipboardManager::class.java)
            clipboardListener?.let { sysClipboard?.removePrimaryClipChangedListener(it) }
        }
        runCatching { telemetryScheduler?.shutdownNow() }
        powerSaveReceiver = null
        batteryReceiver = null
        clipboardListener = null
        telemetryScheduler = null
        BridgeNotificationListener.onNotificationEvent = null
        NotificationCompanionBridge.onNotificationEvent = null
        BridgeActivityHub.onSendMediaAction = null
        BridgeActivityHub.onSendOpenUrl = null
        BridgeActivityHub.onSendFileUri = null
        BridgeActivityHub.onResolvePendingFileOffer = null
        BridgeActivityHub.onSendPairHello = null
        BridgeActivityHub.onSendPairCommit = null
        BridgeActivityHub.onSendDeviceCommand = null
        BridgeActivityHub.onPeerTrustedUpdated = null
        BridgeActivityHub.onRequestRemoteScreenMirror = null
        BridgeActivityHub.onSendScreenFrameBytes = null
        onManualClipboardSyncRequested = null

        runCatching { stopWifiTransport() }
        runCatching { nsdDiscoveryManager?.unregister() }
        runCatching { bleGattClient?.shutdown() }
        runCatching { blePeripheralManager?.shutdown() }
        runCatching { transportManager?.shutdown() }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun enterForegroundSafely(): Boolean {
        val hasPerms = hasRequiredTransportPermissions()
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasPerms) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        return runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, cachedNotification, fgsType)
            hasPerms
        }.getOrDefault(false)
    }

    private fun initializeAsync() {
        if (!initialized.compareAndSet(false, true)) return

        runCatching {
            val idMgr = IdentityManager(useAndroidKeyStore = true)
            val localIdentity = idMgr.loadOrCreateIdentity()
            val store = TrustStore.forContext(this)
            val sysClipboard = getSystemService(ClipboardManager::class.java)
            val clipAdapter = sysClipboard?.let { AndroidClipboardAdapter(it) }
            val transferMgr = FileTransferManager(File(cacheDir, "incoming_transfers"))
            val telemetry = TelemetryFeature(localIdentity.deviceId)

            val transport = TransportManager(
                bleSender = { frame ->
                    val clientSent = bleGattClient?.takeIf { it.isConnected }?.sendFrame(frame) == true
                    if (!clientSent) {
                        blePeripheralManager?.sendControlFrame(frame)
                    }
                },
                wifiOpener = { startOnDemandWifiServer() },
                wifiSender = { frame ->
                    val client = wifiTlsClient ?: error("Wi-Fi peer client not connected")
                    client.send(frame)
                },
                wifiCloser = { stopWifiTransport() }
            )

            val clipSync = ClipboardSyncManager(
                deviceId = localIdentity.deviceId,
                adapter = clipAdapter,
                transport = transport
            )
            val continuity = ContinuityFeature(transport)
            val prefsStore = BridgePreferencesStore.forContext(this)

            identityManager = idMgr
            trustStore = store
            fileTransferManager = transferMgr
            telemetryFeature = telemetry
            continuityFeature = continuity
            preferencesStore = prefsStore
            transportManager = transport
            clipboardSyncManager = clipSync

            val initialSnap = telemetry.readCurrentBattery(this)
            BridgeActivityHub.updateLocalBattery(initialSnap.level, initialSnap.isCharging)

            serviceScope.launch {
                prefsStore.state.collect { prefs ->
                    transport.setCooldownDurationSeconds(prefs.wifiCooldownSeconds)
                    transport.setBleOnlyBatterySaver(prefs.bleOnlyBatterySaver)
                    clipSync.direction = prefs.clipboardDirection
                }
            }

            BridgeActivityHub.onSendMediaAction = { action ->
                serviceScope.launch {
                    runCatching {
                        continuity.sendMediaControl(action)
                        BridgeActivityHub.record(
                            BridgeActivityItem(
                                category = ActivityCategory.TRANSPORT,
                                title = "Media Command Sent",
                                detail = action.wireName.uppercase()
                            )
                        )
                    }
                }
            }

            BridgeActivityHub.onSendOpenUrl = { rawUrl ->
                serviceScope.launch {
                    runCatching {
                        val safe = continuity.sendOpenUrl(rawUrl)
                        BridgeActivityHub.record(
                            BridgeActivityItem(
                                category = ActivityCategory.TRANSPORT,
                                title = "URL Beamed to Mac",
                                detail = safe
                            )
                        )
                    }
                }
            }

            BridgeActivityHub.onResolvePendingFileOffer = { offer, accepted ->
                serviceScope.launch {
                    runCatching {
                        val resp = if (accepted) {
                            transferMgr.beginIncomingTransfer(offer)
                        } else {
                            FileResponsePayload(
                                transferId = offer.transferId,
                                accepted = false,
                                resumeChunkIndex = 0,
                                reason = "DECLINED_BY_USER"
                            )
                        }
                        val respBytes = json.encodeToString(resp).toByteArray(Charsets.UTF_8)
                        transport.sendFrame(
                            WireMessageType.FILE_RESPONSE,
                            respBytes,
                            TransferPriority.LOW_LATENCY_CONTROL
                        )
                        BridgeActivityHub.record(
                            BridgeActivityItem(
                                category = ActivityCategory.FILES,
                                title = if (accepted) "Accepted Incoming File" else "Declined Incoming File",
                                detail = offer.filename
                            )
                        )
                    }
                }
            }

            val pairedPeer = store.allPeers().firstOrNull()
            val beacon = pairedPeer?.let { BlePrivacyBeacon(it.sharedBleSecret) }
            val peripheral = BlePeripheralManager(
                context = this,
                beacon = beacon,
                trustStore = store,
                onInboundFrame = { frame ->
                    serviceScope.launch {
                        runCatching { routeInboundFrame(frame) }
                    }
                },
                onConnectionChanged = { connected ->
                    BridgeActivityHub.updateConnectionState(
                        bleConnected = connected || (bleGattClient?.isConnected == true)
                    )
                    if (connected && store.allPeers().isNotEmpty()) {
                        serviceScope.launch { emitImmediateTelemetry() }
                    }
                }
            )
            blePeripheralManager = peripheral

            val gattClient = BleGattClient(
                context = this,
                trustStore = store,
                onInboundFrame = { frame ->
                    serviceScope.launch {
                        runCatching { routeInboundFrame(frame) }
                    }
                },
                onConnectionChanged = { connected ->
                    BridgeActivityHub.updateConnectionState(
                        bleConnected = connected || peripheral.isClientConnected
                    )
                    if (connected && store.allPeers().isNotEmpty()) {
                        serviceScope.launch { emitImmediateTelemetry() }
                    }
                },
                onLatencyMeasured = { rttMs ->
                    BridgeActivityHub.updateConnectionState(latency = rttMs)
                }
            )
            bleGattClient = gattClient
            if (pairedPeer != null) {
                BridgeActivityHub.updatePeerTelemetry(name = pairedPeer.deviceName)
                gattClient.startScanning(autoReconnect = true)
            }

            nsdDiscoveryManager = NsdDiscoveryManager(this)

            BridgeActivityHub.onSendPairHello = { hello, lanHost, tcpPort ->
                serviceScope.launch {
                    val payloadBytes = json.encodeToString(hello).toByteArray(Charsets.UTF_8)
                    val pairFrame = WireFrame(WireMessageType.PAIR_HELLO, 1L, payloadBytes)
                    gattClient.sendPairingHelloOverBle(pairFrame)
                    if (!lanHost.isNullOrBlank() && tcpPort > 0) {
                        runCatching {
                            Socket().use { sock ->
                                sock.connect(InetSocketAddress(lanHost, tcpPort), 2500)
                                sock.getOutputStream().write(FrameEncoder.encode(pairFrame))
                                sock.getOutputStream().flush()
                            }
                        }
                    }
                    BridgeActivityHub.record(
                        BridgeActivityItem(
                            category = ActivityCategory.SYSTEM,
                            title = "Pairing Handshake Sent",
                            detail = "X25519 PAIR_HELLO over Bluetooth${if (lanHost != null) " + LAN" else ""}"
                        )
                    )
                }
            }

            BridgeActivityHub.onSendPairCommit = { commit, lanHost, tcpPort ->
                serviceScope.launch {
                    val payloadBytes = json.encodeToString(commit).toByteArray(Charsets.UTF_8)
                    val commitFrame = WireFrame(WireMessageType.PAIR_COMMIT, 2L, payloadBytes)
                    gattClient.sendPairingFrameOverBle(commitFrame)
                    if (!lanHost.isNullOrBlank() && tcpPort > 0) {
                        runCatching {
                            Socket().use { sock ->
                                sock.soTimeout = 3500
                                sock.connect(InetSocketAddress(lanHost, tcpPort), 2500)
                                val out = sock.getOutputStream()
                                out.write(FrameEncoder.encode(commitFrame))
                                out.flush()
                                val buf = ByteArray(16_384)
                                val read = runCatching { sock.getInputStream().read(buf) }.getOrDefault(-1)
                                if (read > 0) {
                                    val decoder = com.linkme.androidbridge.core.protocol.FrameDecoder()
                                    decoder.append(buf.copyOf(read))
                                    for (replyFrame in decoder.drainFrames()) {
                                        routeInboundFrame(replyFrame)
                                    }
                                }
                            }
                        }
                    }
                    runCatching { emitImmediateTelemetry() }
                    BridgeActivityHub.record(
                        BridgeActivityItem(
                            category = ActivityCategory.SYSTEM,
                            title = "PAIR_COMMIT Transmitted",
                            detail = "${commit.deviceName} (${commit.batteryLevel}%)"
                        )
                    )
                }
            }

            BridgeActivityHub.onSendDeviceCommand = { command ->
                serviceScope.launch {
                    runCatching {
                        val bytes = json.encodeToString(command).toByteArray(Charsets.UTF_8)
                        val frame = transport.makeFrame(WireMessageType.DEVICE_COMMAND, bytes)
                        transport.sendResilientFrame(frame, preferBle = true)
                    }
                }
            }

            BridgeActivityHub.onPeerTrustedUpdated = {
                serviceScope.launch {
                    gattClient.startScanning(autoReconnect = true)
                    emitImmediateTelemetry()
                }
            }

            BridgeActivityHub.onRequestRemoteScreenMirror = { start, fps, quality ->
                serviceScope.launch {
                    runCatching {
                        val ctrl = ScreenControlPayload(
                            action = if (start) "start" else "stop",
                            source = "macos",
                            fps = fps,
                            quality = quality
                        )
                        val frame = transport.makeFrame(
                            WireMessageType.SCREEN_CONTROL,
                            json.encodeToString(ctrl).toByteArray(Charsets.UTF_8)
                        )
                        transport.sendResilientFrame(frame, preferBle = true)
                    }
                }
            }

            BridgeActivityHub.onSendScreenFrameBytes = { width, height, timestampMs, jpegBytes ->
                serviceScope.launch {
                    runCatching {
                        val packet = ScreenFramePacket(
                            source = ScreenFramePacket.SOURCE_ANDROID,
                            width = width,
                            height = height,
                            timestampMs = timestampMs,
                            jpegData = jpegBytes
                        )
                        val frame = transport.makeFrame(WireMessageType.SCREEN_FRAME, packet.encode())
                        transport.sendResilientFrame(frame, preferBle = false)
                    }
                }
            }

            BridgeActivityHub.onSendFileUri = { uriStr ->
                fileSendQueue.offer(uriStr)
                drainOutboundFileQueue()
            }
            BridgeActivityHub.drainPendingFileUris().forEach { uriStr ->
                fileSendQueue.offer(uriStr)
            }
            if (!fileSendQueue.isEmpty()) {
                drainOutboundFileQueue()
            }

            val powerMgr = getSystemService(PowerManager::class.java)
            peripheral.setBatterySaverActive(powerMgr?.isPowerSaveMode == true)

            val powerReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) {
                        serviceScope.launch {
                            runCatching {
                                val isSaver = context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
                                blePeripheralManager?.setBatterySaverActive(isSaver)
                            }
                        }
                    }
                }
            }
            powerSaveReceiver = powerReceiver
            registerReceiver(powerReceiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))

            val battReceiver = telemetry.createBatteryReceiver(
                currentStateProvider = { transport.bridgeState },
                onTelemetryReady = { payload ->
                    BridgeActivityHub.updateLocalBattery(payload.batteryLevel, payload.isCharging)
                    serviceScope.launch {
                        runCatching {
                            val bytes = json.encodeToString(payload).toByteArray(Charsets.UTF_8)
                            val frame = transport.makeFrame(WireMessageType.PING_TELEMETRY, bytes)
                            transport.sendResilientFrame(frame, preferBle = true)
                        }
                    }
                }
            )
            batteryReceiver = battReceiver
            registerReceiver(battReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

            telemetryScheduler = Executors.newSingleThreadScheduledExecutor().also { sched ->
                sched.scheduleAtFixedRate(
                    {
                        if (store.allPeers().isNotEmpty()) {
                            serviceScope.launch { runCatching { emitImmediateTelemetry() } }
                        }
                    },
                    60L,
                    60L,
                    TimeUnit.SECONDS
                )
            }

            val clipChanged = ClipboardManager.OnPrimaryClipChangedListener {
                serviceScope.launch {
                    runCatching {
                        val clip = sysClipboard?.primaryClip
                        val clipText = clip?.getItemAt(0)?.text?.toString()
                        val sensitive = clipSync.isSensitiveClip(sysClipboard?.primaryClipDescription?.extras)
                        if (!clipText.isNullOrEmpty()) {
                            val sent = clipSync.processLocalCopy(clipText, sensitive)
                            if (sent != null) {
                                BridgeActivityHub.record(
                                    BridgeActivityItem(
                                        category = ActivityCategory.CLIPBOARD,
                                        title = "Clipboard Synced to Mac",
                                        detail = clipText.take(120),
                                        clipboardText = clipText
                                    )
                                )
                            }
                        }
                    }
                }
            }
            clipboardListener = clipChanged
            sysClipboard?.addPrimaryClipChangedListener(clipChanged)

            val forwardNotificationEvent: (com.linkme.androidbridge.core.protocol.NotificationEventPayload) -> Unit = { event ->
                serviceScope.launch {
                    runCatching {
                        val bytes = json.encodeToString(event).toByteArray(Charsets.UTF_8)
                        transport.sendFrame(
                            WireMessageType.NOTIFICATION_EVENT,
                            bytes,
                            TransferPriority.LOW_LATENCY_CONTROL,
                            localIdentity.deviceId
                        )
                    }
                }
            }
            BridgeNotificationListener.onNotificationEvent = forwardNotificationEvent
            NotificationCompanionBridge.onNotificationEvent = forwardNotificationEvent

            onManualClipboardSyncRequested = {
                serviceScope.launch {
                    runCatching {
                        val clipText = sysClipboard?.primaryClip?.getItemAt(0)?.text?.toString()
                        val sensitive = clipSync.isSensitiveClip(sysClipboard?.primaryClipDescription?.extras)
                        if (!clipText.isNullOrEmpty()) {
                            clipSync.processLocalCopy(clipText, sensitive)
                        }
                    }
                }
            }
        }
    }

    private fun startOnDemandWifiServer(): Int {
        wifiTlsServer?.let { if (it.localPort > 0) return it.localPort }
        val idMgr = identityManager ?: return 49152
        val store = trustStore ?: return 49152
        val peerId = store.allPeers().firstOrNull()?.deviceId ?: return 49152

        val server = WifiTlsServer(
            identityManager = idMgr,
            trustStore = store,
            expectedPeerDeviceId = peerId,
            onFrameReceived = { frame ->
                serviceScope.launch { runCatching { routeInboundFrame(frame) } }
            }
        )
        wifiTlsServer = server
        val port = server.start(0, InetAddress.getByName("0.0.0.0"))
        runCatching {
            val localId = idMgr.loadOrCreateIdentity().deviceId
            nsdDiscoveryManager?.registerService(localId, port)
        }
        BridgeActivityHub.updateConnectionState(wifiActive = true)
        return port
    }

    private fun stopWifiTransport() {
        runCatching { wifiTlsClient?.close() }
        wifiTlsClient = null
        runCatching { wifiTlsServer?.stop() }
        wifiTlsServer = null
        runCatching { nsdDiscoveryManager?.unregister() }
        BridgeActivityHub.updateConnectionState(wifiActive = false)
    }

    private fun routeInboundFrame(frame: WireFrame) {
        val clipSync = clipboardSyncManager
        val transferMgr = fileTransferManager
        val transport = transportManager
        val telemetry = telemetryFeature

        when (frame.messageType) {
            WireMessageType.WAKE_WIFI -> {
                runCatching { json.decodeFromString<WakeWifiPayload>(frame.payload.decodeToString()) }
                    .onSuccess { wake ->
                        val localPort = startOnDemandWifiServer()
                        val hostHint = wake.hostHints.firstOrNull { !it.endsWith(".local.") }
                        val idMgr = identityManager
                        val store = trustStore
                        val peerId = store?.allPeers()?.firstOrNull()?.deviceId
                        if (hostHint != null && wake.servicePort in 1024..65535 && idMgr != null && peerId != null) {
                            runCatching {
                                val client = WifiTlsClient(idMgr, store, peerId)
                                client.connect(hostHint, wake.servicePort)
                                wifiTlsClient = client
                                BridgeActivityHub.updateConnectionState(wifiActive = true)
                            }.onFailure {
                                wifiTlsClient = null
                                BridgeActivityHub.updateConnectionState(wifiActive = false)
                            }
                        } else if (localPort > 0) {
                            BridgeActivityHub.updateConnectionState(wifiActive = true)
                        }
                    }
            }

            WireMessageType.CLIPBOARD_SYNC -> {
                runCatching { json.decodeFromString<ClipboardSyncPayload>(frame.payload.decodeToString()) }
                    .onSuccess { payload ->
                        clipSync?.applyRemoteClipboard(payload)
                        payload.text?.let { snippet ->
                            BridgeActivityHub.record(
                                BridgeActivityItem(
                                    category = ActivityCategory.CLIPBOARD,
                                    title = "Clipboard From Mac",
                                    detail = snippet.take(120),
                                    clipboardText = snippet
                                )
                            )
                        }
                    }
            }

            WireMessageType.FILE_OFFER -> {
                if (transferMgr != null && transport != null) {
                    runCatching { json.decodeFromString<FileOfferPayload>(frame.payload.decodeToString()) }
                        .onSuccess { offer ->
                            val autoAccept = preferencesStore?.state?.value?.autoAcceptFiles ?: true
                            if (!autoAccept) {
                                BridgeActivityHub.setPendingOffer(offer)
                                BridgeActivityHub.record(
                                    BridgeActivityItem(
                                        category = ActivityCategory.FILES,
                                        title = "File Offer Pending Approval",
                                        detail = "${offer.filename} (${offer.byteSize / 1024} KB)"
                                    )
                                )
                            } else {
                                val response = transferMgr.beginIncomingTransfer(offer)
                                val respBytes = json.encodeToString(response).toByteArray(Charsets.UTF_8)
                                transport.sendFrame(
                                    WireMessageType.FILE_RESPONSE,
                                    respBytes,
                                    TransferPriority.LOW_LATENCY_CONTROL
                                )
                            }
                        }
                }
            }

            WireMessageType.FILE_CHUNK -> {
                if (transferMgr != null) {
                    runCatching { FileChunkPacket.decode(frame.payload) }
                        .onSuccess { packet ->
                            val progress = transferMgr.receiveChunk(packet)
                            if (progress.bytesTransferred >= progress.totalBytes) {
                                val verifiedFile = transferMgr.finalizeIncomingTransfer(packet.transferId)
                                val exportedUri = transferMgr.exportToMediaStoreDownloads(
                                    this,
                                    verifiedFile,
                                    "application/octet-stream"
                                )
                                postFileReceivedNotification(verifiedFile.name, exportedUri)
                                BridgeActivityHub.record(
                                    BridgeActivityItem(
                                        category = ActivityCategory.FILES,
                                        title = "Received File",
                                        detail = verifiedFile.name,
                                        fileUriString = exportedUri?.toString()
                                    )
                                )
                            }
                        }
                }
            }

            WireMessageType.PING_TELEMETRY -> {
                runCatching { json.decodeFromString<PingTelemetryPayload>(frame.payload.decodeToString()) }
                    .onSuccess { ping ->
                        BridgeActivityHub.updatePeerTelemetry(
                            percent = ping.batteryLevel,
                            charging = ping.isCharging
                        )
                    }
            }

            WireMessageType.PAIR_ACK -> {
                runCatching { json.decodeFromString<PairAckPayload>(frame.payload.decodeToString()) }
                    .onSuccess { ack ->
                        BridgeActivityHub.updatePeerTelemetry(
                            percent = ack.batteryLevel,
                            charging = ack.isCharging,
                            name = ack.deviceName
                        )
                        BridgeActivityHub.record(
                            BridgeActivityItem(
                                category = ActivityCategory.SYSTEM,
                                title = "PAIR_ACK Confirmed",
                                detail = "${ack.deviceName} • ${ack.batteryLevel}%"
                            )
                        )
                    }
            }

            WireMessageType.SCREEN_CONTROL -> {
                runCatching { json.decodeFromString<ScreenControlPayload>(frame.payload.decodeToString()) }
                    .onSuccess { ctrl ->
                        if (ctrl.source == "android" && ctrl.action == "stop") {
                            val stopIntent = Intent(this, ScreenMirrorService::class.java).apply {
                                action = ScreenMirrorService.ACTION_STOP
                            }
                            runCatching { startService(stopIntent) }
                        } else if (ctrl.source == "android" && ctrl.action == "start") {
                            BridgeActivityHub.updateMirrorState {
                                it.copy(statusText = "Mac requested Android screen — open Mirror tab & tap Share Phone Screen")
                            }
                        }
                    }
            }

            WireMessageType.SCREEN_FRAME -> {
                runCatching { ScreenFramePacket.decode(frame.payload) }
                    .onSuccess { pkt ->
                        val now = System.currentTimeMillis()
                        if (mirrorFpsWindowStartMs == 0L) mirrorFpsWindowStartMs = now
                        mirrorFrameCounter += 1
                        val elapsed = now - mirrorFpsWindowStartMs
                        var computedFps: Int? = null
                        if (elapsed >= 1000L) {
                            computedFps = maxOf(1, (mirrorFrameCounter * 1000L / elapsed).toInt())
                            mirrorFrameCounter = 0
                            mirrorFpsWindowStartMs = now
                        }
                        BridgeActivityHub.updateMirrorState { prev ->
                            prev.copy(
                                isViewingRemote = true,
                                remoteWidth = pkt.width,
                                remoteHeight = pkt.height,
                                remoteFps = computedFps ?: prev.remoteFps,
                                latestJpegBytes = pkt.jpegData,
                                statusText = "Live • ${pkt.width}×${pkt.height}"
                            )
                        }
                    }
            }

            WireMessageType.REMOTE_INPUT -> {
                runCatching { json.decodeFromString<RemoteInputPayload>(frame.payload.decodeToString()) }
                    .onSuccess { input ->
                        val executed = RemoteControlAccessibilityService.dispatchRemoteInput(input)
                        if (!executed) {
                            BridgeActivityHub.updateMirrorState {
                                it.copy(statusText = "Remote input ignored: Enable Cursor Control (Accessibility) in Mirror tab")
                            }
                        }
                    }
            }

            WireMessageType.DEVICE_COMMAND -> {
                runCatching { json.decodeFromString<DeviceCommandPayload>(frame.payload.decodeToString()) }
                    .onSuccess { command ->
                        telemetry?.handleDeviceCommand(this, command)
                        NotificationCompanionBridge.dispatchDeviceCommand(this, command)
                    }
            }

            WireMessageType.MEDIA_CONTROL -> {
                runCatching { json.decodeFromString<MediaControlPayload>(frame.payload.decodeToString()) }
                    .onSuccess { payload ->
                        continuityFeature?.handleInboundMediaControl(this, payload)
                        BridgeActivityHub.record(
                            BridgeActivityItem(
                                category = ActivityCategory.TRANSPORT,
                                title = "Remote Media Control",
                                detail = payload.action.uppercase()
                            )
                        )
                    }
            }

            WireMessageType.OPEN_URL -> {
                runCatching { json.decodeFromString<OpenUrlPayload>(frame.payload.decodeToString()) }
                    .onSuccess { payload ->
                        val safe = continuityFeature?.handleInboundOpenUrl(this, payload)
                        if (safe != null) {
                            BridgeActivityHub.record(
                                BridgeActivityItem(
                                    category = ActivityCategory.TRANSPORT,
                                    title = "Opened URL From Mac",
                                    detail = safe
                                )
                            )
                        }
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
            .setContentTitle("Link-Me Bridge")
            .setContentText("Standby: ${state.name}")
            .setSmallIcon(R.drawable.ic_stat_linkme)
            .addAction(0, "Send Clipboard to Mac", syncPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun emitImmediateTelemetry() {
        val telemetry = telemetryFeature ?: return
        val transport = transportManager ?: return
        val payload = telemetry.buildSnapshotPayload(this, transport.bridgeState) ?: return
        BridgeActivityHub.updateLocalBattery(payload.batteryLevel, payload.isCharging)
        val bytes = json.encodeToString(payload).toByteArray(Charsets.UTF_8)
        val frame = transport.makeFrame(WireMessageType.PING_TELEMETRY, bytes)
        runCatching { transport.sendResilientFrame(frame, preferBle = true) }
    }

    private fun drainOutboundFileQueue() {
        if (!fileSendBusy.compareAndSet(false, true)) return
        serviceScope.launch {
            try {
                val transferMgr = fileTransferManager ?: return@launch
                val transport = transportManager ?: return@launch
                while (true) {
                    val uriStr = fileSendQueue.poll() ?: break
                    runCatching {
                        val uri = android.net.Uri.parse(uriStr)
                        var displayName = "shared_file.bin"
                        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                            if (c.moveToFirst()) {
                                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                if (idx >= 0) displayName = c.getString(idx) ?: displayName
                            }
                        }
                        val safeName = com.linkme.androidbridge.core.protocol.FilenameSanitizer.sanitize(displayName)
                        val stagingDir = File(cacheDir, "outbound_staging").apply { mkdirs() }
                        val staged = File(stagingDir, safeName)
                        contentResolver.openInputStream(uri)?.use { input ->
                            staged.outputStream().use { out ->
                                val buf = ByteArray(FileTransferManager.CHUNK_SIZE)
                                while (true) {
                                    val n = input.read(buf)
                                    if (n <= 0) break
                                    out.write(buf, 0, n)
                                }
                            }
                        }
                        try {
                            val transportLabel = if (BridgeActivityHub.wifiConnected.value) "Wi-Fi mTLS" else "BLE E2EE"
                            transferMgr.streamOutboundFile(
                                sourceFile = staged,
                                onProgress = { p ->
                                    BridgeActivityHub.updateFileTransfer(
                                        FileTransferUiState(
                                            filename = p.filename,
                                            bytesTransferred = p.bytesTransferred,
                                            totalBytes = p.totalBytes,
                                            queueRemaining = fileSendQueue.size,
                                            transportLabel = transportLabel
                                        )
                                    )
                                },
                                emitFrame = { frame -> transport.sendResilientFrame(frame, preferBle = false) }
                            )
                            BridgeActivityHub.record(
                                BridgeActivityItem(
                                    category = ActivityCategory.FILES,
                                    title = "Sent File to Mac",
                                    detail = "$safeName ($transportLabel)",
                                    fileUriString = uriStr
                                )
                            )
                        } finally {
                            staged.delete()
                            BridgeActivityHub.updateFileTransfer(null)
                        }
                    }.onFailure { err ->
                        BridgeActivityHub.updateFileTransfer(null)
                        BridgeActivityHub.record(
                            BridgeActivityItem(
                                category = ActivityCategory.FILES,
                                title = "File Send Failed",
                                detail = err.message ?: "Transport unreachable"
                            )
                        )
                    }
                }
            } finally {
                fileSendBusy.set(false)
            }
        }
    }

    private fun postFileReceivedNotification(filename: String, exportedUri: android.net.Uri?) {
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Saved to Downloads/Link-Me")
            .setContentText(filename)
            .setSmallIcon(R.drawable.ic_stat_linkme)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        if (exportedUri != null) {
            val viewIntent = Intent(Intent.ACTION_VIEW, exportedUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val pending = PendingIntent.getActivity(
                this,
                filename.hashCode(),
                viewIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.setContentIntent(pending)
            builder.addAction(0, "Open File", pending)
        }
        mgr.notify(filename.hashCode() and 0x7FFFFFFF, builder.build())
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
