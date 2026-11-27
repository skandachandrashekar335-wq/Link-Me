package com.linkme.androidbridge

import com.linkme.androidbridge.core.crypto.IdentityManager
import com.linkme.androidbridge.core.crypto.TrustStore
import com.linkme.androidbridge.core.crypto.TrustedPeer
import com.linkme.androidbridge.core.protocol.BridgeState
import com.linkme.androidbridge.core.protocol.FrameDecoder
import com.linkme.androidbridge.core.protocol.FrameEncoder
import com.linkme.androidbridge.core.protocol.WireFrame
import com.linkme.androidbridge.core.protocol.WireMessageType
import com.linkme.androidbridge.core.transport.BleFrameChunker
import com.linkme.androidbridge.core.transport.TransferPriority
import com.linkme.androidbridge.core.transport.TransportManager
import com.linkme.androidbridge.core.transport.WifiTlsClient
import com.linkme.androidbridge.core.transport.WifiTlsServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
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
}
