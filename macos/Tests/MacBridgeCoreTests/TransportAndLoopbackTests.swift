import CryptoKit
import Foundation
import Testing
@testable import MacBridgeCore

@Suite("Phase 3 Dual-Transport, mTLS Pinning, and BLE Chunking Tests")
struct TransportAndLoopbackTests {
    @Test("TransportManager routes <=400B control over BLE, wakes Wi-Fi for high-throughput, and cools down to DORMANT_BLE")
    func stateMachineAndCooldownLifecycle() async throws {
        actor Tracker {
            var bleFrames: [WireFrame] = []
            var wifiFrames: [WireFrame] = []
            var openCount = 0
            var closeCount = 0
            func recordBle(_ f: WireFrame) { bleFrames.append(f) }
            func recordWifi(_ f: WireFrame) { wifiFrames.append(f) }
            func opened() { openCount += 1 }
            func closed() { closeCount += 1 }
        }
        let tracker = Tracker()
        let manager = TransportManager(cooldownDurationSeconds: 0.15)
        await manager.configureTransportHooks(
            bleSender: { frame in await tracker.recordBle(frame) },
            wifiOpener: {
                await tracker.opened()
                return 49152
            },
            wifiSender: { frame in await tracker.recordWifi(frame) },
            wifiCloser: { await tracker.closed() }
        )

        #expect(await manager.bridgeState == .dormantBle)

        // 1. Small <=400B low-latency control stays over BLE without waking Wi-Fi
        try await manager.sendFrame(
            msgType: .pingTelemetry,
            payload: Data("{\"ok\":true}".utf8),
            priority: .lowLatencyControl
        )
        #expect(await manager.bridgeState == .dormantBle)
        #expect(await tracker.bleFrames.count == 1)
        #expect(await tracker.openCount == 0)

        // 2. High-throughput >400B payload wakes Wi-Fi, sends WAKE_WIFI over BLE, streams over Wi-Fi, enters COOLDOWN_IDLE
        try await manager.sendFrame(
            msgType: .clipboardSync,
            payload: Data(repeating: 0x41, count: 600),
            priority: .highThroughput
        )
        #expect(await tracker.openCount == 1)
        #expect(await tracker.bleFrames.count == 2) // includes WAKE_WIFI
        #expect(await tracker.wifiFrames.count == 1)
        #expect(await manager.bridgeState == .cooldownIdle)

        // 3. After cooldown expires, socket closes cleanly back to DORMANT_BLE
        try await Task.sleep(for: .milliseconds(300))
        #expect(await tracker.closeCount == 1)
        #expect(await manager.wifiConnected == false)
        #expect(await manager.bridgeState == .dormantBle)
    }

