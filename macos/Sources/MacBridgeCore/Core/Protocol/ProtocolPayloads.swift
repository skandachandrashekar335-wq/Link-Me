import CryptoKit
import Foundation

public enum BridgeState: String, Codable, Sendable, Equatable {
    case dormantBle = "DORMANT_BLE"
    case handshakeWake = "HANDSHAKE_WAKE"
    case activeWifiStream = "ACTIVE_WIFI_STREAM"
    case cooldownIdle = "COOLDOWN_IDLE"

    public var displayBadge: String {
        switch self {
        case .dormantBle, .cooldownIdle:
            return "BLE Standby"
        case .handshakeWake:
            return "Waking Wi-Fi…"
        case .activeWifiStream:
            return "Wi-Fi Turbo"
        }
    }
}

public enum ProtocolPayloadError: Error, Equatable, Sendable {
    case sensitiveClipboardRejected
    case unsafeFilename(FilenameSanitizationError)
    case invalidFileChunk
}

public struct PairHelloPayload: Codable, Sendable, Equatable {
    public let msgType: String
    public let deviceId: String
    public let deviceName: String
    public let platform: String
    public let ephemeralPublicKey: String
    public let spkiSha256: String
    public let protocolVersion: Int
    public let capabilities: [String]

    public init(
        deviceId: String,
        deviceName: String,
        platform: String,
        ephemeralPublicKey: String,
        spkiSha256: String,
        protocolVersion: Int = 1,
        capabilities: [String] = ["clipboard", "file_transfer", "notifications", "inline_reply", "telemetry", "ring"]
    ) {
        self.msgType = "PAIR_HELLO"
        self.deviceId = deviceId
        self.deviceName = deviceName
        self.platform = platform
        self.ephemeralPublicKey = ephemeralPublicKey
        self.spkiSha256 = spkiSha256
        self.protocolVersion = protocolVersion
        self.capabilities = capabilities
    }
}

public struct PingTelemetryPayload: Codable, Sendable, Equatable {
    public let msgType: String
    public let deviceId: String
    public let batteryLevel: Int
    public let isCharging: Bool
    public let batterySaverActive: Bool
    public let transportState: BridgeState
    public let timestampMs: UInt64

    public init(
        deviceId: String,
        batteryLevel: Int,
        isCharging: Bool,
        batterySaverActive: Bool,
        transportState: BridgeState,
        timestampMs: UInt64
    ) {
        self.msgType = "PING_TELEMETRY"
        self.deviceId = deviceId
        self.batteryLevel = min(100, max(0, batteryLevel))
        self.isCharging = isCharging
        self.batterySaverActive = batterySaverActive
        self.transportState = transportState
        self.timestampMs = timestampMs
    }
}

public struct WakeWifiPayload: Codable, Sendable, Equatable {
    public let msgType: String
    public let deviceId: String
    public let servicePort: UInt16
    public let hostHints: [String]
    public let bleTokenHex: String
    public let reason: String
    public let timestampMs: UInt64
}

public struct ClipboardSyncPayload: Codable, Sendable, Equatable {
    public let msgType: String
    public let originDeviceId: String
    public let clipboardEpoch: UInt64
    public let contentSha256: String
    public let mimeType: String
    public let text: String?
    public let imageBase64: String?
    public let isSensitive: Bool
    public let timestampMs: UInt64

    public init(
        originDeviceId: String,
        clipboardEpoch: UInt64,
        contentSha256: String,
        mimeType: String,
        text: String? = nil,
        imageBase64: String? = nil,
        isSensitive: Bool = false,
        timestampMs: UInt64
    ) {
        self.msgType = "CLIPBOARD_SYNC"
        self.originDeviceId = originDeviceId
        self.clipboardEpoch = clipboardEpoch
        self.contentSha256 = contentSha256
        self.mimeType = mimeType
        self.text = text
        self.imageBase64 = imageBase64
        self.isSensitive = isSensitive
        self.timestampMs = timestampMs
    }

    public func validate() throws {
        if isSensitive {
            throw ProtocolPayloadError.sensitiveClipboardRejected
        }
    }
}

