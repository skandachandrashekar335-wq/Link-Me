import CryptoKit
import Foundation
import Security

public enum BluetoothCipherError: Error, Equatable, Sendable {
    case invalidKeyLength
    case malformedEnvelope
    case decryptionFailed
}

/// Encrypts and authenticates binary WireFrames over Bluetooth (BLE GATT & L2CAP)
/// using ChaCha20-Poly1305 with the X25519+HKDF derived 32-byte session key.
/// Wire layout: [2B Magic 0x4C 0x4D ("LM")][1B Ver 0x01][12B Nonce][Ciphertext + 16B Poly1305 Tag].
public final class BluetoothSessionCipher: @unchecked Sendable {
    public static let magicHi: UInt8 = 0x4C // 'L'
    public static let magicLo: UInt8 = 0x4D // 'M'
    public static let version: UInt8 = 0x01
    public static let headerSize = 15 // 2B magic + 1B version + 12B nonce
    public static let tagSize = 16

    private let key: SymmetricKey
    private let senderPrefix: UInt32
    private let lock = NSLock()
    private var counter: UInt64 = 1

    public init(sessionKey: Data, senderTag: String = "MAC0") throws {
        guard sessionKey.count == 32 else {
            throw BluetoothCipherError.invalidKeyLength
        }
        self.key = SymmetricKey(data: sessionKey)
        let tagBytes = Array(senderTag.utf8.prefix(4))
        let b0 = UInt32(tagBytes.indices.contains(0) ? tagBytes[0] : 0x4C) << 24
        let b1 = UInt32(tagBytes.indices.contains(1) ? tagBytes[1] : 0x4D) << 16
        let b2 = UInt32(tagBytes.indices.contains(2) ? tagBytes[2] : 0x30) << 8
        let b3 = UInt32(tagBytes.indices.contains(3) ? tagBytes[3] : 0x31)
        self.senderPrefix = b0 | b1 | b2 | b3
    }

    public func seal(frame: WireFrame) throws -> Data {
        try seal(plaintext: FrameEncoder.encode(frame))
    }

    public func seal(plaintext: Data) throws -> Data {
        let seq: UInt64 = lock.withLock {
            let current = counter
            counter &+= 1
            return current
        }
        var nonceData = Data(capacity: 12)
        var prefixBE = senderPrefix.bigEndian
        withUnsafeBytes(of: &prefixBE) { nonceData.append(contentsOf: $0) }
        var seqBE = seq.bigEndian
        withUnsafeBytes(of: &seqBE) { nonceData.append(contentsOf: $0) }

        let nonce = try ChaChaPoly.Nonce(data: nonceData)
        var aad = Data(capacity: Self.headerSize)
        aad.append(Self.magicHi)
        aad.append(Self.magicLo)
        aad.append(Self.version)
        aad.append(nonceData)

        let sealed = try ChaChaPoly.seal(plaintext, using: key, nonce: nonce, authenticating: aad)
        var envelope = Data(capacity: Self.headerSize + sealed.ciphertext.count + Self.tagSize)
        envelope.append(aad)
        envelope.append(sealed.ciphertext)
        envelope.append(sealed.tag)
        return envelope
    }

    public func openFrame(from envelope: Data) throws -> WireFrame {
        let plaintext = try open(envelope: envelope)
        return try WireFrame.decode(from: plaintext)
    }

    public func open(envelope: Data) throws -> Data {
        guard envelope.count > Self.headerSize + Self.tagSize else {
            throw BluetoothCipherError.malformedEnvelope
        }
        let s = envelope.startIndex
        guard envelope[s] == Self.magicHi,
              envelope[s + 1] == Self.magicLo,
              envelope[s + 2] == Self.version else {
            throw BluetoothCipherError.malformedEnvelope
        }
        let aad = Data(envelope[s..<(s + Self.headerSize)])
        let nonceData = Data(envelope[(s + 3)..<(s + 15)])
        let tagStart = envelope.endIndex - Self.tagSize
        let ciphertext = Data(envelope[(s + Self.headerSize)..<tagStart])
        let tag = Data(envelope[tagStart..<envelope.endIndex])

        let nonce = try ChaChaPoly.Nonce(data: nonceData)
        let box = try ChaChaPoly.SealedBox(nonce: nonce, ciphertext: ciphertext, tag: tag)
        do {
            return try ChaChaPoly.open(box, using: key, authenticating: aad)
        } catch {
            throw BluetoothCipherError.decryptionFailed
        }
    }

    public static func isEncryptedEnvelope(_ data: Data) -> Bool {
        data.count > headerSize + tagSize
            && data[data.startIndex] == magicHi
            && data[data.startIndex + 1] == magicLo
            && data[data.startIndex + 2] == version
    }
}
