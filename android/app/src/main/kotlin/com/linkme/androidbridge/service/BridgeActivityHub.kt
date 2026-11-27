package com.linkme.androidbridge.service

import com.linkme.androidbridge.core.protocol.DeviceCommandPayload
import com.linkme.androidbridge.core.protocol.FileOfferPayload
import com.linkme.androidbridge.core.protocol.MediaControlAction
import com.linkme.androidbridge.core.protocol.PairHelloPayload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

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

/**
 * Shared reactive bridge runtime hub connecting BridgeForegroundService and Compose UI.
 */
object BridgeActivityHub {
    private val _activities = MutableStateFlow<List<BridgeActivityItem>>(emptyList())
    val activities: StateFlow<List<BridgeActivityItem>> = _activities.asStateFlow()

    private val _pendingFileOffer = MutableStateFlow<FileOfferPayload?>(null)
    val pendingFileOffer: StateFlow<FileOfferPayload?> = _pendingFileOffer.asStateFlow()

    private val _bluetoothConnected = MutableStateFlow(false)
    val bluetoothConnected: StateFlow<Boolean> = _bluetoothConnected.asStateFlow()

    private val _wifiConnected = MutableStateFlow(false)
    val wifiConnected: StateFlow<Boolean> = _wifiConnected.asStateFlow()

    private val _latencyMs = MutableStateFlow<Int?>(null)
    val latencyMs: StateFlow<Int?> = _latencyMs.asStateFlow()

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
    var onSendDeviceCommand: ((DeviceCommandPayload) -> Unit)? = null

    @Volatile
    var onPeerTrustedUpdated: (() -> Unit)? = null

    fun updateConnectionState(bleConnected: Boolean? = null, wifiActive: Boolean? = null, latency: Int? = null) {
        if (bleConnected != null) _bluetoothConnected.value = bleConnected
        if (wifiActive != null) _wifiConnected.value = wifiActive
        if (latency != null) _latencyMs.value = latency
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
}