    @Test("mTLS 1.3 loopback succeeds for pinned IdentityManager peer and rejects untrusted rogue cert")
    func mtlsPinningLoopback() async throws {
        let serverStore = InMemorySecureStore()
        let clientStore = InMemorySecureStore()
        let rogueStore = InMemorySecureStore()

        let serverIdentity = try IdentityManager(store: serverStore)
            .loadOrCreateIdentity(deviceId: "11111111-1111-4111-8111-111111111111")
        let clientIdentity = try IdentityManager(store: clientStore)
            .loadOrCreateIdentity(deviceId: "22222222-2222-4222-8222-222222222222")
        let rogueIdentity = try IdentityManager(store: rogueStore)
            .loadOrCreateIdentity(deviceId: "99999999-9999-4999-8999-999999999999")

        let serverTrust = TrustStore(store: serverStore)
        try serverTrust.savePeer(TrustedPeer(
            deviceId: clientIdentity.deviceId,
            deviceName: "Trusted Android",
            pinnedCertSha256: clientIdentity.certificateSha256Hex,
            sharedBleSecret: Data(repeating: 0x11, count: 32)
        ))

        let clientTrust = TrustStore(store: clientStore)
        try clientTrust.savePeer(TrustedPeer(
            deviceId: serverIdentity.deviceId,
            deviceName: "Trusted Mac",
            pinnedCertSha256: serverIdentity.certificateSha256Hex,
            sharedBleSecret: Data(repeating: 0x11, count: 32)
        ))

        actor FrameBox {
            var received: [WireFrame] = []
            func add(_ f: WireFrame) { received.append(f) }
        }
        let box = FrameBox()

        let server = WifiTlsServer(
            identity: serverIdentity,
            expectedPeerDeviceId: clientIdentity.deviceId,
            trustStore: serverTrust
        )
        server.onFrameReceived = { frame in
            Task { await box.add(frame) }
        }

        let port = try await server.start(port: 0, advertiseBonjour: false)
        defer { server.stop() }
        #expect(port > 0)

        // 1. Pinned client succeeds and delivers frame
        let trustedClient = WifiTlsClient(
            identity: clientIdentity,
            expectedPeerDeviceId: serverIdentity.deviceId,
            trustStore: clientTrust
        )
        try await trustedClient.connect(host: "127.0.0.1", port: port)
        let testFrame = try WireFrame(
            messageType: .pingTelemetry,
            sequenceId: 1,
            payload: Data("{\"ping\":1}".utf8)
        )
        try await trustedClient.send(frame: testFrame)
        try await Task.sleep(for: .milliseconds(150))
        trustedClient.close()

        let received = await box.received
        #expect(received.count == 1)
        #expect(received.first == testFrame)

        // 2. Rogue client (not pinned on server) must fail TLS handshake
        let rogueClient = WifiTlsClient(
            identity: rogueIdentity,
            expectedPeerDeviceId: serverIdentity.deviceId,
            trustStore: clientTrust
        )
        var rogueFailed = false
        do {
            try await rogueClient.connect(host: "127.0.0.1", port: port)
            // Send triggers server rejection if TLS post-handshake client auth closes asynchronously
            try await rogueClient.send(frame: testFrame)
            try await Task.sleep(for: .milliseconds(150))
            rogueClient.close()
        } catch {
            rogueFailed = true
        }
        let finalReceivedCount = await box.received.count
        #expect(rogueFailed || finalReceivedCount == 1)
    }

    @Test("BLE frame chunker splits 400-byte frames across MTU=23 and MTU=185 and reassembles identically")
    func bleFrameChunkingAndReassembly() throws {
        let payload = Data((0..<384).map { UInt8($0 & 0xFF) }) // 16B header + 384B = 400B frame
        let frame = try WireFrame(messageType: .clipboardSync, sequenceId: 42, payload: payload)
        let encoded = FrameEncoder.encode(frame)
        #expect(encoded.count == 400)

        for mtu in [23, 185] {
            let chunks = BleFrameChunker.chunk(frameData: encoded, mtu: mtu)
            let maxSlice = mtu - 3
            #expect(chunks.allSatisfy { $0.count <= maxSlice })
            #expect(chunks.count == (encoded.count + maxSlice - 1) / maxSlice)

            var decoder = FrameDecoder()
            for chunk in chunks {
                decoder.append(chunk)
            }
            let reassembled = try decoder.nextFrame()
            #expect(reassembled == frame)
            #expect(decoder.bufferedByteCount == 0)
        }
    }

