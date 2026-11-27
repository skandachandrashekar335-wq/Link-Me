package com.linkme.androidbridge.core.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

data class DeviceIdentity(
    val deviceId: String,
    val certificateDer: ByteArray,
    val spkiSha256Hex: String,
    val certificateSha256Hex: String
)

/**
 * Generates and stores a P-256 identity keypair (AndroidKeyStore on device, JCA fallback in JVM tests)
 * and builds a self-signed 10-year X.509 DER certificate with SHA-256 SPKI/DER fingerprints.
 */
class IdentityManager(
    private val useAndroidKeyStore: Boolean = true
) {
    private var cachedIdentity: DeviceIdentity? = null
    private var cachedPrivateKey: PrivateKey? = null

    fun loadOrCreateIdentity(deviceId: String = UUID.randomUUID().toString().lowercase()): DeviceIdentity {
        cachedIdentity?.let { return it }

        val keyPair = generateOrLoadKeyPair(alias = LOCAL_KEY_ALIAS)
        val certDer = buildSelfSignedP256CertificateDer(
            keyPair = keyPair,
            commonName = "AndroidBridge-$deviceId",
            validityYears = 10
        )
        val spkiSha = MessageDigest.getInstance("SHA-256").digest(keyPair.public.encoded)
            .joinToString("") { "%02x".format(it) }
        val certSha = MessageDigest.getInstance("SHA-256").digest(certDer)
            .joinToString("") { "%02x".format(it) }

        val identity = DeviceIdentity(
            deviceId = deviceId,
            certificateDer = certDer,
            spkiSha256Hex = spkiSha,
            certificateSha256Hex = certSha
        )
        cachedIdentity = identity
        cachedPrivateKey = keyPair.private
        return identity
    }

    fun buildKeyStoreForTls(password: CharArray = CharArray(0)): KeyStore {
        val identity = loadOrCreateIdentity()
        val privateKey = cachedPrivateKey ?: error("Private key unavailable")
        val certFactory = CertificateFactory.getInstance("X.509")
        val x509Cert = certFactory.generateCertificate(ByteArrayInputStream(identity.certificateDer)) as X509Certificate
        return KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, password)
            setKeyEntry(LOCAL_KEY_ALIAS, privateKey, password, arrayOf(x509Cert))
        }
    }

    private fun generateOrLoadKeyPair(alias: String): KeyPair {
        if (useAndroidKeyStore) {
            try {
                val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                if (ks.containsAlias(alias)) {
                    val privateKey = ks.getKey(alias, null) as PrivateKey
                    val publicKey = ks.getCertificate(alias).publicKey
                    return KeyPair(publicKey, privateKey)
                }
                val specClass = Class.forName("android.security.keystore.KeyGenParameterSpec\$Builder")
                val builder = specClass.getConstructor(String::class.java, Int::class.javaPrimitiveType)
                    .newInstance(alias, 12) // PURPOSE_SIGN (4) | PURPOSE_VERIFY (8)
                specClass.getMethod("setDigests", Array<String>::class.java)
                    .invoke(builder, arrayOf("SHA-256") as Any)
                val spec = specClass.getMethod("build").invoke(builder)
                val gen = KeyPairGenerator.getInstance("EC", ANDROID_KEYSTORE)
                gen.initialize(spec as java.security.spec.AlgorithmParameterSpec)
                return gen.generateKeyPair()
            } catch (_: Throwable) {
                // Fallback to software JCA P-256 keypair in JVM unit tests
            }
        }
        val gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(ECGenParameterSpec("secp256r1"))
        return gen.generateKeyPair()
    }

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val LOCAL_KEY_ALIAS = "linkme_mtls_identity"

        fun buildSelfSignedP256CertificateDer(
            keyPair: KeyPair,
            commonName: String,
            validityYears: Int,
            now: Date = Date()
        ): ByteArray {
            val version = derTag(0xA0, derInteger(byteArrayOf(0x02)))
            val serialBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            serialBytes[0] = (serialBytes[0].toInt() and 0x7F).toByte()
            if (serialBytes[0] == 0.toByte()) serialBytes[0] = 0x01
            val serial = derInteger(serialBytes)

            val sigAlg = derSequence(oid(1, 2, 840, 10045, 4, 3, 2)) // ecdsa-with-SHA256
            val name = derSequence(derSet(derSequence(oid(2, 5, 4, 3) + derUtf8String(commonName))))
            val notAfter = Date(now.time + validityYears.toLong() * 365L * 86_400_000L)
            val validity = derSequence(derX509Time(now) + derX509Time(notAfter))
            val spki = keyPair.public.encoded

            val tbs = derSequence(version + serial + sigAlg + name + validity + name + spki)
            val sig = Signature.getInstance("SHA256withECDSA").run {
                initSign(keyPair.private)
                update(tbs)
                sign()
            }
            return derSequence(tbs + sigAlg + derBitString(sig))
        }

        private fun derLength(len: Int): ByteArray {
            if (len < 0x80) return byteArrayOf(len.toByte())
            val tmp = ByteArrayOutputStream()
            var v = len
            val stack = ArrayList<Byte>()
            while (v > 0) {
                stack.add((v and 0xFF).toByte())
                v = v ushr 8
            }
            tmp.write(0x80 or stack.size)
            for (i in stack.indices.reversed()) tmp.write(stack[i].toInt())
            return tmp.toByteArray()
        }

        private fun derTag(tag: Int, content: ByteArray): ByteArray =
            byteArrayOf(tag.toByte()) + derLength(content.size) + content

        private fun derSequence(content: ByteArray): ByteArray = derTag(0x30, content)
        private fun derSet(content: ByteArray): ByteArray = derTag(0x31, content)
        private fun derUtf8String(value: String): ByteArray = derTag(0x0C, value.encodeToByteArray())
        private fun derBitString(bytes: ByteArray): ByteArray = derTag(0x03, byteArrayOf(0x00) + bytes)

        private fun derInteger(raw: ByteArray): ByteArray {
            val b = BigInteger(1, raw).toByteArray()
            return derTag(0x02, b)
        }

        private fun derX509Time(date: Date): ByteArray {
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { time = date }
            val year = cal.get(Calendar.YEAR)
            return if (year < 2050) {
                val fmt = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                derTag(0x17, fmt.format(date).encodeToByteArray())
            } else {
                val fmt = SimpleDateFormat("yyyyMMddHHmmss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                derTag(0x18, fmt.format(date).encodeToByteArray())
            }
        }

        private fun oid(vararg parts: Int): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(parts[0] * 40 + parts[1])
            for (i in 2 until parts.size) {
                var value = parts[i]
                val enc = ArrayList<Int>()
                enc.add(value and 0x7F)
                value = value ushr 7
                while (value > 0) {
                    enc.add((value and 0x7F) or 0x80)
                    value = value ushr 7
                }
                for (j in enc.indices.reversed()) out.write(enc[j])
            }
            return derTag(0x06, out.toByteArray())
        }
    }
}
