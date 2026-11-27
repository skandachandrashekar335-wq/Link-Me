import Foundation

public enum WireMessageType: UInt8, Codable, Sendable, CaseIterable {
    case pairHello = 0x01
    case pingTelemetry = 0x02
    case wakeWifi = 0x03
    case clipboardSync = 0x04
    case fileOffer = 0x05
    case fileResponse = 0x06
    case fileChunk = 0x07
    case notificationEvent = 0x08
    case deviceCommand = 0x09
}

public enum ProtocolError: Error, Equatable, Sendable {
    case truncatedHeader
    case invalidMagic
    case unsupportedVersion(UInt8)
    case unknownMessageType(UInt8)
    case frameTooLarge(UInt32)
    case truncatedPayload(expected: Int, actual: Int)
    case replayDetected(received: UInt64, highestAccepted: UInt64)
}

/// Binary wire frame:
/// 2B Magic (0x414D) + 1B Version (0x01) + 1B MsgType + 4B PayloadLength BE + 8B SequenceID BE + Payload.
public struct WireFrame: Sendable, Equatable {
    public static let magic: UInt16 = 0x414D
    public static let version: UInt8 = 0x01
    public static let headerSize = 16
    public static let maxControlFrameBytes: UInt32 = 1_048_576 // 1 MB
    public static let maxChunkDataBytes: Int = 262_144 // 256 KB
    public static let defaultChunkDataBytes: Int = 65_536 // 64 KB

    public let messageType: WireMessageType
    public let sequenceId: UInt64
    public let payload: Data

    public init(messageType: WireMessageType, sequenceId: UInt64, payload: Data) throws {
        guard payload.count <= Int(Self.maxControlFrameBytes) else {
            throw ProtocolError.frameTooLarge(UInt32(clamping: payload.count))
        }
        self.messageType = messageType
        self.sequenceId = sequenceId
        self.payload = payload
    }

    public func encode() -> Data {
        FrameEncoder.encode(self)
    }

    public static func decode(from data: Data) throws -> WireFrame {
        var decoder = FrameDecoder()
        decoder.append(data)
        guard let frame = try decoder.nextFrame() else {
            if data.count < headerSize {
                throw ProtocolError.truncatedHeader
            }
            let declaredLen = FrameDecoder.readUInt32BE(data, offset: 4)
            throw ProtocolError.truncatedPayload(
                expected: Int(declaredLen),
                actual: max(0, data.count - headerSize)
            )
        }
        return frame
    }
}

public enum FrameEncoder {
    public static func encode(_ frame: WireFrame) -> Data {
        var data = Data(capacity: WireFrame.headerSize + frame.payload.count)
        data.append(0x41)
        data.append(0x4D)
        data.append(WireFrame.version)
        data.append(frame.messageType.rawValue)

        var lengthBE = UInt32(frame.payload.count).bigEndian
        withUnsafeBytes(of: &lengthBE) { data.append(contentsOf: $0) }

        var seqBE = frame.sequenceId.bigEndian
        withUnsafeBytes(of: &seqBE) { data.append(contentsOf: $0) }

        data.append(frame.payload)
        return data
    }
}

/// Stream reassembly buffer handling partial TCP reads and coalesced frames with strict bounds checks.
public struct FrameDecoder: Sendable {
    private var buffer = Data()
    private let maxAllowedSize: UInt32

    public init(maxAllowedSize: UInt32 = WireFrame.maxControlFrameBytes) {
        self.maxAllowedSize = maxAllowedSize
    }

    public var bufferedByteCount: Int { buffer.count }

    public mutating func append(_ chunk: Data) {
        buffer.append(chunk)
    }

    public mutating func reset() {
        buffer.removeAll(keepingCapacity: false)
    }

    public mutating func nextFrame() throws -> WireFrame? {
        guard buffer.count >= WireFrame.headerSize else {
            return nil
        }

        let b0 = buffer[buffer.startIndex]
        let b1 = buffer[buffer.startIndex + 1]
        guard b0 == 0x41, b1 == 0x4D else {
            buffer.removeAll()
            throw ProtocolError.invalidMagic
        }

        let version = buffer[buffer.startIndex + 2]
        guard version == WireFrame.version else {
            buffer.removeAll()
            throw ProtocolError.unsupportedVersion(version)
        }

        let rawType = buffer[buffer.startIndex + 3]
        guard let msgType = WireMessageType(rawValue: rawType) else {
            buffer.removeAll()
            throw ProtocolError.unknownMessageType(rawType)
        }

        let payloadLength = Self.readUInt32BE(buffer, offset: 4)
        guard payloadLength <= maxAllowedSize else {
            buffer.removeAll()
            throw ProtocolError.frameTooLarge(payloadLength)
        }

        let totalNeeded = WireFrame.headerSize + Int(payloadLength)
        guard buffer.count >= totalNeeded else {
            return nil
        }

        let sequenceId = Self.readUInt64BE(buffer, offset: 8)
        let payloadStart = buffer.startIndex + WireFrame.headerSize
        let payloadEnd = payloadStart + Int(payloadLength)
        let payload = Data(buffer[payloadStart..<payloadEnd])

        buffer.removeFirst(totalNeeded)
        return try WireFrame(messageType: msgType, sequenceId: sequenceId, payload: payload)
    }

    public mutating func drainFrames() throws -> [WireFrame] {
        var frames: [WireFrame] = []
        while let frame = try nextFrame() {
            frames.append(frame)
        }
        return frames
    }

    static func readUInt32BE(_ data: Data, offset: Int) -> UInt32 {
        let base = data.startIndex + offset
        return (UInt32(data[base]) << 24)
            | (UInt32(data[base + 1]) << 16)
            | (UInt32(data[base + 2]) << 8)
            | UInt32(data[base + 3])
    }

    static func readUInt64BE(_ data: Data, offset: Int) -> UInt64 {
        let base = data.startIndex + offset
        var value: UInt64 = 0
        for i in 0..<8 {
            value = (value << 8) | UInt64(data[base + i])
        }
        return value
    }
}