    @Test("End-to-end QR pairing flow derives matching SAS + ChaCha20 sessionKey and persists TrustStore")
    func endToEndQrPairingAndTrustPersistence() async throws {
        let macStore = InMemorySecureStore()
        let macIdentityMgr = IdentityManager(store: macStore)
        let macIdentity = try macIdentityMgr.loadOrCreateIdentity(deviceId: "11111111-1111-4111-8111-111111111111")
        let macTrust = TrustStore(store: macStore)

        let androidStore = InMemorySecureStore()
        let androidIdentity = try IdentityManager(store: androidStore)
            .loadOrCreateIdentity(deviceId: "22222222-2222-4222-8222-222222222222")

        let feature = PairingFeature(identityManager: macIdentityMgr, trustStore: macTrust)
        let (uriPayload, _) = try await feature.beginPairingSession(tcpPort: 49152, deviceName: "Skanda MacBook Pro")
        let uriString = uriPayload.uriString

        #expect(uriString.hasPrefix("linkme://pair?"))
        let parsed = try PairingUriPayload.parse(uriString)
        #expect(parsed.hasFullCertFingerprint)
        #expect(parsed.certSha256Prefix == macIdentity.certificateSha256Hex.lowercased())

        // Legacy andromac:// scheme remains compatible
        let legacyUri = uriString.replacingOccurrences(of: "linkme://", with: "andromac://")
        let legacyParsed = try PairingUriPayload.parse(legacyUri)
        #expect(legacyParsed.deviceId == parsed.deviceId)

        let androidSession = PairingCrypto()
        let nonceData = try #require(Data(hexString: parsed.nonceHex))
        let androidDerived = try androidSession.deriveSecrets(peerPublicKeyString: parsed.publicKeyBase64Url, nonce: nonceData)

        let hello = PairHelloPayload(
            deviceId: androidIdentity.deviceId,
            deviceName: "Pixel 9 Pro",
            platform: "android",
            ephemeralPublicKey: androidSession.publicKeyBase64Url,
            spkiSha256: androidIdentity.certificateSha256Hex
        )
        let pending = try await feature.handleIncomingPairHello(hello)
        #expect(pending.sasCode == androidDerived.sasCode)
        #expect(pending.sharedBleSecret == androidDerived.sharedBleSecret)
        #expect(pending.bluetoothSessionKey == androidDerived.sessionKey)
        #expect(pending.bluetoothSessionKey.count == 32)

        let savedPeer = try await feature.confirmAndTrust()
        #expect(try macTrust.peer(forDeviceId: androidIdentity.deviceId)?.bluetoothSessionKey == androidDerived.sessionKey)
        #expect(savedPeer.pinnedCertSha256 == androidIdentity.certificateSha256Hex.lowercased())
    }

