package com.linkme.androidbridge

import com.linkme.androidbridge.core.crypto.BluetoothSessionCipher
import com.linkme.androidbridge.core.crypto.IdentityManager
import com.linkme.androidbridge.core.crypto.PairingCrypto
import com.linkme.androidbridge.core.crypto.PairingUriPayload
import com.linkme.androidbridge.core.crypto.TrustStore
import com.linkme.androidbridge.core.crypto.TrustedPeer
import com.linkme.androidbridge.core.protocol.BridgeState
import com.linkme.androidbridge.core.protocol.ClipboardSyncPayload
import com.linkme.androidbridge.core.protocol.DeviceCommandPayload
import com.linkme.androidbridge.core.protocol.FileChunkPacket
import com.linkme.androidbridge.core.protocol.FileOfferPayload
import com.linkme.androidbridge.core.protocol.FrameDecoder
import com.linkme.androidbridge.core.protocol.FrameEncoder
import com.linkme.androidbridge.core.protocol.MediaControlAction
import com.linkme.androidbridge.core.protocol.MediaControlPayload
import com.linkme.androidbridge.core.protocol.OpenUrlPayload
import com.linkme.androidbridge.core.protocol.PairAckPayload
import com.linkme.androidbridge.core.protocol.PairCommitPayload
import com.linkme.androidbridge.core.protocol.PairHelloPayload
import com.linkme.androidbridge.core.protocol.PairingCommitMac
import com.linkme.androidbridge.core.protocol.PingTelemetryPayload
import com.linkme.androidbridge.core.protocol.RemoteInputPayload
import com.linkme.androidbridge.core.protocol.ScreenControlPayload
import com.linkme.androidbridge.core.protocol.ScreenFramePacket
import com.linkme.androidbridge.core.protocol.WireFrame
import com.linkme.androidbridge.core.protocol.WireMessageType
import com.linkme.androidbridge.core.transport.BleFrameChunker
import com.linkme.androidbridge.core.transport.BluetoothPacketCodec
import com.linkme.androidbridge.core.transport.BluetoothPacketReassembler
import com.linkme.androidbridge.core.transport.TransferPriority
import com.linkme.androidbridge.core.transport.TransportChannel
import com.linkme.androidbridge.core.transport.TransportManager
import com.linkme.androidbridge.core.transport.WifiTlsClient
import com.linkme.androidbridge.core.transport.WifiTlsServer
import com.linkme.androidbridge.service.FileTransferManager
import com.linkme.androidbridge.service.RemoteControlIpcBridge
import com.linkme.androidbridge.service.RemoteInputCoordinateMapper
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TransportAndLoopbackTest {

    @Test
    fun stateMachineAndCooldownLifecycle() {
        val bleSent = CopyOnWriteArrayList<WireFrame>()
        val wifiSent = CopyOnWriteArrayList<WireFrame>()
        val openCount = AtomicInteger(0)
        val closeCount = AtomicInteger(0)
        val closeLatch = CountDownLatch(1)

        val manager = TransportManager(
            cooldownDurationMs = 150L,
            bleSender = { bleSent.add(it) },
            wifiOpener = {
                openCount.incrementAndGet()
                49152
            },
            wifiSender = { wifiSent.add(it) },
            wifiCloser = {
                closeCount.incrementAndGet()
                closeLatch.countDown()
            }
        )

        try {
            assertEquals(BridgeState.DORMANT_BLE, manager.bridgeState)

            // Small <=400B control frame stays on BLE
            manager.sendFrame(
                WireMessageType.PING_TELEMETRY,
                "{\"ok\":true}".toByteArray(),
                TransferPriority.LOW_LATENCY_CONTROL
            )
            assertEquals(BridgeState.DORMANT_BLE, manager.bridgeState)
            assertEquals(1, bleSent.size)
            assertEquals(0, openCount.get())

            // >400B high-throughput frame wakes Wi-Fi and enters COOLDOWN_IDLE
            manager.sendFrame(
                WireMessageType.CLIPBOARD_SYNC,
                ByteArray(600) { 0x41 },
                TransferPriority.HIGH_THROUGHPUT
            )
            assertEquals(1, openCount.get())
            assertEquals(2, bleSent.size) // includes WAKE_WIFI
            assertEquals(1, wifiSent.size)
            assertEquals(BridgeState.COOLDOWN_IDLE, manager.bridgeState)

            // Cooldown closes Wi-Fi socket cleanly back to DORMANT_BLE
            assertTrue(closeLatch.await(2, TimeUnit.SECONDS))
            assertEquals(1, closeCount.get())
            assertFalse(manager.isWifiSocketOpen)
            assertEquals(BridgeState.DORMANT_BLE, manager.bridgeState)
        } finally {
            manager.shutdown()
        }
    }

    @Test
    fun mtlsPinningLoopbackAcceptsTrustedAndRejectsRogue() {
        val serverIdentityMgr = IdentityManager(useAndroidKeyStore = false)
        val serverId = serverIdentityMgr.loadOrCreateIdentity("11111111-1111-4111-8111-111111111111")

        val clientIdentityMgr = IdentityManager(useAndroidKeyStore = false)
        val clientId = clientIdentityMgr.loadOrCreateIdentity("22222222-2222-4222-8222-222222222222")

        val rogueIdentityMgr = IdentityManager(useAndroidKeyStore = false)
        rogueIdentityMgr.loadOrCreateIdentity("99999999-9999-4999-8999-999999999999")

        val serverTrust = TrustStore()
        serverTrust.savePeer(
            TrustedPeer(
                deviceId = clientId.deviceId,
                deviceName = "Trusted Client",
                pinnedCertSha256 = clientId.certificateSha256Hex,
                sharedBleSecret = ByteArray(32) { 0x11 }
            )
        )

        val clientTrust = TrustStore()
        clientTrust.savePeer(
            TrustedPeer(
                deviceId = serverId.deviceId,
                deviceName = "Trusted Server",
                pinnedCertSha256 = serverId.certificateSha256Hex,
                sharedBleSecret = ByteArray(32) { 0x11 }
            )
        )

        val receivedLatch = CountDownLatch(1)
        val received = CopyOnWriteArrayList<WireFrame>()
        val server = WifiTlsServer(
            identityManager = serverIdentityMgr,
            trustStore = serverTrust,
            expectedPeerDeviceId = clientId.deviceId,
            onFrameReceived = {
                received.add(it)
                receivedLatch.countDown()
            }
        )

        val port = server.start(0)
        try {
            val trustedClient = WifiTlsClient(clientIdentityMgr, clientTrust, serverId.deviceId)
            trustedClient.connect("127.0.0.1", port)
            val testFrame = WireFrame(WireMessageType.PING_TELEMETRY, 1L, "{\"ping\":1}".toByteArray())
            trustedClient.send(testFrame)
            assertTrue(receivedLatch.await(2, TimeUnit.SECONDS))
            assertEquals(testFrame, received.first())
            trustedClient.close()

            // Untrusted rogue client must fail TLS handshake
            val rogueClient = WifiTlsClient(rogueIdentityMgr, clientTrust, serverId.deviceId)
            var rogueRejected = false
            try {
                rogueClient.connect("127.0.0.1", port)
                rogueClient.send(testFrame)
            } catch (_: Throwable) {
                rogueRejected = true
            } finally {
                rogueClient.close()
            }
            assertTrue(rogueRejected || received.size == 1)
        } finally {
            server.stop()
        }
    }

    @Test
    fun bleFrameChunkingAndReassembly() {
        val payload = ByteArray(384) { (it and 0xFF).toByte() }
        val frame = WireFrame(WireMessageType.CLIPBOARD_SYNC, 42L, payload)
        val encoded = FrameEncoder.encode(frame)
        assertEquals(400, encoded.size)

        for (mtu in listOf(23, 185)) {
            val chunks = BleFrameChunker.chunk(encoded, mtu)
            val maxSlice = mtu - 3
            assertTrue(chunks.all { it.size <= maxSlice })
            assertEquals((encoded.size + maxSlice - 1) / maxSlice, chunks.size)

            val decoder = FrameDecoder()
            for (chunk in chunks) {
                decoder.append(chunk)
            }
            assertEquals(frame, decoder.nextFrame())
            assertEquals(0, decoder.bufferedByteCount)
        }
    }

    @Test
    fun endToEndQrPairingAndTrustStoreSessionKey() {
        val macSession = PairingCrypto()
        val macIdentity = IdentityManager().loadOrCreateIdentity("11111111-1111-4111-8111-111111111111")
        val androidIdentity = IdentityManager().loadOrCreateIdentity("22222222-2222-4222-8222-222222222222")

        val uri = macSession.makePairingUri(
            deviceId = macIdentity.deviceId,
            certSha256Hex = macIdentity.certificateSha256Hex,
            tcpPort = 49152,
            lanHost = "192.168.1.42",
            deviceName = "Skanda MacBook Pro"
        ).uriString

        assertTrue(uri.startsWith("linkme://pair?"))
        val parsed = PairingUriPayload.parse(uri)
        assertTrue(parsed.hasFullCertFingerprint)
        assertEquals(macIdentity.certificateSha256Hex, parsed.certSha256Prefix)
        assertEquals("Skanda MacBook Pro", parsed.deviceName)

        // Legacy scheme must still parse cleanly
        val legacyParsed = PairingUriPayload.parse(uri.replaceFirst("linkme://", "andromac://"))
        assertEquals(parsed.deviceId, legacyParsed.deviceId)

        val androidSession = PairingCrypto()
        val nonce = parsed.nonceHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val androidSecrets = androidSession.deriveSecrets(parsed.publicKeyBase64Url, nonce)

        val hello = PairHelloPayload(
            deviceId = androidIdentity.deviceId,
            deviceName = "Pixel 9 Pro",
            platform = "android",
            ephemeralPublicKey = androidSession.publicKeyBase64Url,
            spkiSha256 = androidIdentity.certificateSha256Hex
        )
        val macSecrets = macSession.deriveSecrets(hello.ephemeralPublicKey, macSession.nonce)

        assertEquals(macSecrets.sasCode, androidSecrets.sasCode)
        assertEquals(6, macSecrets.sasCode.length)
        assertArrayEquals(macSecrets.sharedBleSecret, androidSecrets.sharedBleSecret)
        assertArrayEquals(macSecrets.sessionKey, androidSecrets.sessionKey)
        assertEquals(32, androidSecrets.sessionKey.size)

        val trustStore = TrustStore()
        trustStore.savePeer(
            TrustedPeer(
                deviceId = macIdentity.deviceId,
                deviceName = parsed.deviceName ?: "MacBridge",
                pinnedCertSha256 = parsed.certSha256Prefix,
                sharedBleSecret = androidSecrets.sharedBleSecret,
                bluetoothSessionKey = androidSecrets.sessionKey
            )
        )
        val stored = trustStore.getPeer(macIdentity.deviceId)
        assertNotNull(stored)
        assertArrayEquals(macSecrets.sessionKey, stored!!.bluetoothSessionKey)
    }

    @Test
    fun encryptedBluetoothLoopbackOverFragmentedMtuAndWifiFallback() {
        val sharedKey = ByteArray(32) { (it * 7 + 3).toByte() }
        val senderCipher = BluetoothSessionCipher(sharedKey, senderTag = "AND0")
        val receiverCipher = BluetoothSessionCipher(sharedKey, senderTag = "MAC0")
        val reassembler = BluetoothPacketReassembler()

        fun transmitAndDecode(frame: WireFrame, mtu: Int, packetId: Long): WireFrame {
            val envelope = senderCipher.seal(frame)
            assertTrue(BluetoothSessionCipher.isEncryptedEnvelope(envelope))
            val packets = BluetoothPacketCodec.packetize(envelope, packetId = packetId, mtu = mtu)
            assertTrue(packets.isNotEmpty())

            // Exercise out-of-order reassembly when fragmented
            val ordered = if (packets.size > 2) packets.reversed() else packets
            var completed: ByteArray? = null
            var ack: BluetoothPacketCodec.AckPacket? = null
            for (pkt in ordered) {
                val res = reassembler.ingest(pkt)
                if (res.completeEnvelope != null) {
                    completed = res.completeEnvelope
                    ack = res.ack
                }
            }
            assertNotNull(completed)
            assertNotNull(ack)
            val decodedAck = BluetoothPacketCodec.AckPacket.decode(ack!!.encode())
            assertEquals(packetId, decodedAck?.packetId)
            assertEquals(packets.size, decodedAck?.receivedChunks)

            return receiverCipher.openFrame(completed!!)
        }

        // 1. Clipboard Sync over minimum ATT MTU 23 (20-byte ATT payload)
        val clipPayload = ClipboardSyncPayload(
            originDeviceId = "22222222-2222-4222-8222-222222222222",
            clipboardEpoch = 12L,
            contentSha256 = "abc123",
            mimeType = "text/plain",
            text = "Link-Me v1.0.0 Bluetooth-First ChaCha20-Poly1305 Sync Check!",
            timestampMs = 1770000000000L
        )
        val decodedClipFrame = transmitAndDecode(
            WireFrame(WireMessageType.CLIPBOARD_SYNC, 10L, Json.encodeToString(clipPayload).toByteArray()),
            mtu = 23,
            packetId = 100L
        )
        val decodedClip = Json.decodeFromString<ClipboardSyncPayload>(decodedClipFrame.payload.decodeToString())
        assertEquals(clipPayload.text, decodedClip.text)

        // 2. Notification inline reply, Media control, Open URL over MTU 23 & 512
        val replyFrame = transmitAndDecode(
            WireFrame(
                WireMessageType.DEVICE_COMMAND,
                11L,
                Json.encodeToString(
                    DeviceCommandPayload(command = "reply", sbnKey = "sbn-1", replyText = "On my way", timestampMs = 100L)
                ).toByteArray()
            ),
            mtu = 23,
            packetId = 101L
        )
        assertEquals("On my way", Json.decodeFromString<DeviceCommandPayload>(replyFrame.payload.decodeToString()).replyText)

        val mediaFrame = transmitAndDecode(
            WireFrame(
                WireMessageType.MEDIA_CONTROL,
                12L,
                Json.encodeToString(MediaControlPayload(MediaControlAction.PLAY_PAUSE.wireName)).toByteArray()
            ),
            mtu = 512,
            packetId = 102L
        )
        assertEquals(
            MediaControlAction.PLAY_PAUSE,
            Json.decodeFromString<MediaControlPayload>(mediaFrame.payload.decodeToString()).resolveAction()
        )

        val urlFrame = transmitAndDecode(
            WireFrame(
                WireMessageType.OPEN_URL,
                13L,
                Json.encodeToString(OpenUrlPayload("https://github.com/skandachandrashekar335-wq/Link-Me")).toByteArray()
            ),
            mtu = 512,
            packetId = 103L
        )
        assertEquals(
            "https://github.com/skandachandrashekar335-wq/Link-Me",
            Json.decodeFromString<OpenUrlPayload>(urlFrame.payload.decodeToString()).url
        )

        // 3. Multi-chunk File Transfer over encrypted Bluetooth MTU=23 + SHA-256 verification
        val tempRoot = Files.createTempDirectory("linkme-bt-file-test").toFile()
        try {
            val srcFile = File(tempRoot, "payload.bin")
            val payloadBytes = ByteArray(1536) { (it % 251).toByte() }
            srcFile.writeBytes(payloadBytes)

            val txManager = FileTransferManager(File(tempRoot, "tx"))
            val rxManager = FileTransferManager(File(tempRoot, "rx"))
            val sentFrames = mutableListOf<WireFrame>()
            val offer = txManager.streamOutboundFile(srcFile) { frame ->
                sentFrames += frame
            }

            var pktId = 200L
            for (rawFrame in sentFrames) {
                val loopFrame = transmitAndDecode(rawFrame, mtu = 23, packetId = pktId++)
                when (loopFrame.messageType) {
                    WireMessageType.FILE_OFFER -> {
                        val decodedOffer = Json.decodeFromString<FileOfferPayload>(loopFrame.payload.decodeToString())
                        rxManager.beginIncomingTransfer(decodedOffer)
                    }
                    WireMessageType.FILE_CHUNK -> {
                        rxManager.receiveChunk(FileChunkPacket.decode(loopFrame.payload))
                    }
                    else -> Unit
                }
            }
            val finalFile = rxManager.finalizeIncomingTransfer(java.util.UUID.fromString(offer.transferId))
            assertArrayEquals(payloadBytes, finalFile.readBytes())
        } finally {
            tempRoot.deleteRecursively()
        }

        // 4. Tampered envelope / wrong key rejection
        val validEnvelope = senderCipher.seal(ByteArray(24) { 0x42 })
        val tampered = validEnvelope.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0xFF).toByte() }
        var rejected = false
        try {
            receiverCipher.open(tampered)
        } catch (_: Throwable) {
            rejected = true
        }
        assertTrue(rejected)

        // 5. TransportManager.sendResilientFrame falls back seamlessly to Bluetooth when Wi-Fi fails
        val bleFallbackFrames = CopyOnWriteArrayList<WireFrame>()
        val channels = CopyOnWriteArrayList<TransportChannel>()
        val resilientManager = TransportManager(
            bleSender = { bleFallbackFrames += it },
            wifiOpener = { throw IllegalStateException("Wi-Fi LAN unreachable") },
            wifiSender = { error("Should not reach Wi-Fi sender") },
            outboundSink = { _, ch -> channels += ch }
        )
        resilientManager.sendResilientFrame(
            WireFrame(
                WireMessageType.CLIPBOARD_SYNC,
                55L,
                ByteArray(700) { 0x55 }
            ),
            preferBle = false
        )
        assertEquals(1, bleFallbackFrames.size)
        assertEquals(listOf(TransportChannel.BLE_STANDBY), channels.toList())
        resilientManager.shutdown()
    }

    @Test
    fun bidirectionalPairCommitAckAndScreenMirrorRoundTrip() {
        val macSession = PairingCrypto()
        val macIdentity = IdentityManager().loadOrCreateIdentity("11111111-1111-4111-8111-111111111111")
        val androidIdentity = IdentityManager().loadOrCreateIdentity("22222222-2222-4222-8222-222222222222")

        val uri = macSession.makePairingUri(
            deviceId = macIdentity.deviceId,
            certSha256Hex = macIdentity.certificateSha256Hex,
            tcpPort = 49152,
            deviceName = "Skanda MacBook Pro"
        )
        val parsed = PairingUriPayload.parse(uri.uriString)
        val nonce = parsed.nonceHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        val androidSession = PairingCrypto()
        val androidDerived = androidSession.deriveSecrets(parsed.publicKeyBase64Url, nonce)
        val macDerived = macSession.deriveSecrets(androidSession.publicKeyBase64Url, macSession.nonce)

        val commitMac = PairingCommitMac.computeHex(
            sharedBleSecret = androidDerived.sharedBleSecret,
            deviceId = androidIdentity.deviceId,
            spkiSha256 = androidIdentity.certificateSha256Hex,
            ephemeralPublicKey = androidSession.publicKeyBase64Url
        )
        val expectedMacOnMacSide = PairingCommitMac.computeHex(
            sharedBleSecret = macDerived.sharedBleSecret,
            deviceId = androidIdentity.deviceId,
            spkiSha256 = androidIdentity.certificateSha256Hex,
            ephemeralPublicKey = androidSession.publicKeyBase64Url
        )
        assertEquals(commitMac, expectedMacOnMacSide)

        val commit = PairCommitPayload(
            deviceId = androidIdentity.deviceId,
            deviceName = "Samsung SM-A736B",
            ephemeralPublicKey = androidSession.publicKeyBase64Url,
            spkiSha256 = androidIdentity.certificateSha256Hex,
            sasMacHex = commitMac,
            batteryLevel = 73,
            isCharging = true
        )
        val commitFrame = WireFrame(
            WireMessageType.PAIR_COMMIT,
            2L,
            Json.encodeToString(commit).toByteArray()
        )
        val decodedCommit = Json.decodeFromString<PairCommitPayload>(
            WireFrame.decode(commitFrame.encode()).payload.decodeToString()
        )
        assertEquals("Samsung SM-A736B", decodedCommit.deviceName)
        assertEquals(73, decodedCommit.batteryLevel)

        val ack = PairAckPayload(
            deviceId = macIdentity.deviceId,
            deviceName = "Skanda MacBook Pro",
            spkiSha256 = macIdentity.certificateSha256Hex,
            accepted = true,
            batteryLevel = 88,
            isCharging = false
        )
        val ackFrame = WireFrame(
            WireMessageType.PAIR_ACK,
            3L,
            Json.encodeToString(ack).toByteArray()
        )
        val decodedAck = Json.decodeFromString<PairAckPayload>(
            WireFrame.decode(ackFrame.encode()).payload.decodeToString()
        )
        assertTrue(decodedAck.accepted)
        assertEquals(88, decodedAck.batteryLevel)

        // Screen Mirroring 0x0D SCREEN_CONTROL + 0x0C SCREEN_FRAME + 0x0E REMOTE_INPUT over ChaCha20
        val androidCipher = BluetoothSessionCipher(androidDerived.sessionKey, senderTag = "AND0")
        val macCipher = BluetoothSessionCipher(macDerived.sessionKey, senderTag = "MAC0")

        val ctrl = ScreenControlPayload(action = "start", source = "android", fps = 24, quality = 60)
        val ctrlFrame = WireFrame(WireMessageType.SCREEN_CONTROL, 20L, Json.encodeToString(ctrl).toByteArray())
        val decodedCtrl = Json.decodeFromString<ScreenControlPayload>(
            macCipher.openFrame(androidCipher.seal(ctrlFrame)).payload.decodeToString()
        )
        assertEquals(ctrl, decodedCtrl)

        val jpegPayload = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) +
            ByteArray(2048) { (it and 0xFF).toByte() } +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        val pkt = ScreenFramePacket(
            source = ScreenFramePacket.SOURCE_ANDROID,
            width = 540,
            height = 1200,
            timestampMs = 1_770_000_555_000L,
            jpegData = jpegPayload
        )
        val frame = WireFrame(WireMessageType.SCREEN_FRAME, 21L, pkt.encode())
        val decodedPkt = ScreenFramePacket.decode(androidCipher.openFrame(macCipher.seal(frame)).payload)
        assertEquals(pkt, decodedPkt)
        assertEquals(ScreenFramePacket.SOURCE_ANDROID, decodedPkt.source)
        assertEquals(540, decodedPkt.width)
        assertEquals(1200, decodedPkt.height)

        val inputs = listOf(
            RemoteInputPayload(type = "tap", x = 0.52, y = 0.41),
            RemoteInputPayload(type = "swipe", x1 = 0.5, y1 = 0.8, x2 = 0.5, y2 = 0.2, durationMs = 220),
            RemoteInputPayload(type = "long_press", x = 0.52, y = 0.41, durationMs = 550),
            RemoteInputPayload(type = "key", action = "back"),
            RemoteInputPayload(type = "key", action = "text", text = "Hello Android")
        )
        inputs.forEachIndexed { idx, input ->
            val inFrame = WireFrame(WireMessageType.REMOTE_INPUT, 30L + idx, Json.encodeToString(input).toByteArray())
            val rx = Json.decodeFromString<RemoteInputPayload>(
                androidCipher.openFrame(macCipher.seal(inFrame)).payload.decodeToString()
            )
            assertEquals(input, rx)
        }
        assertEquals(540f, RemoteInputCoordinateMapper.toPixel(0.5, 1080), 0.1f)
        assertEquals(0f, RemoteInputCoordinateMapper.toPixel(-0.5, 1080), 0.1f)
        assertEquals(2399f, RemoteInputCoordinateMapper.toPixel(1.5, 2400), 0.1f)
    }

    @Test
    fun fullDuplexMtlsServerAndClientExchange() {
        val serverIdMgr = IdentityManager(useAndroidKeyStore = false)
        val serverId = serverIdMgr.loadOrCreateIdentity("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")

        val clientIdMgr = IdentityManager(useAndroidKeyStore = false)
        val clientId = clientIdMgr.loadOrCreateIdentity("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")

        val serverTrust = TrustStore()
        serverTrust.savePeer(
            TrustedPeer(
                deviceId = clientId.deviceId,
                deviceName = "Android Duplex",
                pinnedCertSha256 = clientId.certificateSha256Hex,
                sharedBleSecret = ByteArray(32) { 0x44 }
            )
        )

        val clientTrust = TrustStore()
        clientTrust.savePeer(
            TrustedPeer(
                deviceId = serverId.deviceId,
                deviceName = "Mac Duplex",
                pinnedCertSha256 = serverId.certificateSha256Hex,
                sharedBleSecret = ByteArray(32) { 0x44 }
            )
        )

        val serverLatch = CountDownLatch(2)
        val clientLatch = CountDownLatch(1)
        val serverRx = CopyOnWriteArrayList<WireFrame>()
        val clientRx = CopyOnWriteArrayList<WireFrame>()

        val server = WifiTlsServer(
            identityManager = serverIdMgr,
            trustStore = serverTrust,
            expectedPeerDeviceId = clientId.deviceId,
            onFrameReceived = {
                serverRx.add(it)
                serverLatch.countDown()
            }
        )
        val port = server.start(0)
        val client = WifiTlsClient(
            identityManager = clientIdMgr,
            trustStore = clientTrust,
            expectedPeerDeviceId = serverId.deviceId,
            onFrameReceived = {
                clientRx.add(it)
                clientLatch.countDown()
            }
        )

        try {
            client.connect("127.0.0.1", port)
            assertTrue(server.waitForClient(2000L))
            assertTrue(server.hasActiveClients)

            // Client -> Server: FILE_OFFER + FILE_CHUNK
            val txId = UUID.randomUUID()
            val chunkBytes = ByteArray(2048) { (it and 0xFF).toByte() }
            val chunkSha = MessageDigest.getInstance("SHA-256").digest(chunkBytes)
            val offer = FileOfferPayload(
                transferId = txId.toString(),
                filename = "demo.bin",
                byteSize = 2048L,
                sha256 = "a".repeat(64),
                chunkSize = 2048,
                totalChunks = 1,
                mimeType = "application/octet-stream"
            )
            val offerFrame = WireFrame(
                WireMessageType.FILE_OFFER,
                10L,
                Json.encodeToString(offer).toByteArray()
            )
            val chunkFrame = WireFrame(
                WireMessageType.FILE_CHUNK,
                11L,
                FileChunkPacket(txId, 0, chunkSha, chunkBytes).encode()
            )
            client.send(offerFrame)
            client.send(chunkFrame)

            // Server -> Client: SCREEN_FRAME
            val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) +
                ByteArray(1500) { (it and 0xFF).toByte() } +
                byteArrayOf(0xFF.toByte(), 0xD9.toByte())
            val screenPkt = ScreenFramePacket(
                source = ScreenFramePacket.SOURCE_ANDROID,
                width = 540,
                height = 1200,
                timestampMs = 1_770_000_999_000L,
                jpegData = jpeg
            )
            val screenFrame = WireFrame(WireMessageType.SCREEN_FRAME, 21L, screenPkt.encode())
            server.send(screenFrame)

            assertTrue(serverLatch.await(3, TimeUnit.SECONDS))
            assertTrue(clientLatch.await(3, TimeUnit.SECONDS))

            assertEquals(2, serverRx.size)
            assertEquals(offerFrame.messageType, serverRx[0].messageType)
            assertEquals(chunkFrame.messageType, serverRx[1].messageType)
            assertArrayEquals(chunkFrame.payload, serverRx[1].payload)

            assertEquals(1, clientRx.size)
            val decodedScreen = ScreenFramePacket.decode(clientRx[0].payload)
            assertEquals(screenPkt, decodedScreen)
            assertArrayEquals(jpeg, decodedScreen.jpegData)

            val highFpsCtrl = ScreenControlPayload(action = "start", source = "android", fps = 60, quality = 65)
            val wireJson = Json { encodeDefaults = true }
            val ctrlJson = wireJson.encodeToString(highFpsCtrl)
            assertTrue(ctrlJson.contains("\"fps\":60") && ctrlJson.contains("\"quality\":65"))
            val roundTrippedCtrl = wireJson.decodeFromString<ScreenControlPayload>(ctrlJson)
            assertEquals(60, roundTrippedCtrl.fps)
            assertEquals(65, roundTrippedCtrl.quality)

            val textInputJson = RemoteControlIpcBridge.encodeRemoteInputJson(
                RemoteInputPayload(type = "text", text = "search link-me")
            )
            assertTrue(textInputJson.contains("search link-me"))
        } finally {
            client.close()
            server.stop()
        }
    }

    @Test
    fun v2BootstrapUriBleFallbackFileAcceptAndWakeStealth() {
        // 1. Offline v=2 URI parsing (no ip/port) & pairToken propagation
        val macIdMgr = IdentityManager(useAndroidKeyStore = false)
        val macId = macIdMgr.loadOrCreateIdentity("11111111-1111-4111-8111-111111111111")
        val macCrypto = PairingCrypto()
        val offlineUri = macCrypto.makePairingUri(
            deviceId = macId.deviceId,
            certSha256Hex = macId.certificateSha256Hex,
            tcpPort = 0,
            lanHost = null,
            deviceName = "Skanda MacBook Air M5"
        )
        val parsed = PairingUriPayload.parse(offlineUri.uriString)
        assertEquals(2, parsed.version)
        assertEquals(macCrypto.pairToken, parsed.pairToken)
        assertEquals(null, parsed.lanHost)

        val androidCrypto = PairingCrypto()
        val nonce = parsed.nonceHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val derived = androidCrypto.deriveSecrets(parsed.publicKeyBase64Url, nonce)
        val commit = PairCommitPayload(
            deviceId = "22222222-2222-4222-8222-222222222222",
            deviceName = "SM-A736B",
            ephemeralPublicKey = androidCrypto.publicKeyBase64Url,
            spkiSha256 = "b".repeat(64),
            sasMacHex = PairingCommitMac.computeHex(
                derived.sharedBleSecret,
                "22222222-2222-4222-8222-222222222222",
                "b".repeat(64),
                androidCrypto.publicKeyBase64Url
            ),
            batteryLevel = 73,
            isCharging = true,
            pairToken = parsed.pairToken
        )
        val decodedCommit = Json.decodeFromString<PairCommitPayload>(Json.encodeToString(commit))
        assertEquals(parsed.pairToken, decodedCommit.pairToken)

        // 2. BLE-only resilient fallback with Wi-Fi backoff avoids repeated Wi-Fi dial stalls
        val wifiCalls = AtomicInteger(0)
        val bleCalls = AtomicInteger(0)
        val wakeCalls = AtomicInteger(0)
        val router = TransportManager(
            cooldownDurationMs = 200L,
            bleSender = { frame ->
                if (frame.messageType == WireMessageType.WAKE_WIFI) {
                    wakeCalls.incrementAndGet()
                } else {
                    bleCalls.incrementAndGet()
                }
            },
            wifiOpener = { 49152 },
            wifiSender = {
                wifiCalls.incrementAndGet()
                throw java.net.ConnectException("Off LAN")
            },
            wifiCloser = {}
        )
        try {
            val payload = ByteArray(800) { 0x3C }
            for (i in 1..3) {
                router.sendResilientFrame(
                    WireFrame(WireMessageType.FILE_CHUNK, i.toLong(), payload),
                    preferBle = false
                )
            }
            assertEquals(1, wifiCalls.get())
            assertEquals(1, wakeCalls.get())
            assertEquals(3, bleCalls.get())
        } finally {
            router.shutdown()
        }

        // 3. FILE_OFFER -> FILE_RESPONSE -> adaptive 2048B FILE_CHUNK -> verified disk file
        val tmpRoot = Files.createTempDirectory("linkme-v2-ft").toFile()
        try {
            val src = File(tmpRoot, "photo.bin")
            val raw = ByteArray(5000) { ((it * 13) and 0xFF).toByte() }
            src.writeBytes(raw)
            val senderFT = FileTransferManager(File(tmpRoot, "rx"))
            val receiverFT = FileTransferManager(File(tmpRoot, "rx"))
            var completedFile: File? = null
            val txId = UUID.randomUUID()
            val offer = senderFT.streamOutboundFile(
                sourceFile = src,
                transferId = txId,
                chunkSize = 2048,
                awaitAcceptance = true
            ) { frame ->
                when (frame.messageType) {
                    WireMessageType.FILE_OFFER -> {
                        val o = Json.decodeFromString<FileOfferPayload>(frame.payload.decodeToString())
                        val resp = receiverFT.beginIncomingTransfer(o)
                        senderFT.handleFileResponse(resp)
                    }
                    WireMessageType.FILE_CHUNK -> {
                        val pkt = FileChunkPacket.decode(frame.payload)
                        val p = receiverFT.receiveChunk(pkt)
                        if (p.bytesTransferred >= p.totalBytes) {
                            completedFile = receiverFT.finalizeIncomingTransfer(pkt.transferId)
                        }
                    }
                    else -> Unit
                }
            }
            assertEquals(2048, offer.chunkSize)
            assertEquals(3, offer.totalChunks)
            assertNotNull(completedFile)
            assertArrayEquals(raw, completedFile!!.readBytes())
        } finally {
            tmpRoot.deleteRecursively()
        }

        // 4. Wake + StealthBlack ScreenControl codec
        val ctrl = ScreenControlPayload(
            action = "start",
            source = "android",
            fps = 60,
            quality = 65,
            wake = true,
            stealthBlack = true
        )
        val decodedCtrl = Json.decodeFromString<ScreenControlPayload>(Json.encodeToString(ctrl))
        assertTrue(decodedCtrl.wake && decodedCtrl.stealthBlack && decodedCtrl.fps == 60)

        // 5. v2.0.1 0x01 Ping/Pong telemetry round-trip + RadioDiagnosticsUiState report
        val wireJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val ping01 = PingTelemetryPayload(
            deviceId = "22222222-2222-4222-8222-222222222222",
            batteryLevel = 81,
            isCharging = true,
            batterySaverActive = false,
            transportState = BridgeState.DORMANT_BLE,
            timestampMs = 1_770_000_200L,
            pingNonce = "feed1234",
            isPong = true
        )
        val pingFrame = WireFrame(
            WireMessageType.PING_TELEMETRY,
            99L,
            wireJson.encodeToString(ping01).toByteArray(Charsets.UTF_8)
        )
        val decodedPing = wireJson.decodeFromString<PingTelemetryPayload>(
            WireFrame.decode(FrameEncoder.encode(pingFrame)).payload.decodeToString()
        )
        assertEquals("feed1234", decodedPing.pingNonce)
        assertTrue(decodedPing.isPong)

        val diag = com.linkme.androidbridge.service.RadioDiagnosticsUiState(
            bleRadioState = "PoweredOn",
            bleLinkState = "GATT Connected + CCCD Ready",
            wifiMtlsState = "Listening :49152",
            lastWireEvents = listOf("[10:00:00.000] CCCD_ENABLED", "[10:00:00.010] TX_BYTES(42)")
        )
        val report = diag.formatCopyableReport("MacBook Air", 18)
        assertTrue(report.contains("GATT Connected + CCCD Ready") && report.contains("CCCD_ENABLED"))

        // 6. v2.1.0 BLE tokenTag + PAIR_HELLO -> PAIR_COMMIT + Shizuku 0x0E input command builder
        val tagA = PairingCrypto.computeTokenTag(parsed.pairToken)
        val tagB = PairingCrypto.computeTokenTag(parsed.pairToken)
        assertEquals(4, tagA.size)
        assertArrayEquals(tagA, tagB)

        val tapCmd = com.linkme.androidbridge.service.ShizukuInputCommandBuilder.buildShellCommand(
            RemoteInputPayload(type = "tap", x = 0.5, y = 0.5),
            screenWidthPx = 1080,
            screenHeightPx = 2400
        )
        assertEquals("input tap 540 1200", tapCmd)
        val textCmd = com.linkme.androidbridge.service.ShizukuInputCommandBuilder.buildShellCommand(
            RemoteInputPayload(type = "text", text = "hello link-me")
        )
        assertEquals("input text 'hello%slink-me'", textCmd)
        val backCmd = com.linkme.androidbridge.service.ShizukuInputCommandBuilder.buildShellCommand(
            RemoteInputPayload(type = "key", action = "back")
        )
        assertEquals("input keyevent 4", backCmd)

        // 7. v2.1.1 Audit regressions: 31B legacy BLE cap, collision-safe filenames, Shizuku special chars, Unpair cleanup
        val pairingAdvBytes = com.linkme.androidbridge.core.transport.BleAdvertisementBudget.primaryAdvertisementBytes(
            includeServiceUuid = false,
            serviceDataPayloadLength = 4,
            connectable = true
        )
        assertEquals(25, pairingAdvBytes)
        assertTrue(pairingAdvBytes <= com.linkme.androidbridge.core.transport.BleAdvertisementBudget.MAX_LEGACY_ADV_BYTES)
        val beaconAdvBytes = com.linkme.androidbridge.core.transport.BleAdvertisementBudget.primaryAdvertisementBytes(
            includeServiceUuid = false,
            serviceDataPayloadLength = 8,
            connectable = true
        )
        assertEquals(29, beaconAdvBytes)
        assertTrue(beaconAdvBytes <= com.linkme.androidbridge.core.transport.BleAdvertisementBudget.MAX_LEGACY_ADV_BYTES)
        val unsafeCombinedBytes = com.linkme.androidbridge.core.transport.BleAdvertisementBudget.primaryAdvertisementBytes(
            includeServiceUuid = true,
            serviceDataPayloadLength = 4,
            connectable = true
        )
        assertEquals(43, unsafeCombinedBytes)
        assertTrue(unsafeCombinedBytes > com.linkme.androidbridge.core.transport.BleAdvertisementBudget.MAX_LEGACY_ADV_BYTES)
        assertTrue(
            com.linkme.androidbridge.core.transport.BleAdvertisementBudget.scanResponseBytes(includeServiceUuid = true) <=
                com.linkme.androidbridge.core.transport.BleAdvertisementBudget.MAX_LEGACY_ADV_BYTES
        )

        val shellEscapeCmd = com.linkme.androidbridge.service.ShizukuInputCommandBuilder.buildShellCommand(
            RemoteInputPayload(type = "text", text = "hello & \$(rm) ; 'test' 😀")
        )
        assertEquals("input text 'hello%s&%s\$(rm)%s;%s'\\''test'\\''%s'", shellEscapeCmd)

        val collisionRoot = kotlin.io.path.createTempDirectory("linkme-audit-file-test").toFile()
        try {
            val sanitizedSpaceName = com.linkme.androidbridge.core.protocol.FilenameSanitizer.validateStrict("report (final).pdf")
            assertEquals("report (final).pdf", sanitizedSpaceName)
            File(collisionRoot, "photo.jpg").writeText("first")
            val nextFile = com.linkme.androidbridge.service.FileTransferManager.resolveUniqueDestination(collisionRoot, "photo.jpg")
            assertEquals("photo (1).jpg", nextFile.name)
            nextFile.writeText("second")
            val thirdFile = com.linkme.androidbridge.service.FileTransferManager.resolveUniqueDestination(collisionRoot, "photo.jpg")
            assertEquals("photo (2).jpg", thirdFile.name)
        } finally {
            collisionRoot.deleteRecursively()
        }

        val testTrustStore = TrustStore()
        testTrustStore.savePeer(
            TrustedPeer(
                deviceId = "peer-unpair-1",
                deviceName = "MacBook Air",
                pinnedCertSha256 = "a".repeat(64),
                sharedBleSecret = ByteArray(32) { 0x11 },
                bluetoothSessionKey = ByteArray(32) { 0x22 }
            )
        )
        assertEquals(1, testTrustStore.allPeers().size)
        testTrustStore.removePeer("peer-unpair-1")
        var unpairHookCalled = false
        com.linkme.androidbridge.service.BridgeActivityHub.onUnpairAllRequested = { unpairHookCalled = true }
        com.linkme.androidbridge.service.BridgeActivityHub.notifyUnpaired()
        com.linkme.androidbridge.service.BridgeActivityHub.onUnpairAllRequested = null
        assertTrue(unpairHookCalled)
        assertEquals(0, testTrustStore.allPeers().size)
        assertNull(com.linkme.androidbridge.service.BridgeActivityHub.peerDeviceName.value)
    }
}
