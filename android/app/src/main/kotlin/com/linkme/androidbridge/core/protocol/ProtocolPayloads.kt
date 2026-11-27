package com.linkme.androidbridge.core.protocol

import kotlinx.serialization.Serializable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

@Serializable
enum class BridgeState {
    DORMANT_BLE,
    HANDSHAKE_WAKE,
    ACTIVE_WIFI_STREAM,
    COOLDOWN_IDLE
}

@Serializable
data class PairHelloPayload(
    val msgType: String = "PAIR_HELLO",
    val deviceId: String,
    val deviceName: String,
    val platform: String,
    val ephemeralPublicKey: String,
    val spkiSha256: String,
    val protocolVersion: Int = 1,
    val capabilities: List<String> = listOf(
        "clipboard",
        "file_transfer",
        "notifications",
        "inline_reply",
        "telemetry",
        "ring"
    )
)

@Serializable
data class PingTelemetryPayload(
    val msgType: String = "PING_TELEMETRY",
    val deviceId: String,
    val batteryLevel: Int,
    val isCharging: Boolean,
    val batterySaverActive: Boolean,
    val transportState: BridgeState,
    val timestampMs: Long
)

@Serializable
data class WakeWifiPayload(
    val msgType: String = "WAKE_WIFI",
    val deviceId: String,
    val servicePort: Int,
    val hostHints: List<String>,
    val bleTokenHex: String,
    val reason: String,
    val timestampMs: Long
)

@Serializable
data class ClipboardSyncPayload(
    val msgType: String = "CLIPBOARD_SYNC",
    val originDeviceId: String,
    val clipboardEpoch: Long,
    val contentSha256: String,
    val mimeType: String,
    val text: String? = null,
    val imageBase64: String? = null,
    val isSensitive: Boolean = false,
    val timestampMs: Long
) {
    fun validate() {
        require(!isSensitive) { "SENSITIVE_CLIPBOARD_REJECTED" }
    }
}

@Serializable
data class FileOfferPayload(
    val msgType: String = "FILE_OFFER",
    val transferId: String,
    val filename: String,
    val byteSize: Long,
    val sha256: String,
    val chunkSize: Int = WireFrame.DEFAULT_CHUNK_DATA_BYTES,
    val totalChunks: Int,
    val mimeType: String
) {
    fun validateSafeFilename(): String = FilenameSanitizer.validateStrict(filename)
}

@Serializable
data class FileResponsePayload(
    val msgType: String = "FILE_RESPONSE",
    val transferId: String,
    val accepted: Boolean,
    val resumeChunkIndex: Int = 0,
    val reason: String? = null
)

data class FileChunkPacket(
    val transferId: UUID,
    val chunkIndex: Int,
    val chunkSha256: ByteArray,
    val data: ByteArray
) {
    init {
        require(chunkSha256.size == 32) { "chunkSha256 must be 32 bytes" }
        require(data.size <= WireFrame.MAX_CHUNK_DATA_BYTES) { "chunk data exceeds 256 KB" }
    }

    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(SUBHEADER_SIZE + data.size).order(ByteOrder.BIG_ENDIAN)
        buf.putLong(transferId.mostSignificantBits)
        buf.putLong(transferId.leastSignificantBits)
        buf.putInt(chunkIndex)
        buf.putInt(data.size)
        buf.put(chunkSha256)
        buf.put(data)
        return buf.array()
    }

    companion object {
        const val SUBHEADER_SIZE = 56

        fun decode(payload: ByteArray): FileChunkPacket {
            require(payload.size >= SUBHEADER_SIZE) { "Invalid FILE_CHUNK subheader" }
            val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
            val msb = buf.long
            val lsb = buf.long
            val chunkIndex = buf.int
            val dataLength = buf.int
            require(dataLength in 0..WireFrame.MAX_CHUNK_DATA_BYTES) { "Invalid chunk length" }
            require(payload.size == SUBHEADER_SIZE + dataLength) { "Mismatched FILE_CHUNK length" }
            val sha = ByteArray(32).also { buf.get(it) }
            val data = ByteArray(dataLength).also { buf.get(it) }
            return FileChunkPacket(
                transferId = UUID(msb, lsb),
                chunkIndex = chunkIndex,
                chunkSha256 = sha,
                data = data
            )
        }
    }
}

@Serializable
data class NotificationEventPayload(
    val msgType: String = "NOTIFICATION_EVENT",
    val action: String,
    val sbnKey: String,
    val appName: String,
    val packageName: String,
    val title: String,
    val body: String,
    val timestampMs: Long,
    val canReply: Boolean
)

@Serializable
data class DeviceCommandPayload(
    val msgType: String = "DEVICE_COMMAND",
    val command: String,
    val sbnKey: String? = null,
    val replyText: String? = null,
    val durationSeconds: Int? = null,
    val timestampMs: Long
)