public struct FileOfferPayload: Codable, Sendable, Equatable {
    public let msgType: String
    public let transferId: String
    public let filename: String
    public let byteSize: UInt64
    public let sha256: String
    public let chunkSize: UInt32
    public let totalChunks: UInt32
    public let mimeType: String

    public init(
        transferId: String,
        filename: String,
        byteSize: UInt64,
        sha256: String,
        chunkSize: UInt32 = UInt32(WireFrame.defaultChunkDataBytes),
        totalChunks: UInt32,
        mimeType: String
    ) {
        self.msgType = "FILE_OFFER"
        self.transferId = transferId
        self.filename = filename
        self.byteSize = byteSize
        self.sha256 = sha256
        self.chunkSize = chunkSize
        self.totalChunks = totalChunks
        self.mimeType = mimeType
    }

    public func validateSafeFilename() throws -> String {
        do {
            return try FilenameSanitizer.validateStrict(filename)
        } catch let error as FilenameSanitizationError {
            throw ProtocolPayloadError.unsafeFilename(error)
        }
    }
}

public struct FileResponsePayload: Codable, Sendable, Equatable {
    public let msgType: String
    public let transferId: String
    public let accepted: Bool
    public let resumeChunkIndex: UInt32
    public let reason: String?

    public init(transferId: String, accepted: Bool, resumeChunkIndex: UInt32 = 0, reason: String? = nil) {
        self.msgType = "FILE_RESPONSE"
        self.transferId = transferId
        self.accepted = accepted
        self.resumeChunkIndex = resumeChunkIndex
        self.reason = reason
    }
}

/// Binary payload layout for 0x07 FILE_CHUNK:
/// 16B transferUUID + 4B chunkIndex BE + 4B dataLength BE + 32B chunkSHA256 + raw data bytes.
public struct FileChunkPacket: Sendable, Equatable {
    public static let subheaderSize = 56
    public let transferId: UUID
    public let chunkIndex: UInt32
    public let chunkSha256: Data
    public let data: Data

    public init(transferId: UUID, chunkIndex: UInt32, chunkSha256: Data, data: Data) throws {
        guard chunkSha256.count == 32, data.count <= WireFrame.maxChunkDataBytes else {
            throw ProtocolPayloadError.invalidFileChunk
        }
        self.transferId = transferId
        self.chunkIndex = chunkIndex
        self.chunkSha256 = chunkSha256
        self.data = data
    }

    public func encode() -> Data {
        var out = Data(capacity: Self.subheaderSize + data.count)
        let uuidBytes = withUnsafeBytes(of: transferId.uuid) { Data($0) }
        out.append(uuidBytes)

        var idxBE = chunkIndex.bigEndian
        withUnsafeBytes(of: &idxBE) { out.append(contentsOf: $0) }

        var lenBE = UInt32(data.count).bigEndian
        withUnsafeBytes(of: &lenBE) { out.append(contentsOf: $0) }

        out.append(chunkSha256)
        out.append(data)
        return out
    }

    public static func decode(from payload: Data) throws -> FileChunkPacket {
        guard payload.count >= subheaderSize else {
            throw ProtocolPayloadError.invalidFileChunk
        }
        let s = payload.startIndex
        let uuidBytes = Array(payload[s..<(s + 16)])
        let uuid = UUID(uuid: (
            uuidBytes[0], uuidBytes[1], uuidBytes[2], uuidBytes[3],
            uuidBytes[4], uuidBytes[5], uuidBytes[6], uuidBytes[7],
            uuidBytes[8], uuidBytes[9], uuidBytes[10], uuidBytes[11],
            uuidBytes[12], uuidBytes[13], uuidBytes[14], uuidBytes[15]
        ))
        let chunkIndex = FrameDecoder.readUInt32BE(payload, offset: 16)
        let dataLength = FrameDecoder.readUInt32BE(payload, offset: 20)
        guard dataLength <= UInt32(WireFrame.maxChunkDataBytes),
              payload.count == subheaderSize + Int(dataLength) else {
            throw ProtocolPayloadError.invalidFileChunk
        }
        let chunkSha256 = Data(payload[(s + 24)..<(s + 56)])
        let data = Data(payload[(s + 56)..<(s + 56 + Int(dataLength))])
        return try FileChunkPacket(
            transferId: uuid,
            chunkIndex: chunkIndex,
            chunkSha256: chunkSha256,
            data: data
        )
    }
}

