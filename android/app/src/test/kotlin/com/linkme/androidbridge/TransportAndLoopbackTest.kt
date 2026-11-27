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

        // Screen Mirroring 0x0D SCREEN_CONTROL + 0x0C SCREEN_FRAME round-trip over ChaCha20
        val androidCipher = BluetoothSessionCipher(androidDerived.sessionKey, senderTag = "AND0")
        val macCipher = BluetoothSessionCipher(macDerived.sessionKey, senderTag = "MAC0")

        val ctrl = ScreenControlPayload(action = "start", source = "macos", fps = 15, quality = 60)
        val ctrlFrame = WireFrame(WireMessageType.SCREEN_CONTROL, 20L, Json.encodeToString(ctrl).toByteArray())
        val decodedCtrl = Json.decodeFromString<ScreenControlPayload>(
            macCipher.openFrame(androidCipher.seal(ctrlFrame)).payload.decodeToString()
        )
        assertEquals(ctrl, decodedCtrl)

        val jpegPayload = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) +
            ByteArray(2048) { (it and 0xFF).toByte() } +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        val pkt = ScreenFramePacket(width = 1280, height = 720, timestampMs = 1_770_000_555_000L, jpegData = jpegPayload)
        val frame = WireFrame(WireMessageType.SCREEN_FRAME, 21L, pkt.encode())
        val decodedPkt = ScreenFramePacket.decode(androidCipher.openFrame(macCipher.seal(frame)).payload)
        assertEquals(pkt, decodedPkt)
        assertEquals(1280, decodedPkt.width)
        assertEquals(720, decodedPkt.height)
    }
}
