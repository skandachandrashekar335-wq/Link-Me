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
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

data class PendingPairingCandidate(
    val peerId: String,
    val peerName: String,
    val pinnedCertSha256: String,
    val sharedBleSecret: ByteArray,
    val bluetoothSessionKey: ByteArray,
    val pairToken: String
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

    private val _pairingStatus = MutableStateFlow<PairingCommitStatus>(PairingCommitStatus.Idle)
    val pairingStatus: StateFlow<PairingCommitStatus> = _pairingStatus.asStateFlow()

    @Volatile
    var pendingPairingCandidate: PendingPairingCandidate? = null

    private val pendingFileUris = ConcurrentLinkedQueue<String>()

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

    fun beginPairingCommit(candidate: PendingPairingCandidate) {
        pendingPairingCandidate = candidate
        _pairingStatus.value = PairingCommitStatus.Connecting(candidate.peerName)
    }

    fun completePairingSuccess(candidate: PendingPairingCandidate, ack: PairAckPayload) {
        pendingPairingCandidate = null
        _pairingStatus.value = PairingCommitStatus.Connected(
            peerId = candidate.peerId,
            peerName = ack.deviceName.ifBlank { candidate.peerName },
            pinnedCertSha256 = candidate.pinnedCertSha256,
            ack = ack
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
            _pairingStatus.value = PairingCommitStatus.Failed(reason)
        }
    }

    fun resetPairingStatus() {
        pendingPairingCandidate = null
        _pairingStatus.value = PairingCommitStatus.Idle
    }
}
