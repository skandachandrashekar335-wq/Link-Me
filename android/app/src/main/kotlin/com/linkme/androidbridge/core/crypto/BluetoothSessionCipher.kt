package com.linkme.androidbridge.core.crypto

import com.linkme.androidbridge.core.protocol.FrameEncoder
import com.linkme.androidbridge.core.protocol.WireFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts and authenticates binary WireFrames over Bluetooth (BLE GATT & L2CAP)
 * using ChaCha20-Poly1305 with the X25519+HKDF derived 32-byte session key.
 * Wire layout: [2B Magic 0x4C 0x4D ("LM")][1B Ver 0x01][12B Nonce][Ciphertext + 16B Poly1305 Tag].
 */
class BluetoothSessionCipher(
    sessionKey: ByteArray,
    senderTag: String = "AND0"
) {
    private val keySpec: SecretKeySpec
    private val senderPrefix: Int
    private val counter = AtomicLong(1L)

    init {
        require(sessionKey.size == 32) { "Bluetooth session key must be 32 bytes" }
        keySpec = SecretKeySpec(sessionKey.copyOf(), "ChaCha20-Poly1305")
        val tagBytes = senderTag.encodeToByteArray()
        val b0 = (tagBytes.getOrElse(0) { 0x41.toByte() }.toInt() and 0xFF) shl 24
        val b1 = (tagBytes.getOrElse(1) { 0x4E.toByte() }.toInt() and 0xFF) shl 16
        val b2 = (tagBytes.getOrElse(2) { 0x44.toByte() }.toInt() and 0xFF) shl 8
        val b3 = tagBytes.getOrElse(3) { 0x30.toByte() }.toInt() and 0xFF
        senderPrefix = b0 or b1 or b2 or b3
    }

    fun seal(frame: WireFrame): ByteArray = seal(FrameEncoder.encode(frame))

    fun seal(plaintext: ByteArray): ByteArray {
        val seq = counter.getAndIncrement()
        val nonce = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(senderPrefix)
            putLong(seq)
        }.array()

        val aad = ByteArray(HEADER_SIZE).apply {
            this[0] = MAGIC_HI
            this[1] = MAGIC_LO
            this[2] = VERSION
            System.arraycopy(nonce, 0, this, 3, 12)
        }

        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(nonce))
        cipher.updateAAD(aad)
        val ciphertextAndTag = cipher.doFinal(plaintext)

        return ByteArray(HEADER_SIZE + ciphertextAndTag.size).apply {
            System.arraycopy(aad, 0, this, 0, HEADER_SIZE)
            System.arraycopy(ciphertextAndTag, 0, this, HEADER_SIZE, ciphertextAndTag.size)
        }
    }

    fun openFrame(envelope: ByteArray): WireFrame {
        val plaintext = open(envelope)
        return WireFrame.decode(plaintext)
    }

    fun open(envelope: ByteArray): ByteArray {
        require(envelope.size > HEADER_SIZE + TAG_SIZE) { "Malformed Bluetooth encrypted envelope" }
        require(envelope[0] == MAGIC_HI && envelope[1] == MAGIC_LO && envelope[2] == VERSION) {
            "Invalid Bluetooth encrypted envelope magic/version"
        }

        val aad = envelope.copyOfRange(0, HEADER_SIZE)
        val nonce = envelope.copyOfRange(3, HEADER_SIZE)
        val ciphertextAndTag = envelope.copyOfRange(HEADER_SIZE, envelope.size)

        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertextAndTag)
    }

    companion object {
        const val MAGIC_HI: Byte = 0x4C // 'L'
        const val MAGIC_LO: Byte = 0x4D // 'M'
        const val VERSION: Byte = 0x01
        const val HEADER_SIZE = 15
        const val TAG_SIZE = 16

        fun isEncryptedEnvelope(data: ByteArray): Boolean =
            data.size > HEADER_SIZE + TAG_SIZE &&
                data[0] == MAGIC_HI &&
                data[1] == MAGIC_LO &&
                data[2] == VERSION
    }
}
