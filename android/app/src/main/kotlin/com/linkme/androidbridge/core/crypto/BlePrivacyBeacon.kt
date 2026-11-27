package com.linkme.androidbridge.core.crypto

import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 8-byte rotating BLE privacy beacon:
 * HMAC-SHA256(key: sharedBleSecret, data: uint64_be(floor(unixTimestamp / 900)))[0..<8].
 * Verifies T, T-1, and T+1 in constant time.
 */
class BlePrivacyBeacon(
    private val sharedBleSecret: ByteArray
) {
    init {
        require(sharedBleSecret.size == 32) { "sharedBleSecret must be 32 bytes" }
    }

    fun token(epochSeconds: Long = System.currentTimeMillis() / 1000L): ByteArray {
        val bucket = bucketFor(epochSeconds)
        return computeToken(bucket)
    }

    fun verify(token: ByteArray, epochSeconds: Long = System.currentTimeMillis() / 1000L): Boolean {
        if (token.size != 8) return false
        val current = bucketFor(epochSeconds)
        val candidates = if (current > 0L) {
            longArrayOf(current, current - 1L, current + 1L)
        } else {
            longArrayOf(current, current + 1L)
        }
        return candidates.any { bucket ->
            MessageDigest.isEqual(token, computeToken(bucket))
        }
    }

    private fun bucketFor(epochSeconds: Long): Long = maxOf(0L, epochSeconds) / ROTATION_WINDOW_SECONDS

    private fun computeToken(bucket: Long): ByteArray {
        val input = ByteBuffer.allocate(8).putLong(bucket).array()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(sharedBleSecret, "HmacSHA256"))
        return mac.doFinal(input).copyOf(8)
    }

    companion object {
        const val ROTATION_WINDOW_SECONDS = 900L
    }
}
