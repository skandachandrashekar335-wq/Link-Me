package com.linkme.androidbridge.core.transport

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * Reliable chunking + ACK codec for encrypted Bluetooth GATT transfers (MTU 23..512).
 * Packet types:
 * - 0xB1 Data Chunk: [1B 0xB1][4B packetId BE][2B chunkIdx BE][2B totalChunks BE][payload]
 * - 0xAC Ack Frame:  [1B 0xAC][4B packetId BE][2B chunkCount BE]
 */
object BluetoothPacketCodec {
    const val DATA_TAG: Byte = 0xB1.toByte()
    const val ACK_TAG: Byte = 0xAC.toByte()
    const val CHUNK_HEADER_SIZE = 9
    const val ACK_SIZE = 7

    data class AckPacket(
        val packetId: Long,
        val receivedChunks: Int
    ) {
        fun encode(): ByteArray = ByteBuffer.allocate(ACK_SIZE).order(ByteOrder.BIG_ENDIAN).apply {
            put(ACK_TAG)
            putInt(packetId.toInt())
            putShort(receivedChunks.toShort())
        }.array()

        companion object {
            fun decode(data: ByteArray): AckPacket? {
                if (data.size != ACK_SIZE || data[0] != ACK_TAG) return null
                val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
                buf.get() // tag
                val pid = buf.int.toLong() and 0xFFFFFFFFL
                val count = buf.short.toInt() and 0xFFFF
                return AckPacket(pid, count)
            }
        }
    }

    fun packetize(envelope: ByteArray, packetId: Long, mtu: Int = 23): List<ByteArray> {
        if (envelope.isEmpty()) return emptyList()
        val attCap = maxOf(12, mtu - 3)
        val sliceCap = maxOf(1, attCap - CHUNK_HEADER_SIZE)
        val totalChunks = (envelope.size + sliceCap - 1) / sliceCap
        require(totalChunks <= 65_535) { "Envelope exceeds Bluetooth packet limit" }

        val packets = ArrayList<ByteArray>(totalChunks)
        var offset = 0
        var chunkIdx = 0
        while (offset < envelope.size) {
            val end = minOf(envelope.size, offset + sliceCap)
            val sliceLen = end - offset
            val buf = ByteBuffer.allocate(CHUNK_HEADER_SIZE + sliceLen).order(ByteOrder.BIG_ENDIAN)
            buf.put(DATA_TAG)
            buf.putInt(packetId.toInt())
            buf.putShort(chunkIdx.toShort())
            buf.putShort(totalChunks.toShort())
            buf.put(envelope, offset, sliceLen)
            packets.add(buf.array())
            offset = end
            chunkIdx++
        }
        return packets
    }
}

class BluetoothPacketReassembler {
    private data class Assembly(
        val totalChunks: Int,
        val slices: MutableMap<Int, ByteArray> = LinkedHashMap()
    )

    private val inFlight = ConcurrentHashMap<Long, Assembly>()

    data class ReassemblyResult(
        val packetId: Long,
        val completeEnvelope: ByteArray?,
        val ack: BluetoothPacketCodec.AckPacket?
    )

    @Synchronized
    fun ingest(packet: ByteArray): ReassemblyResult {
        if (packet.size <= BluetoothPacketCodec.CHUNK_HEADER_SIZE || packet[0] != BluetoothPacketCodec.DATA_TAG) {
            return ReassemblyResult(0L, null, null)
        }
        val buf = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        buf.get() // tag
        val packetId = buf.int.toLong() and 0xFFFFFFFFL
        val chunkIdx = buf.short.toInt() and 0xFFFF
        val totalChunks = buf.short.toInt() and 0xFFFF
        if (totalChunks <= 0 || chunkIdx >= totalChunks) {
            return ReassemblyResult(packetId, null, null)
        }

        val slice = ByteArray(packet.size - BluetoothPacketCodec.CHUNK_HEADER_SIZE)
        buf.get(slice)

        val current = inFlight.getOrPut(packetId) { Assembly(totalChunks) }
        if (current.totalChunks != totalChunks) {
            inFlight.remove(packetId)
            return ReassemblyResult(packetId, null, null)
        }
        current.slices[chunkIdx] = slice
        if (current.slices.size == totalChunks) {
            val totalSize = (0 until totalChunks).sumOf { current.slices[it]?.size ?: return ReassemblyResult(packetId, null, null) }
            val out = ByteArray(totalSize)
            var cursor = 0
            for (i in 0 until totalChunks) {
                val part = current.slices[i] ?: return ReassemblyResult(packetId, null, null)
                System.arraycopy(part, 0, out, cursor, part.size)
                cursor += part.size
            }
            inFlight.remove(packetId)
            return ReassemblyResult(
                packetId = packetId,
                completeEnvelope = out,
                ack = BluetoothPacketCodec.AckPacket(packetId, totalChunks)
            )
        }
        if (inFlight.size > 16) {
            inFlight.clear()
        }
        return ReassemblyResult(packetId, null, null)
    }

    @Synchronized
    fun reset() {
        inFlight.clear()
    }
}
