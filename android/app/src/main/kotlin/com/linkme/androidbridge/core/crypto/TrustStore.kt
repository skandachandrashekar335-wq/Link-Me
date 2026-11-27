package com.linkme.androidbridge.core.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class TrustedPeer(
    val deviceId: String,
    val deviceName: String,
    val pinnedCertSha256: String,
    val sharedBleSecret: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrustedPeer) return false
        return deviceId == other.deviceId &&
            deviceName == other.deviceName &&
            pinnedCertSha256 == other.pinnedCertSha256 &&
            sharedBleSecret.contentEquals(other.sharedBleSecret)
    }

    override fun hashCode(): Int {
        var result = deviceId.hashCode()
        result = 31 * result + deviceName.hashCode()
        result = 31 * result + pinnedCertSha256.hashCode()
        result = 31 * result + sharedBleSecret.contentHashCode()
        return result
    }
}

@Serializable
private data class PersistedPeerRecord(
    val deviceId: String,
    val deviceName: String,
    val pinnedCertSha256: String,
    val sharedBleSecretBase64: String
)

interface TrustPersistenceBackend {
    fun readBlob(): String?
    fun writeBlob(blob: String)
}

class InMemoryTrustBackend : TrustPersistenceBackend {
    private var blob: String? = null
    override fun readBlob(): String? = blob
    override fun writeBlob(blob: String) {
        this.blob = blob
    }
}

class FileTrustBackend(private val file: File) : TrustPersistenceBackend {
    override fun readBlob(): String? = if (file.exists()) file.readText() else null
    override fun writeBlob(blob: String) {
        file.parentFile?.mkdirs()
        file.writeText(blob)
    }
}

class SharedPrefsTrustBackend(context: Context) : TrustPersistenceBackend {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    override fun readBlob(): String? = prefs.getString(KEY_CIPHERTEXT, null)
    override fun writeBlob(blob: String) {
        prefs.edit().putString(KEY_CIPHERTEXT, blob).apply()
    }

    companion object {
        private const val PREFS_NAME = "linkme_trust_store_prefs"
        private const val KEY_CIPHERTEXT = "sealed_peers_v1"
    }
}

/**
 * Seals peer trust blobs with AES/GCM/NoPadding backed by AndroidKeyStore (or an injected SecretKey for JVM tests).
 */
