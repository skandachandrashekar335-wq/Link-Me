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
}