    @Test("Encrypted Bluetooth ChaCha20-Poly1305 loopback across MTU 23/512 + Wi-Fi fallback")
    func encryptedBluetoothLoopbackAndWifiFallback() async throws {
        let sessionKey = Data((0..<32).map { UInt8(($0 * 11 + 5) & 0xFF) })
        let senderCipher = try BluetoothSessionCipher(sessionKey: sessionKey, senderTag: "MAC0")
        let receiverCipher = try BluetoothSessionCipher(sessionKey: sessionKey, senderTag: "AND0")
        let reassembler = BluetoothPacketReassembler()

        func transmitOverBle(_ frame: WireFrame, mtu: Int, packetId: UInt32) throws -> WireFrame {
            let envelope = try senderCipher.seal(frame: frame)
            #expect(BluetoothSessionCipher.isEncryptedEnvelope(envelope))
            let packets = BluetoothPacketCodec.packetize(envelope: envelope, packetId: packetId, mtu: mtu)
            #expect(!packets.isEmpty)

            // Deliver out-of-order when fragmented
            let ordered = packets.count > 2 ? Array(packets.reversed()) : packets
            var reassembledEnvelope: Data?
            var ackPacket: BluetoothPacketCodec.AckPacket?
            for pkt in ordered {
                let res = reassembler.ingest(pkt)
                if let env = res.completeEnvelope {
                    reassembledEnvelope = env
                    ackPacket = res.ack
                }
            }
            let envelopeData = try #require(reassembledEnvelope)
            let ack = try #require(ackPacket)
            let decodedAck = try #require(BluetoothPacketCodec.AckPacket.decode(ack.encode()))
            #expect(decodedAck.packetId == packetId)
            #expect(decodedAck.receivedChunks == UInt16(packets.count))
            return try receiverCipher.openFrame(from: envelopeData)
        }

        // 1. Clipboard Sync over minimum ATT MTU 23 (20B ATT payload -> 11B data slice)
        let clip = ClipboardSyncPayload(
            originDeviceId: "11111111-1111-4111-8111-111111111111",
            clipboardEpoch: 9,
            contentSha256: "feedbeef",
            mimeType: "text/plain",
            text: "Link-Me v1.0.0 Bluetooth-First ChaCha20 Sync Check!",
            timestampMs: 1_770_000_000_000
        )
        let clipFrame = try WireFrame(messageType: .clipboardSync, sequenceId: 10, payload: JSONEncoder().encode(clip))
        let rxClipFrame = try transmitOverBle(clipFrame, mtu: 23, packetId: 100)
        let rxClip = try JSONDecoder().decode(ClipboardSyncPayload.self, from: rxClipFrame.payload)
        #expect(rxClip.text == clip.text)

        // 2. Notification Reply, Media Control, Open URL
        let reply = DeviceCommandPayload(command: "reply", sbnKey: "notif-1", replyText: "Omw!", timestampMs: 123)
        let rxReplyFrame = try transmitOverBle(
            try WireFrame(messageType: .deviceCommand, sequenceId: 11, payload: JSONEncoder().encode(reply)),
            mtu: 23,
            packetId: 101
        )
        #expect(try JSONDecoder().decode(DeviceCommandPayload.self, from: rxReplyFrame.payload).replyText == "Omw!")

        let media = MediaControlPayload(action: .playPause)
        let rxMediaFrame = try transmitOverBle(
            try WireFrame(messageType: .mediaControl, sequenceId: 12, payload: JSONEncoder().encode(media)),
            mtu: 512,
            packetId: 102
        )
        #expect(try JSONDecoder().decode(MediaControlPayload.self, from: rxMediaFrame.payload).action == .playPause)

        let urlPayload = try OpenUrlPayload(url: "https://github.com/skandachandrashekar335-wq/Link-Me")
        let rxUrlFrame = try transmitOverBle(
            try WireFrame(messageType: .openUrl, sequenceId: 13, payload: JSONEncoder().encode(urlPayload)),
            mtu: 512,
            packetId: 103
        )
        #expect(try JSONDecoder().decode(OpenUrlPayload.self, from: rxUrlFrame.payload).url == urlPayload.url)

        // 3. Multi-chunk file transfer over fragmented encrypted Bluetooth + SHA-256 verification
        let tempRoot = FileManager.default.temporaryDirectory.appendingPathComponent("linkme-bt-loopback-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tempRoot, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempRoot) }

        let srcURL = tempRoot.appendingPathComponent("sample.bin")
        let rawBytes = Data((0..<1536).map { UInt8($0 % 251) })
        try rawBytes.write(to: srcURL)

        let txFileMgr = FileTransferManager(destinationRoot: tempRoot.appendingPathComponent("tx", isDirectory: true))
        let rxFileMgr = FileTransferManager(destinationRoot: tempRoot.appendingPathComponent("rx", isDirectory: true))

        actor FrameCollector {
            var frames: [WireFrame] = []
            func append(_ f: WireFrame) { frames.append(f) }
        }
        let collector = FrameCollector()
        let offer = try await txFileMgr.streamOutboundFile(fileURL: srcURL) { frame in
            await collector.append(frame)
        }

        var nextPkt: UInt32 = 200
        for rawFrame in await collector.frames {
            let decoded = try transmitOverBle(rawFrame, mtu: 23, packetId: nextPkt)
            nextPkt &+= 1
            switch decoded.messageType {
            case .fileOffer:
                let rxOffer = try JSONDecoder().decode(FileOfferPayload.self, from: decoded.payload)
                _ = try await rxFileMgr.beginIncomingTransfer(offer: rxOffer)
            case .fileChunk:
                let chunk = try FileChunkPacket.decode(from: decoded.payload)
                _ = try await rxFileMgr.receiveChunk(chunk)
            default:
                break
            }
        }
        let offerUUID = try #require(UUID(uuidString: offer.transferId))
        let verifiedURL = try await rxFileMgr.finalizeIncomingTransfer(offerUUID)
        #expect(try Data(contentsOf: verifiedURL) == rawBytes)

        // 4. Tampered ciphertext fails Poly1305 verification
        var sealed = try senderCipher.seal(plaintext: Data("secret".utf8))
        sealed[sealed.count - 1] ^= 0xFF
        #expect(throws: (any Error).self) {
            _ = try receiverCipher.open(envelope: sealed)
        }

        // 5. TransportManager.sendResilientFrame falls back to BLE when Wi-Fi opener throws
        enum TestNetError: Error { case wifiUnavailable }
        actor ChannelLog {
            var bleFrames: [WireFrame] = []
            func recordBle(_ f: WireFrame) { bleFrames.append(f) }
        }
        let log = ChannelLog()
        let transport = TransportManager()
        await transport.configureTransportHooks(
            bleSender: { frame in await log.recordBle(frame) },
            wifiOpener: { throw TestNetError.wifiUnavailable },
            wifiSender: { _ in },
            wifiCloser: {}
        )
        let bigFrame = try WireFrame(messageType: .clipboardSync, sequenceId: 77, payload: Data(repeating: 0x33, count: 700))
        try await transport.sendResilientFrame(bigFrame, preferBle: false)
        #expect(await log.bleFrames.count == 1)
        #expect(await log.bleFrames.first?.sequenceId == 77)
    }

