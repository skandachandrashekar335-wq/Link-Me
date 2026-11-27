package com.linkme.androidbridge

import com.linkme.androidbridge.core.crypto.AesGcmTrustBox
import com.linkme.androidbridge.core.crypto.BlePrivacyBeacon
import com.linkme.androidbridge.core.crypto.FileTrustBackend
import com.linkme.androidbridge.core.crypto.IdentityManager
import com.linkme.androidbridge.core.crypto.PairingCrypto
import com.linkme.androidbridge.core.crypto.PairingUriPayload
import com.linkme.androidbridge.core.crypto.TrustStore
import com.linkme.androidbridge.core.crypto.TrustedPeer
import com.linkme.androidbridge.core.protocol.ClipboardSyncPayload
import com.linkme.androidbridge.core.protocol.DeviceCommandPayload
import com.linkme.androidbridge.core.protocol.FileChunkPacket
import com.linkme.androidbridge.core.protocol.FileOfferPayload
import com.linkme.androidbridge.core.protocol.FilenameSanitizer
import com.linkme.androidbridge.core.protocol.FrameDecoder
import com.linkme.androidbridge.core.protocol.FrameEncoder
import com.linkme.androidbridge.core.protocol.ProtocolError
import com.linkme.androidbridge.core.protocol.ReplayGuard
import com.linkme.androidbridge.core.protocol.UnsafeFilenameException
import com.linkme.androidbridge.core.protocol.WireFrame
import com.linkme.androidbridge.core.transport.TransportManager
import com.linkme.androidbridge.service.ClipboardSyncManager
import com.linkme.androidbridge.service.ClipboardSystemAdapter
import com.linkme.androidbridge.service.FileTransferException
import com.linkme.androidbridge.service.FileTransferManager
import com.linkme.androidbridge.service.NotificationCompanionBridge
import com.linkme.androidbridge.service.NotificationMirrorRouter
import com.linkme.androidbridge.service.ReplyActionExecutor
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.util.UUID

@kotlinx.serialization.Serializable
private data class VectorDocument(
    val validVectors: List<ValidVector>,
    val invalidVectors: List<InvalidVector>
)

@kotlinx.serialization.Serializable
private data class ValidVector(
    val id: String,
    val msgType: Int,
    val sequenceId: Long,
    val frameHex: String
)

@kotlinx.serialization.Serializable
private data class InvalidVector(
    val id: String,
    val category: String,
    val expectedError: String,
    val frameHex: String? = null,
    val sequenceStream: List<String>? = null
)

private class MockClipboardAdapter : ClipboardSystemAdapter {
    val written = mutableListOf<String>()
    override fun writeText(text: String) {
        written.add(text)
    }
}

class ProtocolAndSecurityTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun loadVectors(): VectorDocument {
        val candidates = listOf(
            File("../protocol/test_vectors.json"),
            File("../../protocol/test_vectors.json"),
            File("protocol/test_vectors.json")
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("Could not locate protocol/test_vectors.json")
        return json.decodeFromString(file.readText())
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    @Test
    fun frameEncoderAndDecoderHandleFragmentedStreams() {
        val doc = loadVectors()
        assertEquals(9, doc.validVectors.size)

        val coalesced = ByteArrayOutputStream()
        for (vector in doc.validVectors) {
            val raw = hexToBytes(vector.frameHex)
            val decoded = WireFrame.decode(raw)
            assertEquals(vector.msgType, decoded.messageType.code.toInt() and 0xFF)
            assertEquals(vector.sequenceId, decoded.sequenceId)
            assertArrayEquals(raw, FrameEncoder.encode(decoded))
            coalesced.write(raw)
        }

        val streamDecoder = FrameDecoder()
        val decodedFrames = mutableListOf<WireFrame>()
        for (b in coalesced.toByteArray()) {
            streamDecoder.append(byteArrayOf(b))
            streamDecoder.nextFrame()?.let { decodedFrames.add(it) }
        }
        assertEquals(9, decodedFrames.size)
        assertEquals(0, streamDecoder.bufferedByteCount)
    }

    @Test
    fun invalidVectorsFailClosed() {
        val doc = loadVectors()
        assertEquals(13, doc.invalidVectors.size)

        for (vector in doc.invalidVectors) {
            when (vector.category) {
                "framing" -> {
                    var failed = false
                    try {
                        WireFrame.decode(hexToBytes(vector.frameHex!!))
                    } catch (_: ProtocolError) {
                        failed = true
                    }
                    assertTrue("Expected ProtocolError for ${vector.id}", failed)
                }
                "replay" -> {
                    val guard = ReplayGuard()
                    var rejected = false
                    for (hex in vector.sequenceStream!!) {
                        val frame = WireFrame.decode(hexToBytes(hex))
                        try {
                            guard.validateAndAccept(frame.sequenceId)
                        } catch (_: ProtocolError.ReplayDetected) {
                            rejected = true
                        }
                    }
                    assertTrue("Expected ReplayDetected for ${vector.id}", rejected)
                }
                "path_traversal" -> {
                    val frame = WireFrame.decode(hexToBytes(vector.frameHex!!))
                    val offer = json.decodeFromString<FileOfferPayload>(frame.payload.decodeToString())
                    var rejected = false
                    try {
                        offer.validateSafeFilename()
                    } catch (_: UnsafeFilenameException) {
                        rejected = true
                    }
                    assertTrue("Expected UnsafeFilenameException for ${vector.id}", rejected)
                }
                "clipboard_privacy" -> {
                    val frame = WireFrame.decode(hexToBytes(vector.frameHex!!))
                    val clip = json.decodeFromString<ClipboardSyncPayload>(frame.payload.decodeToString())
                    var rejected = false
                    try {
                        clip.validate()
                    } catch (_: IllegalArgumentException) {
                        rejected = true
                    }
                    assertTrue("Expected sensitive clipboard rejection for ${vector.id}", rejected)
                }
            }
        }
    }

    @Test
    fun replayGuardSlidingWindowBehavior() {
        val guard = ReplayGuard()
        guard.validateAndAccept(10L)
        guard.validateAndAccept(8L)
        guard.validateAndAccept(9L)

        var duplicateRejected = false
        try {
            guard.validateAndAccept(8L)
        } catch (_: ProtocolError.ReplayDetected) {
            duplicateRejected = true
        }
        assertTrue(duplicateRejected)

        guard.validateAndAccept(80L)
        var staleRejected = false
        try {
            guard.validateAndAccept(12L)
        } catch (_: ProtocolError.ReplayDetected) {
            staleRejected = true
        }
        assertTrue(staleRejected)
    }

    @Test
    fun filenameSanitizerNeutralizesAttacksAndPreservesUnicode() {
        assertEquals("authorized_keys", FilenameSanitizer.sanitize("../../.ssh/authorized_keys"))
        assertEquals("cmd.exe", FilenameSanitizer.sanitize("C:\\Windows\\System32\\cmd.exe"))
        assertEquals("evil.sh.png", FilenameSanitizer.sanitize("evil\u0000.sh.png"))
        assertEquals("bashrc", FilenameSanitizer.sanitize(".bashrc"))
        assertEquals("unnamed_file", FilenameSanitizer.sanitize("../../../.."))
        assertEquals("résumé_日本語.pdf", FilenameSanitizer.sanitize("résumé_日本語.pdf"))

        val longName = "a".repeat(240) + ".tar.gz"
        val truncated = FilenameSanitizer.sanitize(longName)
        assertTrue(truncated.toByteArray(Charsets.UTF_8).size <= 200)
        assertTrue(truncated.endsWith(".gz"))
    }

    @Test
    fun identityAndTrustStoreConstantTimePinning() {
        val identity = IdentityManager(useAndroidKeyStore = false)
            .loadOrCreateIdentity("22222222-2222-4222-8222-222222222222")
        assertTrue(identity.certificateDer.isNotEmpty())
        assertEquals(64, identity.certificateSha256Hex.length)

        val trustStore = TrustStore()
        trustStore.savePeer(
            TrustedPeer(
                deviceId = "mac-1",
                deviceName = "MacBook Pro",
                pinnedCertSha256 = identity.certificateSha256Hex,
                sharedBleSecret = ByteArray(32) { 0x55 }
            )
        )
        assertTrue(trustStore.verifyPeerCertificate(identity.certificateDer, "mac-1"))
        val tampered = identity.certificateDer.copyOf().apply { this[0] = (this[0].toInt() xor 0xFF).toByte() }
        assertFalse(trustStore.verifyPeerCertificate(tampered, "mac-1"))
    }

    @Test
    fun trustStorePersistsPeersEncryptedAcrossRestarts() {
        val tempFile = Files.createTempFile("linkme-trust", ".enc").toFile()
        try {
            val aesKey = AesGcmTrustBox.ephemeralAesKey()
            val secret = ByteArray(32) { (it + 7).toByte() }
            val certPin = "ab".repeat(32)

            val writerStore = TrustStore(
                backend = FileTrustBackend(tempFile),
                trustBox = AesGcmTrustBox { aesKey }
            )
            writerStore.savePeer(
                TrustedPeer(
                    deviceId = "mac-persisted-1",
                    deviceName = "Skanda-MacBook-Pro",
                    pinnedCertSha256 = certPin,
                    sharedBleSecret = secret
                )
            )

            // Verify zero plaintext secrets on disk
            val rawDisk = tempFile.readText()
            assertFalse(rawDisk.contains("mac-persisted-1"))
            assertFalse(rawDisk.contains(certPin))

            // Re-open TrustStore simulating app restart
            val readerStore = TrustStore(
                backend = FileTrustBackend(tempFile),
                trustBox = AesGcmTrustBox { aesKey }
            )
            val restored = readerStore.getPeer("mac-persisted-1")
            assertNotNull(restored)
            assertEquals("Skanda-MacBook-Pro", restored!!.deviceName)
            assertEquals(certPin, restored.pinnedCertSha256)
            assertArrayEquals(secret, restored.sharedBleSecret)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun pairingCryptoProducesIdenticalSasAndSharedBleSecret() {
        val mac = PairingCrypto()
        val android = PairingCrypto()
        val uri = mac.makePairingUri("mac-1", "a".repeat(64), 49152)
        val parsed = PairingUriPayload.parse(uri.uriString)
        assertEquals(49152, parsed.tcpPort)

        val macSecrets = mac.deriveSecrets(android.publicKeyBase64Url, mac.nonce)
        val androidSecrets = android.deriveSecrets(parsed.publicKeyBase64Url, mac.nonce)

        assertEquals(6, macSecrets.sasCode.length)
        assertEquals(macSecrets.sasCode, androidSecrets.sasCode)
        assertArrayEquals(macSecrets.sharedBleSecret, androidSecrets.sharedBleSecret)

        val tamperedNonce = mac.nonce.copyOf().apply { this[0] = (this[0].toInt() xor 0x01).toByte() }
        val tampered = android.deriveSecrets(parsed.publicKeyBase64Url, tamperedNonce)
        assertNotEquals(macSecrets.sharedBleSecret.toList(), tampered.sharedBleSecret.toList())
    }

    @Test
    fun blePrivacyBeaconSlidingBuckets() {
        val secret = ByteArray(32) { it.toByte() }
        val wrongSecret = ByteArray(32) { (255 - it).toByte() }
        val beacon = BlePrivacyBeacon(secret)
        val wrongBeacon = BlePrivacyBeacon(wrongSecret)
        val ts = 1_764_234_900L

        val token = beacon.token(ts)
        assertEquals(8, token.size)
        assertTrue(beacon.verify(token, ts))
        assertTrue(beacon.verify(token, ts + 900L))
        assertTrue(beacon.verify(token, ts - 900L))
        assertFalse(beacon.verify(token, ts + 1800L))
        assertFalse(wrongBeacon.verify(token, ts))
    }

    @Test
    fun clipboardLoopDeduplicationAndSensitivityRejection() {
        val adapter = MockClipboardAdapter()
        val transport = TransportManager()
        val manager = ClipboardSyncManager(
            deviceId = "22222222-2222-4222-8222-222222222222",
            adapter = adapter,
            transport = transport
        )

        val remoteText = "Remote Mac clipboard"
        val remoteHash = ClipboardSyncManager.sha256Hex(remoteText.toByteArray())
        manager.applyRemoteClipboard(
            ClipboardSyncPayload(
                originDeviceId = "11111111-1111-4111-8111-111111111111",
                clipboardEpoch = 1L,
                contentSha256 = remoteHash,
                mimeType = "text/plain",
                text = remoteText,
                isSensitive = false,
                timestampMs = 1_764_234_900_000L
            )
        )
        assertEquals(remoteHash, manager.lastSyncedContentHash)
        assertNull(manager.processLocalCopy(remoteText, isSensitive = false))
        assertNull(manager.processLocalCopy("secret-password", isSensitive = true))
        assertNotNull(manager.processLocalCopy("New Android copy", isSensitive = false))
    }

    @Test
    fun chunkedFileTransferStreamAndCorruptionRejection() {
        val tempRoot = Files.createTempDirectory("linkme-test").toFile()
        try {
            val sourceFile = File(tempRoot, "sample_2_5mb.bin")
            val totalBytes = 2_621_440 // 2.5 MiB = 40 * 64 KB
            val payload = ByteArray(totalBytes) { i -> ((i * 31) xor (i ushr 8)).toByte() }
            sourceFile.writeBytes(payload)

            val downloadDir = File(tempRoot, "AndroidBridge")
            val sender = FileTransferManager(downloadDir)
            val receiver = FileTransferManager(downloadDir)
            val transferId = UUID.randomUUID()
            val frames = mutableListOf<WireFrame>()

            val offer = sender.streamOutboundFile(sourceFile, transferId) { frames.add(it) }
            assertEquals(65_536, offer.chunkSize)
            assertEquals(40, offer.totalChunks)
            assertEquals(41, frames.size)

            val response = receiver.beginIncomingTransfer(offer)
            assertTrue(response.accepted)

            for (frame in frames.drop(1)) {
                val packet = FileChunkPacket.decode(frame.payload)
                receiver.receiveChunk(packet)
            }
            val finalFile = receiver.finalizeIncomingTransfer(transferId)
            assertEquals(offer.sha256, FileTransferManager.streamingSha256Hex(FileInputStream(finalFile)))

            // Corrupt first chunk and assert rejection
            val badId = UUID.randomUUID()
            receiver.beginIncomingTransfer(offer.copy(transferId = badId.toString().lowercase(), filename = "bad.bin"))
            val orig = FileChunkPacket.decode(frames[1].payload)
            val badBytes = orig.data.copyOf().apply { this[0] = (this[0].toInt() xor 0xFF).toByte() }
            val badPacket = FileChunkPacket(badId, 0, orig.chunkSha256, badBytes)

            var rejected = false
            try {
                receiver.receiveChunk(badPacket)
            } catch (_: FileTransferException) {
                rejected = true
            }
            assertTrue(rejected)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun notificationPayloadAndRemoteInputReplyRouting() {
        val router = NotificationMirrorRouter(ownPackageName = "com.linkme.androidbridge")
        val repliesReceived = mutableListOf<String>()
        val executor = object : ReplyActionExecutor {
            override fun executeReply(replyText: String): Boolean {
                repliesReceived.add(replyText)
                return true
            }
        }

        val event = router.onPosted(
            sbnKey = "0|com.chat|1|null|100",
            packageName = "com.google.android.apps.messaging",
            appName = "Messages",
            title = "Alex",
            body = "Ready?",
            timestampMs = 1_764_234_900_000L,
            replyExecutor = executor
        )
        assertNotNull(event)
        assertTrue(event!!.canReply)

        val command = DeviceCommandPayload(
            command = "replyToNotification",
            sbnKey = event.sbnKey,
            replyText = "Yes, on my way!",
            timestampMs = 1_764_234_901_000L
        )
        assertTrue(router.handleDeviceCommand(command))
        assertEquals(listOf("Yes, on my way!"), repliesReceived)
    }

    @Test
    fun notificationCompanionBridgeIpcRoundTripAndBounds() {
        val event = NotificationEventPayload(
            action = "POST",
            sbnKey = "0|com.signal|2|null|1000",
            appName = "Signal",
            packageName = "org.thoughtcrime.securesms",
            title = "Sam",
            body = "Encrypted hello",
            timestampMs = 1_764_234_950_000L,
            canReply = true
        )
        val rawEvent = NotificationCompanionBridge.encodeNotificationEventJson(event)
        val decodedEvent = NotificationCompanionBridge.decodeNotificationEventJson(rawEvent)
        assertEquals(event, decodedEvent)

        val cmd = DeviceCommandPayload(
            command = "replyToNotification",
            sbnKey = event.sbnKey,
            replyText = "Received",
            timestampMs = 1_764_234_951_000L
        )
        val rawCmd = NotificationCompanionBridge.encodeDeviceCommandJson(cmd)
        val decodedCmd = NotificationCompanionBridge.decodeDeviceCommandJson(rawCmd)
        assertEquals(cmd, decodedCmd)

        assertEquals(null, NotificationCompanionBridge.decodeNotificationEventJson("invalid"))
        assertEquals(null, NotificationCompanionBridge.decodeDeviceCommandJson("x".repeat(300 * 1024)))
    }
}