class AesGcmTrustBox(
    private val keyProvider: () -> SecretKey
) {
    fun encrypt(plaintext: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val key = keyProvider()
        // AndroidKeyStore generates the 12-byte IV automatically unless provided; generate explicitly for portability
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        val combined = ByteArray(1 + iv.size + ciphertext.size)
        combined[0] = iv.size.toByte()
        System.arraycopy(iv, 0, combined, 1, iv.size)
        System.arraycopy(ciphertext, 0, combined, 1 + iv.size, ciphertext.size)
        return Base64.getEncoder().encodeToString(combined)
    }

    fun decrypt(encoded: String): ByteArray {
        val combined = Base64.getDecoder().decode(encoded)
        require(combined.size > 14) { "Corrupt encrypted trust blob" }
        val ivLen = combined[0].toInt() and 0xFF
        require(ivLen in 12..16 && combined.size > 1 + ivLen) { "Invalid GCM IV length" }
        val iv = combined.copyOfRange(1, 1 + ivLen)
        val ciphertext = combined.copyOfRange(1 + ivLen, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    companion object {
        private const val KEY_ALIAS = "linkme_trust_aes_gcm_v1"

        fun androidKeyStoreKey(): SecretKey {
            val ks = KeyStore.getInstance(IdentityManager.ANDROID_KEYSTORE).apply { load(null) }
            if (ks.containsAlias(KEY_ALIAS)) {
                return ks.getKey(KEY_ALIAS, null) as SecretKey
            }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, IdentityManager.ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            generator.init(spec)
            return generator.generateKey()
        }

        fun ephemeralAesKey(): SecretKey {
            val gen = KeyGenerator.getInstance("AES")
            gen.init(256, SecureRandom())
            return gen.generateKey()
        }
    }
}

/**
 * Stores paired peer certificate pins and 32-byte BLE secrets encrypted at rest via AndroidKeyStore AES-GCM,
 * and performs constant-time SHA-256 certificate verification for mTLS 1.3.
 */
class TrustStore(
    private val backend: TrustPersistenceBackend = InMemoryTrustBackend(),
    private val trustBox: AesGcmTrustBox? = null
) {
    private val peers = ConcurrentHashMap<String, TrustedPeer>()
    private val json = Json { ignoreUnknownKeys = true }

    init {
        loadFromBackend()
    }

    @Synchronized
    fun savePeer(peer: TrustedPeer) {
        require(peer.sharedBleSecret.size == 32) { "sharedBleSecret must be 32 bytes" }
        require(peer.pinnedCertSha256.length == 64) { "pinnedCertSha256 must be 64 hex chars" }
        peers[peer.deviceId] = peer.copy(pinnedCertSha256 = peer.pinnedCertSha256.lowercase())
        persistToBackend()
    }

    fun getPeer(deviceId: String): TrustedPeer? = peers[deviceId]

    fun allPeers(): List<TrustedPeer> = peers.values.toList()

    @Synchronized
    fun removePeer(deviceId: String) {
        peers.remove(deviceId)
        persistToBackend()
    }

    fun verifyPeerCertificate(derBytes: ByteArray, expectedDeviceId: String): Boolean {
        val peer = peers[expectedDeviceId] ?: return false
        val expectedDigest = hexToBytes(peer.pinnedCertSha256) ?: return false
        val actualDigest = MessageDigest.getInstance("SHA-256").digest(derBytes)
        return MessageDigest.isEqual(actualDigest, expectedDigest)
    }

    private fun persistToBackend() {
        val records = peers.values.map {
            PersistedPeerRecord(
                deviceId = it.deviceId,
                deviceName = it.deviceName,
                pinnedCertSha256 = it.pinnedCertSha256,
                sharedBleSecretBase64 = Base64.getEncoder().encodeToString(it.sharedBleSecret)
            )
        }
        val payload = json.encodeToString(records).encodeToByteArray()
        val serialized = trustBox?.encrypt(payload) ?: Base64.getEncoder().encodeToString(payload)
        backend.writeBlob(serialized)
    }

    private fun loadFromBackend() {
        val blob = backend.readBlob() ?: return
        val plaintext = runCatching {
            trustBox?.decrypt(blob) ?: Base64.getDecoder().decode(blob)
        }.getOrNull() ?: return
        val records = runCatching {
            json.decodeFromString<List<PersistedPeerRecord>>(plaintext.decodeToString())
        }.getOrNull() ?: return
        for (record in records) {
            val secret = Base64.getDecoder().decode(record.sharedBleSecretBase64)
            if (secret.size == 32 && record.pinnedCertSha256.length == 64) {
                peers[record.deviceId] = TrustedPeer(
                    deviceId = record.deviceId,
                    deviceName = record.deviceName,
                    pinnedCertSha256 = record.pinnedCertSha256.lowercase(),
                    sharedBleSecret = secret
                )
            }
        }
    }

    companion object {
        fun forContext(context: Context): TrustStore = TrustStore(
            backend = SharedPrefsTrustBackend(context.applicationContext),
            trustBox = AesGcmTrustBox { AesGcmTrustBox.androidKeyStoreKey() }
        )

        fun hexToBytes(hex: String): ByteArray? {
            if (hex.length % 2 != 0) return null
            return ByteArray(hex.length / 2) { i ->
                hex.substring(i * 2, i * 2 + 2).toIntOrNull(16)?.toByte() ?: return null
            }
        }
    }
}