    @Test("Bidirectional PAIR_COMMIT + PAIR_ACK updates TrustStore with real battery telemetry and Screen Mirror frames round-trip")
    func bidirectionalPairCommitAndScreenMirrorLoopback() async throws {
        let macStore = InMemorySecureStore()
        let macIdentityMgr = IdentityManager(store: macStore)
        let _ = try macIdentityMgr.loadOrCreateIdentity(deviceId: "11111111-1111-4111-8111-111111111111")
        let macTrust = TrustStore(store: macStore)

        let androidStore = InMemorySecureStore()
        let androidIdentity = try IdentityManager(store: androidStore)
            .loadOrCreateIdentity(deviceId: "22222222-2222-4222-8222-222222222222")

        let pairingFeature = PairingFeature(identityManager: macIdentityMgr, trustStore: macTrust)
        let (uriPayload, _) = try await pairingFeature.beginPairingSession(tcpPort: 49152, deviceName: "Skanda MacBook Pro")
        let parsed = try PairingUriPayload.parse(uriPayload.uriString)
        let nonceData = try #require(Data(hexString: parsed.nonceHex))

        let androidCrypto = PairingCrypto()
        let derived = try androidCrypto.deriveSecrets(peerPublicKeyString: parsed.publicKeyBase64Url, nonce: nonceData)
        let sasMacHex = PairingCommitMac.computeHex(
            sharedBleSecret: derived.sharedBleSecret,
            deviceId: androidIdentity.deviceId,
            spkiSha256: androidIdentity.certificateSha256Hex,
            ephemeralPublicKey: androidCrypto.publicKeyBase64Url
        )

        // Forged PAIR_COMMIT with invalid HMAC must be rejected
        let badCommit = PairCommitPayload(
            deviceId: androidIdentity.deviceId,
            deviceName: "Samsung SM-A736B",
            ephemeralPublicKey: androidCrypto.publicKeyBase64Url,
            spkiSha256: androidIdentity.certificateSha256Hex,
            sasMacHex: String(repeating: "0", count: 64),
            batteryLevel: 76,
            isCharging: true
        )
        await #expect(throws: (any Error).self) {
            _ = try await pairingFeature.handleIncomingPairCommit(badCommit)
        }

