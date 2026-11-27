import Foundation

/// Reliable chunking + ACK codec for encrypted Bluetooth GATT transfers (MTU 23..512).
/// Packet types:
/// - 0xB1 Data Chunk: [1B 0xB1][4B packetId BE][2B chunkIdx BE][2B totalChunks BE][payload]
/// - 0xAC Ack Frame:  [1B 0xAC][4B packetId BE][2B chunkCount BE]
public enum BluetoothPacketCodec {
    public static let dataTag: UInt8 = 0xB1
    public static let ackTag: UInt8 = 0xAC
    public static let chunkHeaderSize = 9
    public static let ackSize = 7

    public struct AckPacket: Equatable, Sendable {
        public let packetId: UInt32
        public let receivedChunks: UInt16

        public func encode() -> Data {
            var out = Data(capacity: ackSize)
            out.append(ackTag)
            var pid = packetId.bigEndian
            withUnsafeBytes(of: &pid) { out.append(contentsOf: $0) }
            var cnt = receivedChunks.bigEndian
            withUnsafeBytes(of: &cnt) { out.append(contentsOf: $0) }
            return out
        }

        public static func decode(_ data: Data) -> AckPacket? {
            guard data.count == ackSize else { return nil }
            let s = data.startIndex
            guard data[s] == ackTag else { return nil }
            let pid = (UInt32(data[s + 1]) << 24)
                | (UInt32(data[s + 2]) << 16)
                | (UInt32(data[s + 3]) << 8)
                | UInt32(data[s + 4])
            let cnt = (UInt16(data[s + 5]) << 8) | UInt16(data[s + 6])
            return AckPacket(packetId: pid, receivedChunks: cnt)
        }
    }

    public static func packetize(envelope: Data, packetId: UInt32, mtu: Int = 23) -> [Data] {
        guard !envelope.isEmpty else { return [] }
        let attPayloadCap = max(12, mtu - 3)
        let sliceCap = max(1, attPayloadCap - chunkHeaderSize)
        let totalChunksInt = (envelope.count + sliceCap - 1) / sliceCap
        precondition(totalChunksInt <= Int(UInt16.max), "Envelope exceeds Bluetooth packet limit")
        let totalChunks = UInt16(totalChunksInt)

        var packets: [Data] = []
        packets.reserveCapacity(totalChunksInt)
        var offset = envelope.startIndex
        var chunkIdx: UInt16 = 0

        while offset < envelope.endIndex {
            let end = envelope.index(offset, offsetBy: sliceCap, limitedBy: envelope.endIndex) ?? envelope.endIndex
            var pkt = Data(capacity: chunkHeaderSize + envelope.distance(from: offset, to: end))
            pkt.append(dataTag)
            var pidBE = packetId.bigEndian
            withUnsafeBytes(of: &pidBE) { pkt.append(contentsOf: $0) }
            var idxBE = chunkIdx.bigEndian
            withUnsafeBytes(of: &idxBE) { pkt.append(contentsOf: $0) }
            var totalBE = totalChunks.bigEndian
            withUnsafeBytes(of: &totalBE) { pkt.append(contentsOf: $0) }
            pkt.append(contentsOf: envelope[offset..<end])
            packets.append(pkt)
            offset = end
            chunkIdx &+= 1
        }
        return packets
    }
}

public final class BluetoothPacketReassembler: @unchecked Sendable {
    private struct Assembly {
        let totalChunks: Int
        var slices: [Int: Data]
    }

    private let lock = NSLock()
    private var inFlight: [UInt32: Assembly] = [:]

    public init() {}

    public func ingest(_ packet: Data) -> (packetId: UInt32, completeEnvelope: Data?, ack: BluetoothPacketCodec.AckPacket?) {
        guard packet.count > BluetoothPacketCodec.chunkHeaderSize else { return (0, nil, nil) }
        let s = packet.startIndex
        guard packet[s] == BluetoothPacketCodec.dataTag else { return (0, nil, nil) }

        let packetId = (UInt32(packet[s + 1]) << 24)
            | (UInt32(packet[s + 2]) << 16)
            | (UInt32(packet[s + 3]) << 8)
            | UInt32(packet[s + 4])
        let chunkIdx = Int((UInt16(packet[s + 5]) << 8) | UInt16(packet[s + 6]))
        let totalChunks = Int((UInt16(packet[s + 7]) << 8) | UInt16(packet[s + 8]))
        guard totalChunks > 0, chunkIdx < totalChunks else { return (packetId, nil, nil) }

        let slice = Data(packet[(s + BluetoothPacketCodec.chunkHeaderSize)..<packet.endIndex])

        return lock.withLock {
            var current = inFlight[packetId] ?? Assembly(totalChunks: totalChunks, slices: [:])
            guard current.totalChunks == totalChunks else {
                inFlight.removeValue(forKey: packetId)
                return (packetId, nil, nil)
            }
            current.slices[chunkIdx] = slice
            if current.slices.count == totalChunks {
                var assembled = Data()
                for idx in 0..<totalChunks {
                    guard let part = current.slices[idx] else {
                        inFlight.removeValue(forKey: packetId)
                        return (packetId, nil, nil)
                    }
                    assembled.append(part)
                }
                inFlight.removeValue(forKey: packetId)
                let ack = BluetoothPacketCodec.AckPacket(packetId: packetId, receivedChunks: UInt16(totalChunks))
                return (packetId, assembled, ack)
            } else {
                inFlight[packetId] = current
                if inFlight.count > 16 {
                    inFlight.removeAll()
                }
                return (packetId, nil, nil)
            }
        }
    }

    public func reset() {
        lock.withLock { inFlight.removeAll() }
    }
}

/// Shared 4-byte length-prefixed framing + non-blocking InputStream reader for L2CAP CoC streams.
public final class BluetoothL2CapStreamFramer: @unchecked Sendable {
    private var rxBuffer = Data()

    public init() {}

    public static func writeFramed(_ envelope: Data, to outputStream: OutputStream) -> Bool {
        guard outputStream.hasSpaceAvailable else { return false }
        var framed = Data(capacity: 4 + envelope.count)
        var lenBE = UInt32(envelope.count).bigEndian
        withUnsafeBytes(of: &lenBE) { framed.append(contentsOf: $0) }
        framed.append(envelope)
        return framed.withUnsafeBytes { rawBuf in
            guard let ptr = rawBuf.bindMemory(to: UInt8.self).baseAddress else { return false }
            return outputStream.write(ptr, maxLength: framed.count) == framed.count
        }
    }

    public func readAvailable(from inputStream: InputStream) -> [Data] {
        var chunk = [UInt8](repeating: 0, count: 4096)
        while inputStream.hasBytesAvailable {
            let count = inputStream.read(&chunk, maxLength: chunk.count)
            if count > 0 {
                rxBuffer.append(contentsOf: chunk[0..<count])
            } else {
                break
            }
        }
        var messages: [Data] = []
        while rxBuffer.count >= 4 {
            let s = rxBuffer.startIndex
            let len = Int((UInt32(rxBuffer[s]) << 24)
                | (UInt32(rxBuffer[s + 1]) << 16)
                | (UInt32(rxBuffer[s + 2]) << 8)
                | UInt32(rxBuffer[s + 3]))
            guard len > 0, len <= 2 * 1024 * 1024 else {
                rxBuffer.removeAll()
                break
            }
            guard rxBuffer.count >= 4 + len else { break }
            messages.append(Data(rxBuffer[(s + 4)..<(s + 4 + len)]))
            rxBuffer.removeFirst(4 + len)
        }
        return messages
    }

    public func reset() {
        rxBuffer.removeAll()
    }
}
