package com.linkme.androidbridge.service

import com.linkme.androidbridge.core.protocol.DeviceCommandPayload
import com.linkme.androidbridge.core.protocol.FileOfferPayload
import com.linkme.androidbridge.core.protocol.MediaControlAction
import com.linkme.androidbridge.core.protocol.PairAckPayload
import com.linkme.androidbridge.core.protocol.PairCommitPayload
import com.linkme.androidbridge.core.protocol.PairHelloPayload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

data class PendingPairingCandidate(
    val peerId: String,
    val peerName: String,
    val pinnedCertSha256: String,
    val sharedBleSecret: ByteArray,
    val bluetoothSessionKey: ByteArray,
    val pairToken: String,
    val btAddress: String? = null
)

sealed class PairingCommitStatus {
    data object Idle : PairingCommitStatus()
    data class Connecting(val macName: String) : PairingCommitStatus()
    data class Connected(
        val peerId: String,
        val peerName: String,
        val pinnedCertSha256: String,
        val ack: PairAckPayload
    ) : PairingCommitStatus()
    data class Failed(val reason: String) : PairingCommitStatus()
}

data class PairingTraceState(
    val step: Int = 1,
    val statusText: String = "Point camera at MacBridge QR",
    val errorMessage: String? = null
)

enum class ActivityCategory {
    FILES,
    CLIPBOARD,
    MEDIA,
    SYSTEM,
    TRANSPORT
}

data class BridgeActivityItem(
    val id: String = UUID.randomUUID().toString(),
    val category: ActivityCategory,
    val title: String,
    val detail: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val clipboardText: String? = null,
    val fileUriString: String? = null
)