public struct NotificationEventPayload: Codable, Sendable, Equatable {
    public let msgType: String
    public let action: String
    public let sbnKey: String
    public let appName: String
    public let packageName: String
    public let title: String
    public let body: String
    public let timestampMs: UInt64
    public let canReply: Bool

    public init(
        action: String,
        sbnKey: String,
        appName: String,
        packageName: String,
        title: String,
        body: String,
        timestampMs: UInt64,
        canReply: Bool
    ) {
        self.msgType = "NOTIFICATION_EVENT"
        self.action = action
        self.sbnKey = sbnKey
        self.appName = appName
        self.packageName = packageName
        self.title = title
        self.body = body
        self.timestampMs = timestampMs
        self.canReply = canReply
    }
}

public struct DeviceCommandPayload: Codable, Sendable, Equatable {
    public let msgType: String
    public let command: String
    public let sbnKey: String?
    public let replyText: String?
    public let durationSeconds: Int?
    public let timestampMs: UInt64

    public init(
        command: String,
        sbnKey: String? = nil,
        replyText: String? = nil,
        durationSeconds: Int? = nil,
        timestampMs: UInt64
    ) {
        self.msgType = "DEVICE_COMMAND"
        self.command = command
        self.sbnKey = sbnKey
        self.replyText = replyText
        self.durationSeconds = durationSeconds
        self.timestampMs = timestampMs
    }
}

public enum MediaControlAction: String, Codable, CaseIterable, Sendable {
    case playPause = "play_pause"
    case next
    case prev
    case volUp = "vol_up"
    case volDown = "vol_down"
    case mute
}

/// Binary frame 0x0A MEDIA_CONTROL JSON payload.
public struct MediaControlPayload: Codable, Sendable, Equatable {
    public let action: MediaControlAction

    public init(action: MediaControlAction) {
        self.action = action
    }
}

/// Binary frame 0x0B OPEN_URL JSON payload validated strictly for http/https URLs.
public struct OpenUrlPayload: Codable, Sendable, Equatable {
    public let url: String

    public init(url: String) throws {
        let validated = try UrlSanitizer.validateStrict(url)
        self.url = validated.absoluteString
    }

    public func validateStrict() throws -> URL {
        try UrlSanitizer.validateStrict(url)
    }
}

/// Computes & verifies constant-time HMAC proof of X25519 + SAS possession for PAIR_COMMIT (0x0E).
public enum PairingCommitMac {
    public static func computeHex(
        sharedBleSecret: Data,
        deviceId: String,
        spkiSha256: String,
        ephemeralPublicKey: String
    ) -> String {
        let message = Data("LinkMe-PAIR-COMMIT-v1|\(deviceId)|\(spkiSha256.lowercased())|\(ephemeralPublicKey)".utf8)
        let mac = HMAC<SHA256>.authenticationCode(for: message, using: SymmetricKey(data: sharedBleSecret))
        return mac.map { String(format: "%02x", $0) }.joined()
    }
}

/// Signed commitment sent by Android after confirming the 6-digit SAS code (0x0E PAIR_COMMIT).
public struct PairCommitPayload: Codable, Sendable, Equatable {
    public let msgType: String
    public let deviceId: String
    public let deviceName: String
    public let platform: String
    public let ephemeralPublicKey: String
    public let spkiSha256: String
    public let sasMacHex: String
    public let batteryLevel: Int
    public let isCharging: Bool
    public let timestampMs: UInt64

