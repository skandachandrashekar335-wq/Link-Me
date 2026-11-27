import CryptoKit
import Foundation
import Testing
@testable import MacBridgeCore

private struct VectorFile: Decodable {
    let validVectors: [ValidVector]
    let invalidVectors: [InvalidVector]
}

private struct ValidVector: Decodable {
    let id: String
    let msgType: UInt8
    let sequenceId: UInt64
    let frameHex: String
}

private struct InvalidVector: Decodable {
    let id: String
    let category: String
    let expectedError: String
    let frameHex: String?
    let sequenceStream: [String]?
}

private final class MockPasteboardAdapter: PasteboardAdapter, @unchecked Sendable {
    private var changeCountValue = 1
    private var sample = PasteboardReadSample(changeCount: 1, typeIdentifiers: [], text: nil, pngData: nil)
    var writtenTexts: [String] = []

    func setLocalSample(text: String?, types: Set<String> = ["public.utf8-plain-text"], pngData: Data? = nil) {
        changeCountValue += 1
        sample = PasteboardReadSample(changeCount: changeCountValue, typeIdentifiers: types, text: text, pngData: pngData)
    }

    @MainActor func currentChangeCount() -> Int { changeCountValue }
    @MainActor func readSample() -> PasteboardReadSample { sample }
    @MainActor func writeText(_ text: String) {
        changeCountValue += 1
        writtenTexts.append(text)
        sample = PasteboardReadSample(changeCount: changeCountValue, typeIdentifiers: ["public.utf8-plain-text"], text: text)
    }
    @MainActor func writePng(_ data: Data) {
        changeCountValue += 1
        sample = PasteboardReadSample(changeCount: changeCountValue, typeIdentifiers: ["public.png"], pngData: data)
    }
}