data class FileTransferUiState(
    val filename: String,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val queueRemaining: Int,
    val transportLabel: String
) {
    val fraction: Float
        get() = if (totalBytes > 0L) (bytesTransferred.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
    val percent: Int
        get() = (fraction * 100f).toInt().coerceIn(0, 100)
}

data class ScreenMirrorUiState(
    val isViewingRemote: Boolean = false,
    val isSharingLocal: Boolean = false,
    val remoteWidth: Int = 0,
    val remoteHeight: Int = 0,
    val remoteFps: Int = 0,
    val latestJpegBytes: ByteArray? = null,
    val statusText: String = "Idle"
)

data class RadioDiagnosticsUiState(
    val bleRadioState: String = "Unknown",
    val bleLinkState: String = "Idle",
    val wifiMtlsState: String = "Standby",
    val lastWireEvents: List<String> = emptyList(),
    val pingStatusText: String? = null
) {
    fun formatCopyableReport(peerName: String, latencyMs: Int?): String {
        val events = if (lastWireEvents.isEmpty()) "(none)" else lastWireEvents.take(10).joinToString("\n")
        return buildString {
            appendLine("Link-Me Connection Diagnostics (Android)")
            appendLine("Peer: $peerName • Latency: ${latencyMs?.let { "${it}ms" } ?: "--"}")
            appendLine("BLE Radio State: $bleRadioState")
            appendLine("BLE Link: $bleLinkState")
            appendLine("Wi-Fi mTLS: $wifiMtlsState")
            appendLine("Last 10 Wire Events:")
            append(events)
        }
    }
}

/**
 * Shared reactive bridge runtime hub connecting BridgeForegroundService, ScreenMirrorService, and Compose UI.
 */
object BridgeActivityHub {
    private val _activities = MutableStateFlow<List<BridgeActivityItem>>(emptyList())
    val activities: StateFlow<List<BridgeActivityItem>> = _activities.asStateFlow()

    private val _pendingFileOffer = MutableStateFlow<FileOfferPayload?>(null)
    val pendingFileOffer: StateFlow<FileOfferPayload?> = _pendingFileOffer.asStateFlow()

    private val _activeFileTransfer = MutableStateFlow<FileTransferUiState?>(null)
    val activeFileTransfer: StateFlow<FileTransferUiState?> = _activeFileTransfer.asStateFlow()

    private val _bluetoothConnected = MutableStateFlow(false)
    val bluetoothConnected: StateFlow<Boolean> = _bluetoothConnected.asStateFlow()

    private val _wifiConnected = MutableStateFlow(false)
    val wifiConnected: StateFlow<Boolean> = _wifiConnected.asStateFlow()

    private val _latencyMs = MutableStateFlow<Int?>(null)
    val latencyMs: StateFlow<Int?> = _latencyMs.asStateFlow()

    private val _localBatteryPercent = MutableStateFlow<Int?>(null)
    val localBatteryPercent: StateFlow<Int?> = _localBatteryPercent.asStateFlow()

    private val _localIsCharging = MutableStateFlow(false)
    val localIsCharging: StateFlow<Boolean> = _localIsCharging.asStateFlow()

    private val _peerDeviceName = MutableStateFlow<String?>(null)
    val peerDeviceName: StateFlow<String?> = _peerDeviceName.asStateFlow()

    private val _peerBatteryPercent = MutableStateFlow<Int?>(null)
    val peerBatteryPercent: StateFlow<Int?> = _peerBatteryPercent.asStateFlow()

    private val _peerIsCharging = MutableStateFlow(false)
    val peerIsCharging: StateFlow<Boolean> = _peerIsCharging.asStateFlow()

    private val _mirrorState = MutableStateFlow(ScreenMirrorUiState())
    val mirrorState: StateFlow<ScreenMirrorUiState> = _mirrorState.asStateFlow()

    private val _stealthBlackActive = MutableStateFlow(false)
    val stealthBlackActive: StateFlow<Boolean> = _stealthBlackActive.asStateFlow()

    private val _pairingStatus = MutableStateFlow<PairingCommitStatus>(PairingCommitStatus.Idle)
    val pairingStatus: StateFlow<PairingCommitStatus> = _pairingStatus.asStateFlow()

    private val _pairingTrace = MutableStateFlow(PairingTraceState())
    val pairingTrace: StateFlow<PairingTraceState> = _pairingTrace.asStateFlow()

    private val _radioDiagnostics = MutableStateFlow(RadioDiagnosticsUiState())
    val radioDiagnostics: StateFlow<RadioDiagnosticsUiState> = _radioDiagnostics.asStateFlow()

    private val wireLogLock = Any()

    @Volatile
    var onSendPing01: (() -> Unit)? = null

    @Volatile
    var pendingPairingCandidate: PendingPairingCandidate? = null

    @Volatile
    var pendingAutoCommit: PairCommitPayload? = null

    private val pendingFileUris = ConcurrentLinkedQueue<String>()

    @Volatile
    var onStartPairingExchange: ((candidate: PendingPairingCandidate, commit: PairCommitPayload, lanHost: String?, tcpPort: Int) -> Unit)? = null

    @Volatile
    var onSendMediaAction: ((MediaControlAction) -> Unit)? = null

    @Volatile
    var onSendOpenUrl: ((String) -> Unit)? = null

    @Volatile
    var onSendFileUri: ((String) -> Unit)? = null

    @Volatile
    var onResolvePendingFileOffer: ((FileOfferPayload, Boolean) -> Unit)? = null

    @Volatile
    var onSendPairHello: ((hello: PairHelloPayload, lanHost: String?, tcpPort: Int) -> Unit)? = null

    @Volatile
    var onSendPairCommit: ((commit: PairCommitPayload, lanHost: String?, tcpPort: Int) -> Unit)? = null

    @Volatile
    var onSendDeviceCommand: ((DeviceCommandPayload) -> Unit)? = null

    @Volatile
    var onPeerTrustedUpdated: (() -> Unit)? = null

    @Volatile
    var onUnpairAllRequested: (() -> Unit)? = null

    @Volatile
    var onRequestRemoteScreenMirror: ((start: Boolean, fps: Int, quality: Int) -> Unit)? = null

    @Volatile
    var onSendScreenFrameBytes: ((width: Int, height: Int, timestampMs: Long, jpegBytes: ByteArray) -> Unit)? = null

    fun enqueueOutboundFileUri(uriString: String) {
        val handler = onSendFileUri
        if (handler != null) {
            handler.invoke(uriString)
        } else {
            pendingFileUris.offer(uriString)
        }
    }

    fun drainPendingFileUris(): List<String> {
        val list = mutableListOf<String>()
        while (true) {
            val next = pendingFileUris.poll() ?: break
            list.add(next)
        }
        return list
    }

    fun updateFileTransfer(state: FileTransferUiState?) {
        _activeFileTransfer.value = state
    }

    fun updateConnectionState(bleConnected: Boolean? = null, wifiActive: Boolean? = null, latency: Int? = null) {
        if (bleConnected != null) _bluetoothConnected.value = bleConnected
        if (wifiActive != null) _wifiConnected.value = wifiActive
        if (latency != null) _latencyMs.value = latency
    }

    fun updateLocalBattery(percent: Int?, charging: Boolean) {
        _localBatteryPercent.value = percent?.coerceIn(0, 100)
        _localIsCharging.value = charging
    }

    fun updatePeerTelemetry(percent: Int? = null, charging: Boolean? = null, name: String? = null) {
        if (percent != null) _peerBatteryPercent.value = percent.coerceIn(0, 100)
        if (charging != null) _peerIsCharging.value = charging
        if (!name.isNullOrBlank()) _peerDeviceName.value = name
    }

    fun updateMirrorState(transform: (ScreenMirrorUiState) -> ScreenMirrorUiState) {
        _mirrorState.value = transform(_mirrorState.value)
    }

    fun record(item: BridgeActivityItem) {
        val updated = (_activities.value + item)
            .sortedByDescending { it.timestampMs }
            .take(80)
        _activities.value = updated
    }

    fun setPendingOffer(offer: FileOfferPayload?) {
        _pendingFileOffer.value = offer
    }

    fun resolvePendingOffer(accepted: Boolean) {
        val current = _pendingFileOffer.value ?: return
        _pendingFileOffer.value = null
        onResolvePendingFileOffer?.invoke(current, accepted)
    }

    fun setStealthBlack(active: Boolean) {
        _stealthBlackActive.value = active
    }

    fun updatePairingTrace(step: Int? = null, statusText: String? = null, errorMessage: String? = null) {
        val cur = _pairingTrace.value
        _pairingTrace.value = PairingTraceState(
            step = step ?: cur.step,
            statusText = statusText ?: cur.statusText,
            errorMessage = errorMessage
        )
    }

    fun beginPairingCommit(candidate: PendingPairingCandidate) {
        pendingPairingCandidate = candidate
        _pairingStatus.value = PairingCommitStatus.Connecting(candidate.peerName)
        updatePairingTrace(step = 1, statusText = "QR Decoded — Starting BLE & LAN...", errorMessage = null)
    }

    fun completePairingSuccess(candidate: PendingPairingCandidate, ack: PairAckPayload) {
        pendingPairingCandidate = null
        pendingAutoCommit = null
        _pairingStatus.value = PairingCommitStatus.Connected(
            peerId = candidate.peerId,
            peerName = ack.deviceName.ifBlank { candidate.peerName },
            pinnedCertSha256 = candidate.pinnedCertSha256,
            ack = ack
        )
        updatePairingTrace(
            step = 4,
            statusText = "Paired & Synced with ${ack.deviceName.ifBlank { candidate.peerName }}",
            errorMessage = null
        )
        updatePeerTelemetry(
            percent = ack.batteryLevel,
            charging = ack.isCharging,
            name = ack.deviceName.ifBlank { candidate.peerName }
        )
    }

    fun failPairingIfWaiting(reason: String) {
        if (_pairingStatus.value is PairingCommitStatus.Connecting) {
            pendingPairingCandidate = null
            pendingAutoCommit = null
            _pairingStatus.value = PairingCommitStatus.Failed(reason)
            updatePairingTrace(errorMessage = reason)
        }
    }

    fun resetPairingStatus() {
        pendingPairingCandidate = null
        pendingAutoCommit = null
        _pairingStatus.value = PairingCommitStatus.Idle
        _pairingTrace.value = PairingTraceState()
    }

    fun notifyUnpaired() {
        resetPairingStatus()
        _peerDeviceName.value = null
        _peerBatteryPercent.value = null
        _peerIsCharging.value = false
        _bluetoothConnected.value = false
        _wifiConnected.value = false
        _latencyMs.value = null
        _pendingFileOffer.value = null
        appendWireEvent("UNPAIR_ALL_CLEARED")
        onUnpairAllRequested?.invoke()
    }

    fun updateRadioDiagnostics(
        bleRadioState: String? = null,
        bleLinkState: String? = null,
        wifiMtlsState: String? = null,
        pingStatusText: String? = null,
        clearPingStatus: Boolean = false
    ) {
        synchronized(wireLogLock) {
            val cur = _radioDiagnostics.value
            _radioDiagnostics.value = cur.copy(
                bleRadioState = bleRadioState ?: cur.bleRadioState,
                bleLinkState = bleLinkState ?: cur.bleLinkState,
                wifiMtlsState = wifiMtlsState ?: cur.wifiMtlsState,
                pingStatusText = when {
                    clearPingStatus -> null
                    pingStatusText != null -> pingStatusText
                    else -> cur.pingStatusText
                }
            )
        }
    }

    fun appendWireEvent(line: String) {
        val entry = if (line.startsWith("[")) {
            line
        } else {
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            "[$ts] $line"
        }
        synchronized(wireLogLock) {
            val cur = _radioDiagnostics.value
            if (cur.lastWireEvents.firstOrNull() == entry) return
            _radioDiagnostics.value = cur.copy(
                lastWireEvents = (listOf(entry) + cur.lastWireEvents).take(40)
            )
        }
    }
}
