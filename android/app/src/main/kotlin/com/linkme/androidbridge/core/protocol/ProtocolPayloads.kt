package com.linkme.androidbridge.core.protocol

import kotlinx.serialization.Serializable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

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
    val timestampMs: Long,
    val pingNonce: String? = null,
    val isPong: Boolean = false
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

@Serializable
enum class MediaControlAction(val wireName: String) {
    PLAY_PAUSE("play_pause"),
    NEXT("next"),
    PREV("prev"),
    VOL_UP("vol_up"),
    VOL_DOWN("vol_down"),
    MUTE("mute");

    companion object {
        fun fromWireName(raw: String): MediaControlAction? =
            entries.firstOrNull { it.wireName == raw }
    }
}

@Serializable
data class MediaControlPayload(
    val action: String
) {
    fun resolveAction(): MediaControlAction? = MediaControlAction.fromWireName(action)
}

@Serializable
data class OpenUrlPayload(
    val url: String
) {
    fun validateStrict(): String = UrlSanitizer.validateStrict(url)
}

object PairingCommitMac {
    fun computeHex(
        sharedBleSecret: ByteArray,
        deviceId: String,
        spkiSha256: String,
        ephemeralPublicKey: String
    ): String {
        val msg = "LinkMe-PAIR-COMMIT-v1|$deviceId|${spkiSha256.lowercase()}|$ephemeralPublicKey"
            .toByteArray(Charsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(sharedBleSecret, "HmacSHA256"))
        return mac.doFinal(msg).joinToString("") { "%02x".format(it) }
    }
}

@Serializable
data class PairCommitPayload(
    val msgType: String = "PAIR_COMMIT",
    val deviceId: String,
    val deviceName: String,
    val platform: String = "android",
    val ephemeralPublicKey: String,
    val spkiSha256: String,
    val sasMacHex: String,
    val batteryLevel: Int,
    val isCharging: Boolean,
    val timestampMs: Long = System.currentTimeMillis(),
    val pairToken: String? = null
)

@Serializable
data class PairAckPayload(
    val msgType: String = "PAIR_ACK",
    val deviceId: String,
    val deviceName: String,
    val platform: String = "macos",
    val spkiSha256: String,
    val accepted: Boolean = true,
    val batteryLevel: Int,
    val isCharging: Boolean,
    val timestampMs: Long = System.currentTimeMillis()
)

@Serializable
data class ScreenControlPayload(
    val action: String,
    val source: String,
    val fps: Int = 60,
    val quality: Int = 65,
    val wake: Boolean = true,
    val stealthBlack: Boolean = false
)

@Serializable
data class RemoteInputPayload(
    val type: String,
    val x: Double? = null,
    val y: Double? = null,
    val x1: Double? = null,
    val y1: Double? = null,
    val x2: Double? = null,
    val y2: Double? = null,
    val durationMs: Int? = null,
    val action: String? = null,
    val text: String? = null
)

data class ScreenFramePacket(
    val source: Int = SOURCE_ANDROID,
    val width: Int,
    val height: Int,
    val timestampMs: Long,
    val jpegData: ByteArray
) {
    init {
        require(source == SOURCE_ANDROID || source == SOURCE_MACOS) { "Invalid screen source" }
        require(width in 1..65535 && height in 1..65535) { "Invalid screen frame dimensions" }
        require(jpegData.isNotEmpty() && jpegData.size <= WireFrame.MAX_CONTROL_FRAME_BYTES - HEADER_SIZE) {
            "Screen frame JPEG exceeds max WireFrame bounds"
        }
    }

    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(HEADER_SIZE + jpegData.size).order(ByteOrder.BIG_ENDIAN)
        buf.put(source.toByte())
        buf.putShort(width.toShort())
        buf.putShort(height.toShort())
        buf.putLong(timestampMs)
        buf.put(jpegData)
        return buf.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScreenFramePacket) return false
        return source == other.source &&
            width == other.width &&
            height == other.height &&
            timestampMs == other.timestampMs &&
            jpegData.contentEquals(other.jpegData)
    }

    override fun hashCode(): Int {
        var result = source
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + jpegData.contentHashCode()
        return result
    }

    companion object {
        const val SOURCE_ANDROID = 1
        const val SOURCE_MACOS = 2
        const val HEADER_SIZE = 13

        fun decode(payload: ByteArray): ScreenFramePacket {
            require(payload.size > HEADER_SIZE) { "Invalid SCREEN_FRAME subheader" }
            val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
            val src = buf.get().toInt() and 0xFF
            val w = buf.short.toInt() and 0xFFFF
            val h = buf.short.toInt() and 0xFFFF
            val ts = buf.long
            val jpeg = ByteArray(payload.size - HEADER_SIZE).also { buf.get(it) }
            return ScreenFramePacket(source = src, width = w, height = h, timestampMs = ts, jpegData = jpeg)
        }
    }
}
