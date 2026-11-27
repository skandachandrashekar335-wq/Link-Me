package com.linkme.androidbridge.service

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import android.os.PersistableBundle
import com.linkme.androidbridge.core.protocol.ClipboardSyncPayload
import com.linkme.androidbridge.core.protocol.WireFrame
import com.linkme.androidbridge.core.protocol.WireMessageType
import com.linkme.androidbridge.core.transport.FrameTransportSink
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

interface ClipboardSystemAdapter {
    fun writeText(text: String)
}

class AndroidClipboardAdapter(
    private val clipboardManager: ClipboardManager
) : ClipboardSystemAdapter {
    override fun writeText(text: String) {
        clipboardManager.setPrimaryClip(ClipData.newPlainText("Link-Me", text))
    }
}

/**
 * Feature 2: Loop-safe clipboard sync.
 * Updates lastSyncedContentHash BEFORE calling ClipboardManager.setPrimaryClip to prevent echo loops,
 * and drops EXTRA_IS_SENSITIVE password-manager entries.
 */
class ClipboardSyncManager(
    private val deviceId: String,
    private val adapter: ClipboardSystemAdapter? = null,
    private val transport: FrameTransportSink? = null
) {
    private var clipboardEpoch: Long = 0L

    @Volatile
    var lastSyncedContentHash: String? = null
        private set

    fun isSensitiveClip(extras: PersistableBundle?): Boolean {
        if (extras == null) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false)) {
                return true
            }
        }
        return extras.getBoolean("android.content.extra.IS_SENSITIVE", false)
    }

    fun processLocalCopy(
        text: String,
        isSensitive: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): ClipboardSyncPayload? {
        if (isSensitive || text.isEmpty()) return null
        val hash = sha256Hex(text.toByteArray(Charsets.UTF_8))
        if (hash == lastSyncedContentHash) {
            return null
        }

        clipboardEpoch += 1L
        lastSyncedContentHash = hash
        val payload = ClipboardSyncPayload(
            originDeviceId = deviceId,
            clipboardEpoch = clipboardEpoch,
            contentSha256 = hash,
            mimeType = "text/plain",
            text = text,
            isSensitive = false,
            timestampMs = nowMs
        )
        transport?.let { sink ->
            val jsonBytes = Json.encodeToString(payload).toByteArray(Charsets.UTF_8)
            val preferBle = text.toByteArray(Charsets.UTF_8).size <= BLE_MAX_BYTES
            sink.sendFrame(
                WireFrame(WireMessageType.CLIPBOARD_SYNC, clipboardEpoch, jsonBytes),
                preferBleIfSmall = preferBle
            )
        }
        return payload
    }

    fun applyRemoteClipboard(payload: ClipboardSyncPayload) {
        payload.validate()
        // CRITICAL: Update lastSyncedContentHash BEFORE mutating ClipboardManager.
        lastSyncedContentHash = payload.contentSha256
        if (payload.mimeType == "text/plain" && !payload.text.isNullOrEmpty()) {
            adapter?.writeText(payload.text)
        }
    }

    companion object {
        const val BLE_MAX_BYTES = 400

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
