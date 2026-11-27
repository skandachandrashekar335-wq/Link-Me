package com.linkme.androidbridge.core.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class WireMessageType(val code: Byte) {
    PAIR_HELLO(0x01),
    PING_TELEMETRY(0x02),
    WAKE_WIFI(0x03),
    CLIPBOARD_SYNC(0x04),
    FILE_OFFER(0x05),
    FILE_RESPONSE(0x06),
    FILE_CHUNK(0x07),
    NOTIFICATION_EVENT(0x08),
    DEVICE_COMMAND(0x09),
    MEDIA_CONTROL(0x0A),
    OPEN_URL(0x0B),
    SCREEN_FRAME(0x0C),
    SCREEN_CONTROL(0x0D),
    REMOTE_INPUT(0x0E),
    PAIR_COMMIT(0x0F),
    PAIR_ACK(0x10);

    companion object {
        fun fromByte(value: Byte): WireMessageType? = entries.firstOrNull { it.code == value }
    }
}

sealed class ProtocolError(message: String) : Exception(message) {
    class TruncatedHeader : ProtocolError("TRUNCATED_HEADER")
    class InvalidMagic : ProtocolError("INVALID_MAGIC")
    class UnsupportedVersion(val version: Int) : ProtocolError("UNSUPPORTED_VERSION")
    class UnknownMessageType(val msgType: Int) : ProtocolError("UNKNOWN_MSG_TYPE")
    class FrameTooLarge(val length: Long) : ProtocolError("FRAME_TOO_LARGE")
    class TruncatedPayload(val expected: Int, val actual: Int) : ProtocolError("TRUNCATED_PAYLOAD")
    class ReplayDetected(val received: Long, val highestAccepted: Long) :
        ProtocolError("REPLAY_OR_INVALID_SEQUENCE")
}

data class WireFrame(
    val messageType: WireMessageType,
    val sequenceId: Long,
    val payload: ByteArray
) {
    init {
        if (payload.size > MAX_CONTROL_FRAME_BYTES) {
            throw ProtocolError.FrameTooLarge(payload.size.toLong())
        }
    }

    fun encode(): ByteArray = FrameEncoder.encode(this)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WireFrame) return false
        return messageType == other.messageType &&
            sequenceId == other.sequenceId &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = messageType.hashCode()
        result = 31 * result + sequenceId.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {
        const val MAGIC_HI: Byte = 0x41
        const val MAGIC_LO: Byte = 0x4D
        const val VERSION: Byte = 0x01
        const val HEADER_SIZE = 16
        const val MAX_CONTROL_FRAME_BYTES = 1_048_576 // 1 MB
        const val MAX_CHUNK_DATA_BYTES = 262_144 // 256 KB
        const val DEFAULT_CHUNK_DATA_BYTES = 65_536 // 64 KB

        fun decode(bytes: ByteArray): WireFrame {
            val decoder = FrameDecoder()
            decoder.append(bytes)
            return decoder.nextFrame() ?: run {
                if (bytes.size < HEADER_SIZE) throw ProtocolError.TruncatedHeader()
                val declaredLen = ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.BIG_ENDIAN).int
                throw ProtocolError.TruncatedPayload(declaredLen, maxOf(0, bytes.size - HEADER_SIZE))
            }
        }
    }
}

object FrameEncoder {
    fun encode(frame: WireFrame): ByteArray {
        val buffer = ByteBuffer.allocate(WireFrame.HEADER_SIZE + frame.payload.size)
            .order(ByteOrder.BIG_ENDIAN)
        buffer.put(WireFrame.MAGIC_HI)
        buffer.put(WireFrame.MAGIC_LO)
        buffer.put(WireFrame.VERSION)
        buffer.put(frame.messageType.code)
        buffer.putInt(frame.payload.size)
        buffer.putLong(frame.sequenceId)
        buffer.put(frame.payload)
        return buffer.array()
    }
}

/**
 * Stream reassembly buffer handling partial TCP reads and coalesced frames with strict bounds checks.
 */
class FrameDecoder(
    private val maxAllowedSize: Int = WireFrame.MAX_CONTROL_FRAME_BYTES
) {
    private val streamBuffer = ByteArrayOutputStream()
    private var bytesCache = ByteArray(0)

    val bufferedByteCount: Int
        get() = bytesCache.size

    fun append(chunk: ByteArray) {
        if (chunk.isEmpty()) return
        streamBuffer.write(chunk)
        bytesCache = streamBuffer.toByteArray()
    }

    fun reset() {
        streamBuffer.reset()
        bytesCache = ByteArray(0)
    }

    fun nextFrame(): WireFrame? {
        if (bytesCache.size < WireFrame.HEADER_SIZE) {
            return null
        }

        if (bytesCache[0] != WireFrame.MAGIC_HI || bytesCache[1] != WireFrame.MAGIC_LO) {
            reset()
            throw ProtocolError.InvalidMagic()
        }

        val ver = bytesCache[2]
        if (ver != WireFrame.VERSION) {
            reset()
            throw ProtocolError.UnsupportedVersion(ver.toInt() and 0xFF)
        }

        val msgType = WireMessageType.fromByte(bytesCache[3]) ?: run {
            val code = bytesCache[3].toInt() and 0xFF
            reset()
            throw ProtocolError.UnknownMessageType(code)
        }

        val headerBuf = ByteBuffer.wrap(bytesCache, 4, 12).order(ByteOrder.BIG_ENDIAN)
        val payloadLengthLong = headerBuf.int.toLong() and 0xFFFFFFFFL
        val sequenceId = headerBuf.long

        if (payloadLengthLong > maxAllowedSize.toLong()) {
            reset()
            throw ProtocolError.FrameTooLarge(payloadLengthLong)
        }

        val payloadLength = payloadLengthLong.toInt()
        val totalNeeded = WireFrame.HEADER_SIZE + payloadLength
        if (bytesCache.size < totalNeeded) {
            return null
        }

        val payload = bytesCache.copyOfRange(WireFrame.HEADER_SIZE, totalNeeded)
        val remaining = bytesCache.copyOfRange(totalNeeded, bytesCache.size)
        streamBuffer.reset()
        if (remaining.isNotEmpty()) {
            streamBuffer.write(remaining)
        }
        bytesCache = remaining

        return WireFrame(messageType = msgType, sequenceId = sequenceId, payload = payload)
    }

    fun drainFrames(): List<WireFrame> {
        val frames = mutableListOf<WireFrame>()
        while (true) {
            val next = nextFrame() ?: break
            frames.add(next)
        }
        return frames
    }
}
