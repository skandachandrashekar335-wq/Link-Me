package com.linkme.androidbridge.service

import com.linkme.androidbridge.core.protocol.FileOfferPayload
import com.linkme.androidbridge.core.protocol.MediaControlAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class ActivityCategory {
    FILES,
    CLIPBOARD,
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

    @Volatile
    var onSendMediaAction: ((MediaControlAction) -> Unit)? = null

    @Volatile
    var onSendOpenUrl: ((String) -> Unit)? = null

    @Volatile
    var onResolvePendingFileOffer: ((FileOfferPayload, Boolean) -> Unit)? = null

    fun record(item: BridgeActivityItem) {
        val updated = (_activities.value + item)
            .sortedByDescending { it.timestampMs }
            .take(60)
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
