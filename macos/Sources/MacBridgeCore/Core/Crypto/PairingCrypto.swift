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
    public static let defaultBleServiceUuid = "414D0001-0000-1000-8000-00805F9B34FB"

    public let version: Int
    public let deviceId: String
    public let publicKeyBase64Url: String
    public let certSha256Prefix: String
    public let nonceHex: String
    public let pairToken: String
    public let tcpPort: UInt16
    public let bleServiceUuid: String?
    public let lanHost: String?
    public let deviceName: String?
    public let btAddress: String?

    public init(
        version: Int,
        deviceId: String,
        publicKeyBase64Url: String,
        certSha256Prefix: String,
        nonceHex: String,
        pairToken: String? = nil,
        tcpPort: UInt16,
        bleServiceUuid: String? = PairingUriPayload.defaultBleServiceUuid,
        lanHost: String? = nil,
        deviceName: String? = nil,
        btAddress: String? = nil
    ) {
        self.version = version
        self.deviceId = deviceId
        self.publicKeyBase64Url = publicKeyBase64Url
        self.certSha256Prefix = certSha256Prefix.lowercased()
        self.nonceHex = nonceHex
        self.pairToken = pairToken ?? String(nonceHex.prefix(16)).lowercased()
        self.tcpPort = tcpPort
        self.bleServiceUuid = bleServiceUuid
        self.lanHost = lanHost
        self.deviceName = deviceName
        self.btAddress = btAddress
    }

    public var hasFullCertFingerprint: Bool {
        certSha256Prefix.count == 64 && Data(hexString: certSha256Prefix) != nil
    }

    public var uriString: String {
        var components = URLComponents()
        components.scheme = "linkme"
        components.host = "pair"
        var queryItems: [URLQueryItem] = [
            URLQueryItem(name: "v", value: String(version)),
            URLQueryItem(name: "id", value: deviceId),
            URLQueryItem(name: "pub", value: publicKeyBase64Url),
            URLQueryItem(name: "pk", value: publicKeyBase64Url),
            URLQueryItem(name: "fp", value: certSha256Prefix),
            URLQueryItem(name: "nonce", value: nonceHex),
            URLQueryItem(name: "tok", value: pairToken),
            URLQueryItem(name: "ble", value: (bleServiceUuid?.isEmpty == false ? bleServiceUuid! : Self.defaultBleServiceUuid))
        ]
        if let host = lanHost, !host.isEmpty, tcpPort > 0 {
            queryItems.append(URLQueryItem(name: "ip", value: host))
            queryItems.append(URLQueryItem(name: "host", value: host))
            queryItems.append(URLQueryItem(name: "port", value: String(tcpPort)))
        } else if tcpPort > 0 {
            queryItems.append(URLQueryItem(name: "port", value: String(tcpPort)))
        }
        if let name = deviceName, !name.isEmpty {
            queryItems.append(URLQueryItem(name: "name", value: name))
        }
        if let bt = btAddress, !bt.isEmpty {
            queryItems.append(URLQueryItem(name: "bt", value: bt))
        }
        components.queryItems = queryItems
        return components.string ?? "linkme://pair?v=\(version)&id=\(deviceId)&pk=\(publicKeyBase64Url)&fp=\(certSha256Prefix)&nonce=\(nonceHex)&tok=\(pairToken)&ble=1"
    }

    public static func parse(_ uri: String) throws -> PairingUriPayload {
        guard let components = URLComponents(string: uri.trimmingCharacters(in: .whitespacesAndNewlines)),
              let scheme = components.scheme?.lowercased(),
              scheme == "linkme" || scheme == "andromac",
              components.host == "pair",
              let items = components.queryItems else {
            throw PairingCryptoError.invalidUri
        }
        let dict = Dictionary(items.compactMap { item in
            item.value.map { (item.name, $0) }
        }, uniquingKeysWith: { _, latest in latest })
        guard let vStr = dict["v"], let version = Int(vStr), (version == 1 || version == 2 || version == 3),
              let deviceId = dict["id"], !deviceId.isEmpty,
              let pub = dict["pk"] ?? dict["pub"], !pub.isEmpty,
              let fp = dict["fp"], fp.count >= 16,
              let nonce = dict["nonce"], nonce.count == 32, Data(hexString: nonce) != nil else {
            throw PairingCryptoError.invalidUri
        }
        let port: UInt16
        if let portStr = dict["port"] {
            guard let parsedPort = UInt16(portStr) else { throw PairingCryptoError.invalidUri }
            port = parsedPort
        } else if version >= 2 {
            port = 0
        } else {
            throw PairingCryptoError.invalidUri
        }
        let bleRaw = dict["ble"]
        let bleUuid = (bleRaw == "1" || bleRaw == nil || bleRaw?.isEmpty == true) ? defaultBleServiceUuid : bleRaw
        let btAddress = dict["bt"]
        return PairingUriPayload(
            version: version,
            deviceId: deviceId,
            publicKeyBase64Url: pub,
            certSha256Prefix: fp,
            nonceHex: nonce,
            pairToken: dict["tok"],
            tcpPort: port,
            bleServiceUuid: bleUuid,
            lanHost: dict["ip"] ?? dict["host"],
            deviceName: dict["name"],
            btAddress: btAddress
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

    public var pairToken: String {
        let digest = SHA256.hash(data: nonce + privateKey.publicKey.rawRepresentation)
        return Data(digest.prefix(8)).hexString
    }

    /// 4-byte SHA-256 discovery tag advertised in BLE Service Data by Android GATT Server during QR pairing.
    public static func computeTokenTag(_ pairToken: String) -> Data {
        let digest = SHA256.hash(data: Data(pairToken.utf8))
        return Data(digest.prefix(4))
    }

    public func makePairingUri(
        deviceId: String,
        certSha256Hex: String,
        tcpPort: UInt16,
        bleServiceUuid: String? = PairingUriPayload.defaultBleServiceUuid,
        lanHost: String? = nil,
        deviceName: String? = nil,
        btAddress: String? = nil
    ) -> PairingUriPayload {
        PairingUriPayload(
            version: 3,
            deviceId: deviceId,
            publicKeyBase64Url: publicKeyBase64Url,
            certSha256Prefix: certSha256Hex.lowercased(),
            nonceHex: nonce.hexString,
            pairToken: pairToken,
            tcpPort: tcpPort,
            bleServiceUuid: bleServiceUuid,
            lanHost: lanHost,
            deviceName: deviceName,
            btAddress: btAddress
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
