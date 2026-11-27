package com.linkme.androidbridge.core.crypto

import java.math.BigInteger
import java.net.URI
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class PairingUriPayload(
    val version: Int,
    val deviceId: String,
    val publicKeyBase64Url: String,
    val certSha256Prefix: String,
    val nonceHex: String,
    val tcpPort: Int
) {
    val uriString: String
        get() = "andromac://pair?v=$version&id=$deviceId&pub=$publicKeyBase64Url&fp=$certSha256Prefix&nonce=$nonceHex&port=$tcpPort"

    companion object {
        fun parse(uriString: String): PairingUriPayload {
            val uri = URI.create(uriString)
            require(uri.scheme == "andromac" && uri.host == "pair") { "Invalid pairing URI scheme" }
            val query = uri.rawQuery.orEmpty().split("&").associate { pair ->
                val parts = pair.split("=", limit = 2)
                require(parts.size == 2) { "Malformed query parameter" }
                parts[0] to parts[1]
            }
            val version = query["v"]?.toIntOrNull() ?: error("Missing v")
            require(version == 1) { "Unsupported pairing version" }
            val deviceId = query["id"]?.takeIf { it.isNotEmpty() } ?: error("Missing id")
            val pub = query["pub"]?.takeIf { it.isNotEmpty() } ?: error("Missing pub")
            val fp = query["fp"]?.takeIf { it.isNotEmpty() } ?: error("Missing fp")
            val nonce = query["nonce"]?.takeIf { it.length == 32 } ?: error("Invalid nonce")
            val port = query["port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: error("Invalid port")
            return PairingUriPayload(version, deviceId, pub, fp, nonce, port)
        }
    }
}

data class PairingDerivedSecrets(
    val sasCode: String,
    val sharedBleSecret: ByteArray,
    val sessionKey: ByteArray
)

/**
 * Ephemeral X25519 QR pairing (5-minute TTL) + HKDF-SHA256 ("AndroMac-Pairing-v1")
 * deriving a 6-digit SAS code and 32-byte sharedBleSecret.
 */
class PairingCrypto(
    privateKeyBytes: ByteArray? = null,
    nonceBytes: ByteArray? = null,
    val createdAtMs: Long = System.currentTimeMillis()
) {
    val privateKey: ByteArray
    val publicKey: ByteArray
    val nonce: ByteArray

    init {
        val raw = privateKeyBytes?.copyOf() ?: ByteArray(32).also { SecureRandom().nextBytes(it) }
        require(raw.size == 32) { "X25519 private key must be 32 bytes" }
        clampScalar(raw)
        this.privateKey = raw
        this.publicKey = scalarMult(raw, BASE_POINT)

        val n = nonceBytes?.copyOf() ?: ByteArray(16).also { SecureRandom().nextBytes(it) }
        require(n.size == 16) { "Pairing nonce must be 16 bytes" }
        this.nonce = n
    }

    val publicKeyBase64Url: String
        get() = Base64.getUrlEncoder().withoutPadding().encodeToString(publicKey)

    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean =
        (nowMs - createdAtMs) > SESSION_TTL_MS

    fun makePairingUri(deviceId: String, certSha256Hex: String, tcpPort: Int): PairingUriPayload =
        PairingUriPayload(
            version = 1,
            deviceId = deviceId,
            publicKeyBase64Url = publicKeyBase64Url,
            certSha256Prefix = certSha256Hex.take(16),
            nonceHex = nonce.joinToString("") { "%02x".format(it) },
            tcpPort = tcpPort
        )

    fun deriveSecrets(
        peerPublicKeyString: String,
        nonce: ByteArray,
        nowMs: Long = System.currentTimeMillis()
    ): PairingDerivedSecrets {
        require(!isExpired(nowMs)) { "Pairing session expired" }
        require(nonce.size == 16) { "Pairing nonce must be 16 bytes" }

        val peerPublicKey = decodeKeyString(peerPublicKeyString)
        val sharedSecret = scalarMult(privateKey, peerPublicKey)
        require(sharedSecret.any { it != 0.toByte() }) { "Invalid X25519 shared secret" }

        val prk = hkdfExtract(salt = nonce, ikm = sharedSecret)
        val okm = hkdfExpand(prk = prk, info = HKDF_INFO, length = 68)
        val sasInt = ByteBuffer.wrap(okm, 0, 4).int.toLong() and 0xFFFFFFFFL
        val sasCode = String.format("%06d", sasInt % 1_000_000L)
        val sharedBleSecret = okm.copyOfRange(4, 36)
        val sessionKey = okm.copyOfRange(36, 68)

        return PairingDerivedSecrets(
            sasCode = sasCode,
            sharedBleSecret = sharedBleSecret,
            sessionKey = sessionKey
        )
    }

    companion object {
        const val SESSION_TTL_MS = 300_000L // 5 minutes
        private val HKDF_INFO = "AndroMac-Pairing-v1".encodeToByteArray()
        private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
        private val A24: BigInteger = BigInteger.valueOf(121665)
        private val BASE_POINT: ByteArray = ByteArray(32).apply { this[0] = 9 }

        fun decodeKeyString(encoded: String): ByteArray {
            val decoded = try {
                Base64.getUrlDecoder().decode(encoded)
            } catch (_: IllegalArgumentException) {
                Base64.getDecoder().decode(encoded)
            }
            require(decoded.size == 32) { "Peer X25519 key must be 32 bytes" }
            return decoded
        }

        private fun clampScalar(k: ByteArray) {
            k[0] = (k[0].toInt() and 248).toByte()
            k[31] = (k[31].toInt() and 127).toByte()
            k[31] = (k[31].toInt() or 64).toByte()
        }

        private fun decodeLittleEndian(bytes: ByteArray, maskTopBit: Boolean): BigInteger {
            val copy = bytes.copyOf()
            if (maskTopBit) copy[31] = (copy[31].toInt() and 0x7F).toByte()
            return BigInteger(1, copy.reversedArray())
        }

        private fun encodeLittleEndian(value: BigInteger): ByteArray {
            val bigEndian = value.mod(P).toByteArray()
            val out = ByteArray(32)
            val len = minOf(32, bigEndian.size)
            for (i in 0 until len) out[i] = bigEndian[bigEndian.size - 1 - i]
            return out
        }

        fun scalarMult(kBytes: ByteArray, uBytes: ByteArray): ByteArray {
            val k = decodeLittleEndian(kBytes, maskTopBit = false)
            val u = decodeLittleEndian(uBytes, maskTopBit = true).mod(P)

            var x1 = u
            var x2 = BigInteger.ONE
            var z2 = BigInteger.ZERO
            var x3 = u
            var z3 = BigInteger.ONE
            var swap = 0

            for (t in 254 downTo 0) {
                val kt = if (k.testBit(t)) 1 else 0
                swap = swap xor kt
                if (swap != 0) {
                    val tx = x2; x2 = x3; x3 = tx
                    val tz = z2; z2 = z3; z3 = tz
                }
                swap = kt

                val a = x2.add(z2).mod(P)
                val aa = a.multiply(a).mod(P)
                val b = x2.subtract(z2).mod(P)
                val bb = b.multiply(b).mod(P)
                val e = aa.subtract(bb).mod(P)
                val c = x3.add(z3).mod(P)
                val d = x3.subtract(z3).mod(P)
                val da = d.multiply(a).mod(P)
                val cb = c.multiply(b).mod(P)

                x3 = da.add(cb).modPow(BigInteger.TWO, P)
                z3 = x1.multiply(da.subtract(cb).modPow(BigInteger.TWO, P)).mod(P)
                x2 = aa.multiply(bb).mod(P)
                z2 = e.multiply(aa.add(A24.multiply(e)).mod(P)).mod(P)
            }

            if (swap != 0) {
                val tx = x2; x2 = x3; x3 = tx
                val tz = z2; z2 = z3; z3 = tz
            }

            val result = x2.multiply(z2.modPow(P.subtract(BigInteger.TWO), P)).mod(P)
            return encodeLittleEndian(result)
        }

        private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(data)
        }

        private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray =
            hmacSha256(if (salt.isEmpty()) ByteArray(32) else salt, ikm)

        private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
            val out = ByteArray(length)
            var t = ByteArray(0)
            var offset = 0
            var counter = 1
            while (offset < length) {
                t = hmacSha256(prk, t + info + byteArrayOf(counter.toByte()))
                val toCopy = minOf(t.size, length - offset)
                System.arraycopy(t, 0, out, offset, toCopy)
                offset += toCopy
                counter++
            }
            return out
        }
    }
}