    public init(
        deviceId: String,
        deviceName: String,
        platform: String = "android",
        ephemeralPublicKey: String,
        spkiSha256: String,
        sasMacHex: String,
        batteryLevel: Int,
        isCharging: Bool,
        timestampMs: UInt64 = UInt64(Date().timeIntervalSince1970 * 1000)
    ) {
        self.msgType = "PAIR_COMMIT"
        self.deviceId = deviceId
        self.deviceName = deviceName
        self.platform = platform
        self.ephemeralPublicKey = ephemeralPublicKey
        self.spkiSha256 = spkiSha256.lowercased()
        self.sasMacHex = sasMacHex.lowercased()
        self.batteryLevel = min(100, max(0, batteryLevel))
        self.isCharging = isCharging
        self.timestampMs = timestampMs
    }
}

/// Bidirectional confirmation returned by macOS upon trusting the peer (0x0F PAIR_ACK).
public struct PairAckPayload: Codable, Sendable, Equatable {
    public let msgType: String
    public let deviceId: String
    public let deviceName: String
    public let platform: String
    public let spkiSha256: String
    public let accepted: Bool
    public let batteryLevel: Int
    public let isCharging: Bool
    public let timestampMs: UInt64

    public init(
        deviceId: String,
        deviceName: String,
        platform: String = "macos",
        spkiSha256: String,
        accepted: Bool = true,
        batteryLevel: Int,
        isCharging: Bool,
        timestampMs: UInt64 = UInt64(Date().timeIntervalSince1970 * 1000)
    ) {
        self.msgType = "PAIR_ACK"
        self.deviceId = deviceId
        self.deviceName = deviceName
        self.platform = platform
        self.spkiSha256 = spkiSha256.lowercased()
        self.accepted = accepted
        self.batteryLevel = min(100, max(0, batteryLevel))
        self.isCharging = isCharging
        self.timestampMs = timestampMs
    }
}

/// Screen Mirroring session control (0x0D SCREEN_CONTROL).
public struct ScreenControlPayload: Codable, Sendable, Equatable {
    public let action: String   // "start" | "stop"
    public let source: String   // "macos" | "android"
    public let fps: Int
    public let quality: Int

    public init(action: String, source: String, fps: Int = 15, quality: Int = 60) {
        self.action = action
        self.source = source
        self.fps = min(30, max(1, fps))
        self.quality = min(95, max(20, quality))
    }
}

/// Binary payload layout for 0x0C SCREEN_FRAME:
/// 2B width BE + 2B height BE + 8B timestampMs BE + JPEG compressed image bytes.
public struct ScreenFramePacket: Sendable, Equatable {
    public static let headerSize = 12
    public static let maxJpegBytes = Int(WireFrame.maxControlFrameBytes) - headerSize

    public let width: UInt16
    public let height: UInt16
    public let timestampMs: UInt64
    public let jpegData: Data

    public init(width: UInt16, height: UInt16, timestampMs: UInt64, jpegData: Data) throws {
        guard width > 0, height > 0, !jpegData.isEmpty, jpegData.count <= Self.maxJpegBytes else {
            throw ProtocolPayloadError.invalidFileChunk
        }
        self.width = width
        self.height = height
        self.timestampMs = timestampMs
        self.jpegData = jpegData
    }

    public func encode() -> Data {
        var out = Data(capacity: Self.headerSize + jpegData.count)
        var wBE = width.bigEndian
        withUnsafeBytes(of: &wBE) { out.append(contentsOf: $0) }
        var hBE = height.bigEndian
        withUnsafeBytes(of: &hBE) { out.append(contentsOf: $0) }
        var tsBE = timestampMs.bigEndian
        withUnsafeBytes(of: &tsBE) { out.append(contentsOf: $0) }
        out.append(jpegData)
        return out
    }

    public static func decode(from payload: Data) throws -> ScreenFramePacket {
        guard payload.count > headerSize else {
            throw ProtocolPayloadError.invalidFileChunk
        }
        let s = payload.startIndex
        let w = (UInt16(payload[s]) << 8) | UInt16(payload[s + 1])
        let h = (UInt16(payload[s + 2]) << 8) | UInt16(payload[s + 3])
        let ts = FrameDecoder.readUInt64BE(payload, offset: 4)
        let jpeg = Data(payload[(s + headerSize)..<payload.endIndex])
        return try ScreenFramePacket(width: w, height: h, timestampMs: ts, jpegData: jpeg)
    }
}
