package com.linkme.androidbridge.core.crypto

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

data class TrustedPeer(
    val deviceId: String,
    val deviceName: String,
    val pinnedCertSha256: String,
    val sharedBleSecret: ByteArray
)

/**
 * Stores paired peer SPKI/DER fingerprints and 32-byte BLE secrets;
 * performs constant-time SHA-256 certificate verification for mTLS 1.3.
 */
class TrustStore {
    private val peers = ConcurrentHashMap<String, TrustedPeer>()

    fun savePeer(peer: TrustedPeer) {
        require(peer.sharedBleSecret.size == 32) { "sharedBleSecret must be 32 bytes" }
        require(peer.pinnedCertSha256.length == 64) { "pinnedCertSha256 must be 64 hex chars" }
        peers[peer.deviceId] = peer.copy(pinnedCertSha256 = peer.pinnedCertSha256.lowercase())
    }

    fun getPeer(deviceId: String): TrustedPeer? = peers[deviceId]

    fun allPeers(): List<TrustedPeer> = peers.values.toList()

    fun removePeer(deviceId: String) {
        peers.remove(deviceId)
    }

    fun verifyPeerCertificate(derBytes: ByteArray, expectedDeviceId: String): Boolean {
        val peer = peers[expectedDeviceId] ?: return false
        val expectedDigest = hexToBytes(peer.pinnedCertSha256) ?: return false
        val actualDigest = MessageDigest.getInstance("SHA-256").digest(derBytes)
        return MessageDigest.isEqual(actualDigest, expectedDigest)
    }

    companion object {
        fun hexToBytes(hex: String): ByteArray? {
            if (hex.length % 2 != 0) return null
            return ByteArray(hex.length / 2) { i ->
                hex.substring(i * 2, i * 2 + 2).toIntOrNull(16)?.toByte() ?: return null
            }
        }
    }
}