@Suite("Phase 2 & 4 Protocol, Crypto, and Feature Tests")
struct ProtocolAndSecurityTests {
    private static func loadVectors() throws -> VectorFile {
        let testFile = URL(fileURLWithPath: #filePath)
        let repoRoot = testFile
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let vectorsURL = repoRoot.appendingPathComponent("protocol/test_vectors.json")
        return try JSONDecoder().decode(VectorFile.self, from: Data(contentsOf: vectorsURL))
    }

    private static func dataFromHex(_ hex: String) -> Data {
        Data(hexString: hex)!
    }

    @Test("FrameEncoder & FrameDecoder handle valid vectors including fragmented byte-by-byte stream reads")
    func wireFramingAndFragmentation() throws {
        let vectors = try Self.loadVectors()
        #expect(vectors.validVectors.count == 9)

        var coalesced = Data()
        for vector in vectors.validVectors {
            let raw = Self.dataFromHex(vector.frameHex)
            let decoded = try WireFrame.decode(from: raw)
            #expect(decoded.messageType.rawValue == vector.msgType)
            #expect(decoded.sequenceId == vector.sequenceId)
            #expect(FrameEncoder.encode(decoded) == raw)
            coalesced.append(raw)
        }

        var streamDecoder = FrameDecoder()
        var decodedFrames: [WireFrame] = []
        for byte in coalesced {
            streamDecoder.append(Data([byte]))
            if let frame = try streamDecoder.nextFrame() {
                decodedFrames.append(frame)
            }
        }
        #expect(decodedFrames.count == 9)
        #expect(streamDecoder.bufferedByteCount == 0)
    }

    @Test("Invalid framing, oversized frames, replay, path traversal, and sensitive clipboard vectors fail closed")
    func invalidVectorsFailClosed() async throws {
        let vectors = try Self.loadVectors()
        #expect(vectors.invalidVectors.count == 13)

        for vector in vectors.invalidVectors {
            switch vector.category {
            case "framing":
                let raw = Self.dataFromHex(try #require(vector.frameHex))
                #expect(throws: ProtocolError.self) {
                    _ = try WireFrame.decode(from: raw)
                }

            case "replay":
                let stream = try #require(vector.sequenceStream)
                let guardWindow = ReplayGuard()
                var rejected = false
                for hex in stream {
                    let frame = try WireFrame.decode(from: Self.dataFromHex(hex))
                    do {
                        try await guardWindow.validateAndAccept(frame.sequenceId)
                    } catch is ProtocolError {
                        rejected = true
                    }
                }
                #expect(rejected, "Expected replay rejection for \(vector.id)")

            case "path_traversal":
                let frame = try WireFrame.decode(from: Self.dataFromHex(try #require(vector.frameHex)))
                let offer = try JSONDecoder().decode(FileOfferPayload.self, from: frame.payload)
                #expect(throws: ProtocolPayloadError.self) {
                    _ = try offer.validateSafeFilename()
                }

            case "clipboard_privacy":
                let frame = try WireFrame.decode(from: Self.dataFromHex(try #require(vector.frameHex)))
                let clip = try JSONDecoder().decode(ClipboardSyncPayload.self, from: frame.payload)
                #expect(throws: ProtocolPayloadError.sensitiveClipboardRejected) {
                    try clip.validate()
                }

            default:
                Issue.record("Unexpected category \(vector.category)")
            }
        }
    }

    @Test("64-bit ReplayGuard accepts minor out-of-order packets inside window and blocks duplicates or stale packets")
    func replayGuardSlidingWindow() async throws {
        let guardWindow = ReplayGuard()
        try await guardWindow.validateAndAccept(10)
        try await guardWindow.validateAndAccept(8)
        try await guardWindow.validateAndAccept(9)

        await #expect(throws: ProtocolError.self) {
            try await guardWindow.validateAndAccept(8)
        }

        try await guardWindow.validateAndAccept(80)
        await #expect(throws: ProtocolError.self) {
            try await guardWindow.validateAndAccept(12)
        }
    }

    @Test("FilenameSanitizer neutralizes traversal, Windows paths, NUL bytes, and preserves Unicode & extensions")
    func filenameSanitization() {
        #expect(FilenameSanitizer.sanitize("../../.ssh/authorized_keys") == "authorized_keys")
        #expect(FilenameSanitizer.sanitize("C:\\Windows\\System32\\cmd.exe") == "cmd.exe")
        #expect(FilenameSanitizer.sanitize("evil\u{0000}.sh.png") == "evil.sh.png")
        #expect(FilenameSanitizer.sanitize(".bashrc") == "bashrc")
        #expect(FilenameSanitizer.sanitize("../../../..") == "unnamed_file")
        #expect(FilenameSanitizer.sanitize("résumé_日本語.pdf") == "résumé_日本語.pdf")

        let longStem = String(repeating: "a", count: 240) + ".tar.gz"
        let truncated = FilenameSanitizer.sanitize(longStem)
        #expect(truncated.utf8.count <= 200)
        #expect(truncated.hasSuffix(".gz"))
    }

    @Test("IdentityManager + TrustStore verify self-signed X.509 DER certificates in constant time")
    func identityAndTrustStoreVerification() throws {
        let store = InMemorySecureStore()
        let identityMgr = IdentityManager(store: store)
        let identity = try identityMgr.loadOrCreateIdentity(deviceId: "11111111-1111-4111-8111-111111111111")
        #expect(!identity.certificateDer.isEmpty)
        #expect(identity.certificateSha256Hex.count == 64)

        let trustStore = TrustStore(store: store)
        try trustStore.savePeer(TrustedPeer(
            deviceId: "peer-1",
            deviceName: "Pixel 9",
            pinnedCertSha256: identity.certificateSha256Hex,
            sharedBleSecret: Data(repeating: 0x55, count: 32)
        ))

        #expect(trustStore.verifyPeerCertificate(derBytes: identity.certificateDer, expectedDeviceId: "peer-1"))
        var tamperedDer = identity.certificateDer
        tamperedDer[0] ^= 0xFF
        #expect(!trustStore.verifyPeerCertificate(derBytes: tamperedDer, expectedDeviceId: "peer-1"))
    }

    @Test("PairingCrypto derives identical 6-digit SAS and 32-byte sharedBleSecret and rejects tampered nonces")
    func pairingCryptoAndUriRoundTrip() throws {
        let mac = PairingCrypto()
        let android = PairingCrypto()
        let uri = mac.makePairingUri(deviceId: "mac-1", certSha256Hex: String(repeating: "a", count: 64), tcpPort: 49152)
        let parsed = try PairingUriPayload.parse(uri.uriString)
        #expect(parsed.tcpPort == 49152)

        let macSecrets = try mac.deriveSecrets(peerPublicKeyString: android.publicKeyBase64Url, nonce: mac.nonce)
        let androidSecrets = try android.deriveSecrets(peerPublicKeyString: parsed.publicKeyBase64Url, nonce: Data(hexString: parsed.nonceHex)!)

        #expect(macSecrets.sasCode.count == 6)
        #expect(macSecrets.sasCode == androidSecrets.sasCode)
        #expect(macSecrets.sharedBleSecret.count == 32)
        #expect(macSecrets.sharedBleSecret == androidSecrets.sharedBleSecret)

        var tamperedNonce = mac.nonce
        tamperedNonce[0] ^= 0x01
        let tamperedSecrets = try android.deriveSecrets(peerPublicKeyString: parsed.publicKeyBase64Url, nonce: tamperedNonce)
        #expect(tamperedSecrets.sharedBleSecret != macSecrets.sharedBleSecret)
    }

    @Test("BlePrivacyBeacon validates T-1, T, T+1 buckets and rejects T-2 or wrong keys")
    func blePrivacyBeaconBuckets() {
        let secret = Data((0..<32).map { UInt8($0) })
        let wrongSecret = Data((0..<32).map { UInt8(255 - $0) })
        let beacon = BlePrivacyBeacon(sharedBleSecret: secret)
        let wrongBeacon = BlePrivacyBeacon(sharedBleSecret: wrongSecret)

        let base = Date(timeIntervalSince1970: 1_764_234_900)
        let token = beacon.token(at: base)
        #expect(token.count == 8)
        #expect(beacon.verify(token: token, at: base))
        #expect(beacon.verify(token: token, at: base.addingTimeInterval(900)))
        #expect(beacon.verify(token: token, at: base.addingTimeInterval(-900)))
        #expect(!beacon.verify(token: token, at: base.addingTimeInterval(1800)))
        #expect(!wrongBeacon.verify(token: token, at: base))
    }

    @Test("ClipboardSyncManager prevents echo loops and drops ConcealedType items")
    func clipboardLoopAndPrivacy() async throws {
        let adapter = MockPasteboardAdapter()
        let transport = TransportManager()
        let manager = ClipboardSyncManager(
            deviceId: "11111111-1111-4111-8111-111111111111",
            pasteboard: adapter,
            transport: transport
        )

        let remoteText = "Remote clipboard snippet"
        let remoteHash = ClipboardSyncManager.sha256Hex(Data(remoteText.utf8))
        let remotePayload = ClipboardSyncPayload(
            originDeviceId: "22222222-2222-4222-8222-222222222222",
            clipboardEpoch: 1,
            contentSha256: remoteHash,
            mimeType: "text/plain",
            text: remoteText,
            isSensitive: false,
            timestampMs: 1_764_234_900_000
        )
        try await manager.applyRemoteClipboard(remotePayload)
        #expect(await manager.lastSyncedContentHash == remoteHash)
        #expect(try await manager.pollLocalPasteboardOnce() == nil)

        adapter.setLocalSample(text: "super-secret-password", types: ["public.utf8-plain-text", "org.nspasteboard.ConcealedType"])
        #expect(try await manager.pollLocalPasteboardOnce() == nil)

        adapter.setLocalSample(text: "Fresh Mac copy")
        let emitted = try await manager.pollLocalPasteboardOnce()
        #expect(emitted?.text == "Fresh Mac copy")
    }

    @Test("FileTransferManager streams 2.5 MB file in 64 KB chunks, verifies SHA-256, and rejects corrupted chunks")
    func chunkedFileTransferStreamAndCorruptionRejection() async throws {
        let tempRoot = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        let downloadRoot = tempRoot.appendingPathComponent("Downloads", isDirectory: true)
        try FileManager.default.createDirectory(at: tempRoot, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempRoot) }

        let totalBytes = 2_621_440 // 2.5 MiB = 40 * 64 KB
        var bytes = [UInt8](repeating: 0, count: totalBytes)
        for i in bytes.indices { bytes[i] = UInt8(truncatingIfNeeded: i &* 31 ^ (i >> 8)) }
        let sourceURL = tempRoot.appendingPathComponent("sample_2_5mb.bin")
        try Data(bytes).write(to: sourceURL)

        let sender = FileTransferManager(destinationRoot: downloadRoot)
        let receiver = FileTransferManager(destinationRoot: downloadRoot)
        let transferId = UUID()

        actor FrameCollector {
            var frames: [WireFrame] = []
            func append(_ f: WireFrame) { frames.append(f) }
        }
        let collector = FrameCollector()

        let offer = try await sender.streamOutboundFile(fileURL: sourceURL, transferId: transferId) { frame in
            await collector.append(frame)
        }
        let frames = await collector.frames
        #expect(offer.chunkSize == 65_536)
        #expect(offer.totalChunks == 40)
        #expect(frames.count == 41)

        let response = try await receiver.beginIncomingTransfer(offer: offer)
        #expect(response.accepted)

        for frame in frames.dropFirst() {
            let packet = try FileChunkPacket.decode(from: frame.payload)
            _ = try await receiver.receiveChunk(packet)
        }
        let finalizedURL = try await receiver.finalizeIncomingTransfer(transferId)
        #expect(try FileTransferManager.streamingSha256Hex(fileURL: finalizedURL) == offer.sha256)

        let corruptId = UUID()
        let corruptOffer = FileOfferPayload(
            transferId: corruptId.uuidString.lowercased(),
            filename: "corrupt.bin",
            byteSize: offer.byteSize,
            sha256: offer.sha256,
            chunkSize: 65_536,
            totalChunks: offer.totalChunks,
            mimeType: "application/octet-stream"
        )
        _ = try await receiver.beginIncomingTransfer(offer: corruptOffer)
        var firstPacket = try FileChunkPacket.decode(from: frames[1].payload)
        var badData = firstPacket.data
        badData[0] ^= 0xFF
        firstPacket = try FileChunkPacket(
            transferId: corruptId,
            chunkIndex: 0,
            chunkSha256: firstPacket.chunkSha256,
            data: badData
        )
        await #expect(throws: FileTransferError.chunkHashMismatch(chunkIndex: 0)) {
            _ = try await receiver.receiveChunk(firstPacket)
        }
    }

    @Test("MacNotificationManager serializes mirrored notifications and emits inline reply DeviceCommand")
    func notificationMirrorAndReplyCommand() async throws {
        actor Sink: FrameTransportSink {
            var sent: [WireFrame] = []
            func sendFrame(_ frame: WireFrame, preferBleIfSmall: Bool) async throws {
                sent.append(frame)
            }
        }
        let sink = Sink()
        let manager = MacNotificationManager(transport: sink)
        let event = NotificationEventPayload(
            action: "POST",
            sbnKey: "0|com.chat|1|null|100",
            appName: "Messages",
            packageName: "com.google.android.apps.messaging",
            title: "Sam",
            body: "Ping",
            timestampMs: 1_764_234_900_000,
            canReply: true
        )
        try await manager.handleIncomingEvent(event, postSystemBanner: false)
        #expect(await manager.recentNotifications.count == 1)

        let cmd = try await manager.sendInlineReply(sbnKey: event.sbnKey, replyText: "On my way!")
        #expect(cmd.command == "replyToNotification")
        #expect(cmd.replyText == "On my way!")
        #expect(await sink.sent.count == 1)
    }
}
