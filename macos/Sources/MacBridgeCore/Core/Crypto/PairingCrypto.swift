@preconcurrency import CryptoKit
import Foundation
import Security

public enum PairingCryptoError: Error, Equatable, Sendable {
    case invalidUri
    case sessionExpired
    case invalidPeerPublicKey
    case invalidNonce
}

public struct PairingUriPayload: Sendable, Equatable {
    public let version: Int
    public let deviceId: String
    public let publicKeyBase64Url: String
    public let certSha256Prefix: String
    public let nonceHex: String
    public let tcpPort: UInt16

    public var uriString: String {
        "andromac://pair?v=\(version)&id=\(deviceId)&pub=\(publicKeyBase64Url)&fp=\(certSha256Prefix)&nonce=\(nonceHex)&port=\(tcpPort)"
    }

    public static func parse(_ uri: String) throws -> PairingUriPayload {
        guard let components = URLComponents(string: uri),
              components.scheme == "andromac",
              components.host == "pair",
              let items = components.queryItems else {
            throw PairingCryptoError.invalidUri
        }
        let dict = Dictionary(uniqueKeysWithValues: items.compactMap { item in
            item.value.map { (item.name, $0) }
        })
        guard let vStr = dict["v"], let version = Int(vStr), version == 1,
              let deviceId = dict["id"], !deviceId.isEmpty,
              let pub = dict["pub"], !pub.isEmpty,
              let fp = dict["fp"], !fp.isEmpty,
              let nonce = dict["nonce"], nonce.count == 32, Data(hexString: nonce) != nil,
              let portStr = dict["port"], let port = UInt16(portStr) else {
            throw PairingCryptoError.invalidUri
        }
        return PairingUriPayload(
            version: version,
            deviceId: deviceId,
            publicKeyBase64Url: pub,
            certSha256Prefix: fp,
            nonceHex: nonce,
            tcpPort: port
        )
    }
}

public struct PairingDerivedSecrets: Sendable, Equatable {
    public let sasCode: String
    public let sharedBleSecret: Data
    public let sessionKey: Data

    public init(sasCode: String, sharedBleSecret: Data, sessionKey: Data) {
        self.sasCode = sasCode
        self.sharedBleSecret = sharedBleSecret
        self.sessionKey = sessionKey
    }
}

/// Out-of-band Curve25519 QR pairing with 5-minute TTL, HKDF-SHA256 ("AndroMac-Pairing-v1"),
/// 6-digit SAS code, and 32-byte sharedBleSecret.
public struct PairingCrypto: @unchecked Sendable {
    public static let sessionTtlSeconds: TimeInterval = 300 // 5 minutes
    private static let hkdfInfo = Data("AndroMac-Pairing-v1".utf8)

    public let privateKey: Curve25519.KeyAgreement.PrivateKey
    public let createdAt: Date
    public let nonce: Data

    public init(
        privateKey: Curve25519.KeyAgreement.PrivateKey = Curve25519.KeyAgreement.PrivateKey(),
        nonce: Data? = nil,
        createdAt: Date = Date()
    ) {
        self.privateKey = privateKey
        self.createdAt = createdAt
        if let nonce {
            precondition(nonce.count == 16, "Pairing nonce must be 16 bytes")
            self.nonce = nonce
        } else {
            var bytes = Data(count: 16)
            _ = bytes.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 16, $0.baseAddress!) }
            self.nonce = bytes
        }
    }

    public var publicKeyBase64Url: String {
        Self.base64UrlEncode(privateKey.publicKey.rawRepresentation)
    }

    public var publicKeyBase64: String {
        privateKey.publicKey.rawRepresentation.base64EncodedString()
    }

    public func isExpired(at date: Date = Date()) -> Bool {
        date.timeIntervalSince(createdAt) > Self.sessionTtlSeconds
    }

    public func makePairingUri(
        deviceId: String,
        certSha256Hex: String,
        tcpPort: UInt16
    ) -> PairingUriPayload {
        PairingUriPayload(
            version: 1,
            deviceId: deviceId,
            publicKeyBase64Url: publicKeyBase64Url,
            certSha256Prefix: String(certSha256Hex.prefix(16)),
            nonceHex: nonce.hexString,
            tcpPort: tcpPort
        )
    }

    public func deriveSecrets(
        peerPublicKeyString: String,
        nonce: Data,
        now: Date = Date()
    ) throws -> PairingDerivedSecrets {
        guard !isExpired(at: now) else {
            throw PairingCryptoError.sessionExpired
        }
        guard nonce.count == 16 else {
            throw PairingCryptoError.invalidNonce
        }
        guard let peerKeyData = Self.decodeKeyString(peerPublicKeyString),
              let peerKey = try? Curve25519.KeyAgreement.PublicKey(rawRepresentation: peerKeyData) else {
            throw PairingCryptoError.invalidPeerPublicKey
        }

        let sharedSecret = try privateKey.sharedSecretFromKeyAgreement(with: peerKey)
        // Expand 68 bytes: [0..<4] SAS uint32 | [4..<36] sharedBleSecret (32B) | [36..<68] sessionKey (32B)
        let okm = sharedSecret.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: nonce,
            sharedInfo: Self.hkdfInfo,
            outputByteCount: 68
        )
        let okmData = okm.withUnsafeBytes { Data($0) }
        let sasInt = (UInt32(okmData[0]) << 24)
            | (UInt32(okmData[1]) << 16)
            | (UInt32(okmData[2]) << 8)
            | UInt32(okmData[3])
        let sasCode = String(format: "%06u", sasInt % 1_000_000)
        let sharedBleSecret = Data(okmData[4..<36])
        let sessionKey = Data(okmData[36..<68])

        return PairingDerivedSecrets(
            sasCode: sasCode,
            sharedBleSecret: sharedBleSecret,
            sessionKey: sessionKey
        )
    }

    public static func base64UrlEncode(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    public static func decodeKeyString(_ string: String) -> Data? {
        if let standard = Data(base64Encoded: string), standard.count == 32 {
            return standard
        }
        var padded = string
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        let remainder = padded.count % 4
        if remainder > 0 {
            padded += String(repeating: "=", count: 4 - remainder)
        }
        guard let data = Data(base64Encoded: padded), data.count == 32 else { return nil }
        return data
    }
}