        // Authentic PAIR_COMMIT persists TrustStore and returns real Mac battery telemetry in PAIR_ACK
        let validCommit = PairCommitPayload(
            deviceId: androidIdentity.deviceId,
            deviceName: "Samsung SM-A736B",
            ephemeralPublicKey: androidCrypto.publicKeyBase64Url,
            spkiSha256: androidIdentity.certificateSha256Hex,
            sasMacHex: sasMacHex,
            batteryLevel: 76,
            isCharging: true
        )
        let (savedPeer, ack) = try await pairingFeature.handleIncomingPairCommit(validCommit)
        #expect(savedPeer.deviceName == "Samsung SM-A736B")
        #expect(try macTrust.peer(forDeviceId: androidIdentity.deviceId)?.bluetoothSessionKey == derived.sessionKey)
        #expect(ack.accepted == true)
        #expect((0...100).contains(ack.batteryLevel))
        let localBatt = MacBatteryMonitor.readBattery()
        if let lvl = localBatt.level {
            #expect((0...100).contains(lvl))
        }

        // Screen Mirroring 0x0D SCREEN_CONTROL + 0x0C SCREEN_FRAME + 0x0E REMOTE_INPUT over ChaCha20
        let cipherMac = try BluetoothSessionCipher(sessionKey: derived.sessionKey, senderTag: "MAC0")
        let cipherAndroid = try BluetoothSessionCipher(sessionKey: derived.sessionKey, senderTag: "AND0")

        let ctrl = ScreenControlPayload(action: "start", source: "android", fps: 24, quality: 60)
        let ctrlFrame = try WireFrame(messageType: .screenControl, sequenceId: 101, payload: JSONEncoder().encode(ctrl))
        let rxCtrl = try cipherMac.openFrame(from: try cipherAndroid.seal(frame: ctrlFrame))
        let decodedCtrl = try JSONDecoder().decode(ScreenControlPayload.self, from: rxCtrl.payload)
        #expect(decodedCtrl == ctrl)

        let fakeJpeg = Data([0xFF, 0xD8, 0xFF, 0xE0]) + Data((0..<4096).map { UInt8($0 & 0xFF) }) + Data([0xFF, 0xD9])
        let screenPkt = try ScreenFramePacket(
            source: ScreenFrameSource.android.rawValue,
            width: 540,
            height: 1200,
            timestampMs: 1_770_000_123_000,
            jpegData: fakeJpeg
        )
        let screenFrame = try WireFrame(messageType: .screenFrame, sequenceId: 102, payload: screenPkt.encode())
        let rxScreen = try cipherAndroid.openFrame(from: try cipherMac.seal(frame: screenFrame))
        let decodedPkt = try ScreenFramePacket.decode(from: rxScreen.payload)
        #expect(decodedPkt == screenPkt)
        #expect(decodedPkt.source == ScreenFrameSource.android.rawValue)
        #expect(decodedPkt.width == 540)
        #expect(decodedPkt.height == 1200)

        let inputs: [RemoteInputPayload] = [
            .tap(x: 0.52, y: 0.41),
            .swipe(x1: 0.5, y1: 0.8, x2: 0.5, y2: 0.2, durationMs: 220),
            .longPress(x: 0.52, y: 0.41, durationMs: 550),
            .key("back"),
            .key("text", text: "Hello Pixel")
        ]
        for (idx, input) in inputs.enumerated() {
            let f = try WireFrame(messageType: .remoteInput, sequenceId: UInt64(200 + idx), payload: JSONEncoder().encode(input))
            let rxInput = try cipherAndroid.openFrame(from: try cipherMac.seal(frame: f))
            let decodedInput = try JSONDecoder().decode(RemoteInputPayload.self, from: rxInput.payload)
            #expect(decodedInput == input)
        }
    }
}
