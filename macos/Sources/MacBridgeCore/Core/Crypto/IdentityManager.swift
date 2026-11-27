import CryptoKit
import Foundation
import Security

public struct DeviceIdentity: Sendable, Equatable {
    public let deviceId: String
    public let privateKeyRaw: Data
    public let certificateDer: Data
    public let spkiSha256Hex: String
    public let certificateSha256Hex: String

    public init(
        deviceId: String,
        privateKeyRaw: Data,
        certificateDer: Data,
        spkiSha256Hex: String,
        certificateSha256Hex: String
    ) {
        self.deviceId = deviceId
        self.privateKeyRaw = privateKeyRaw
        self.certificateDer = certificateDer
        self.spkiSha256Hex = spkiSha256Hex
        self.certificateSha256Hex = certificateSha256Hex
    }

    /// Reconstructs an in-memory SecIdentity from the P-256 private key and X.509 DER certificate.
    public func makeSecIdentity() -> SecIdentity? {
        guard let signingKey = try? P256.Signing.PrivateKey(rawRepresentation: privateKeyRaw),
              let secCert = SecCertificateCreateWithData(nil, certificateDer as CFData) else {
            return nil
        }
        let x963Private = Data([0x04]) + signingKey.publicKey.rawRepresentation + signingKey.rawRepresentation
        let attrs: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeyClass as String: kSecAttrKeyClassPrivate,
            kSecAttrKeySizeInBits as String: 256
        ]
        var error: Unmanaged<CFError>?
        guard let secKey = SecKeyCreateWithData(x963Private as CFData, attrs as CFDictionary, &error) else {
            return nil
        }
        return SecIdentityCreate(nil, secCert, secKey)
    }
}

public protocol SecureKeyValueStore: Sendable {
    func saveData(_ data: Data, forKey key: String) throws
    func loadData(forKey key: String) throws -> Data?
    func deleteData(forKey key: String) throws
}

public final class KeychainSecureStore: SecureKeyValueStore, @unchecked Sendable {
    private let service: String

    public init(service: String = "com.linkme.macbridge.secure") {
        self.service = service
    }

    public func saveData(_ data: Data, forKey key: String) throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key
        ]
        SecItemDelete(query as CFDictionary)

        var addQuery = query
        addQuery[kSecValueData as String] = data
        addQuery[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(addQuery as CFDictionary, nil)
        guard status == errSecSuccess else {
            throw NSError(domain: NSOSStatusErrorDomain, code: Int(status))
        }
    }

    public func loadData(forKey key: String) throws -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else {
            throw NSError(domain: NSOSStatusErrorDomain, code: Int(status))
        }
        return result as? Data
    }

    public func deleteData(forKey key: String) throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key
        ]
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw NSError(domain: NSOSStatusErrorDomain, code: Int(status))
        }
    }
}

public final class InMemorySecureStore: SecureKeyValueStore, @unchecked Sendable {
    private var storage: [String: Data] = [:]
    private let lock = NSLock()
    public init() {}
    public func saveData(_ data: Data, forKey key: String) throws {
        lock.lock(); defer { lock.unlock() }
        storage[key] = data
    }
    public func loadData(forKey key: String) throws -> Data? {
        lock.lock(); defer { lock.unlock() }
        return storage[key]
    }
    public func deleteData(forKey key: String) throws {
        lock.lock(); defer { lock.unlock() }
        storage.removeValue(forKey: key)
    }
}

/// Manages the local device P-256 identity keypair and self-signed 10-year X.509 DER certificate.
public final class IdentityManager: @unchecked Sendable {
    private let store: SecureKeyValueStore
    private let identityStorageKey = "local_device_identity_v1"

    public init(store: SecureKeyValueStore = KeychainSecureStore()) {
        self.store = store
    }

    public func loadOrCreateIdentity(deviceId: String = UUID().uuidString.lowercased()) throws -> DeviceIdentity {
        if let existingData = try store.loadData(forKey: identityStorageKey),
           let stored = try? JSONDecoder().decode(StoredIdentity.self, from: existingData) {
            return DeviceIdentity(
                deviceId: stored.deviceId,
                privateKeyRaw: stored.privateKeyRaw,
                certificateDer: stored.certificateDer,
                spkiSha256Hex: stored.spkiSha256Hex,
                certificateSha256Hex: stored.certificateSha256Hex
            )
        }

        let privateKey = P256.Signing.PrivateKey()
        let commonName = "MacBridge-\(deviceId)"
        let (certDer, spkiDer) = try SelfSignedX509Builder.buildP256Certificate(
            privateKey: privateKey,
            commonName: commonName,
            validityYears: 10
        )
        let spkiHash = SHA256.hash(data: spkiDer).hexString
        let certHash = SHA256.hash(data: certDer).hexString

        let stored = StoredIdentity(
            deviceId: deviceId,
            privateKeyRaw: privateKey.rawRepresentation,
            certificateDer: certDer,
            spkiSha256Hex: spkiHash,
            certificateSha256Hex: certHash
        )
        let encoded = try JSONEncoder().encode(stored)
        try store.saveData(encoded, forKey: identityStorageKey)

        return DeviceIdentity(
            deviceId: stored.deviceId,
            privateKeyRaw: stored.privateKeyRaw,
            certificateDer: stored.certificateDer,
            spkiSha256Hex: stored.spkiSha256Hex,
            certificateSha256Hex: stored.certificateSha256Hex
        )
    }

    private struct StoredIdentity: Codable {
        let deviceId: String
        let privateKeyRaw: Data
        let certificateDer: Data
        let spkiSha256Hex: String
        let certificateSha256Hex: String
    }
}

/// Minimal RFC 5280 ASN.1 DER builder for self-signed P-256 X.509 v3 certificates.
enum SelfSignedX509Builder {
    static func buildP256Certificate(
        privateKey: P256.Signing.PrivateKey,
        commonName: String,
        validityYears: Int,
        now: Date = Date()
    ) throws -> (certificateDer: Data, spkiDer: Data) {
        let version = derTag(0xA0, derInteger(Data([0x02]))) // v3
        var serialBytes = Data(count: 16)
        _ = serialBytes.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 16, $0.baseAddress!) }
        serialBytes[0] &= 0x7F // positive integer
        if serialBytes[0] == 0 { serialBytes[0] = 0x01 }
        let serial = derInteger(serialBytes)

        // ecdsa-with-SHA256 OID: 1.2.840.10045.4.3.2
        let sigAlg = derSequence(oid([1, 2, 840, 10045, 4, 3, 2]))
        let name = derSequence(derSet(derSequence(oid([2, 5, 4, 3]) + derUTF8String(commonName))))

        let notBefore = derX509Time(now)
        let notAfterDate = Calendar(identifier: .gregorian).date(byAdding: .year, value: validityYears, to: now)
            ?? now.addingTimeInterval(TimeInterval(validityYears) * 365 * 86400)
        let notAfter = derX509Time(notAfterDate)
        let validity = derSequence(notBefore + notAfter)

        // SubjectPublicKeyInfo: id-ecPublicKey (1.2.840.10045.2.1) + prime256v1 (1.2.840.10045.3.1.7)
        let spkiAlg = derSequence(oid([1, 2, 840, 10045, 2, 1]) + oid([1, 2, 840, 10045, 3, 1, 7]))
        let ecPoint = Data([0x04]) + privateKey.publicKey.rawRepresentation
        let spki = derSequence(spkiAlg + derBitString(ecPoint))

        let tbsCertificate = derSequence(version + serial + sigAlg + name + validity + name + spki)
        let signature = try privateKey.signature(for: tbsCertificate).derRepresentation
        let fullCert = derSequence(tbsCertificate + sigAlg + derBitString(signature))
        return (fullCert, spki)
    }

    private static func derLength(_ length: Int) -> Data {
        if length < 0x80 {
            return Data([UInt8(length)])
        }
        var len = length
        var bytes: [UInt8] = []
        while len > 0 {
            bytes.insert(UInt8(len & 0xFF), at: 0)
            len >>= 8
        }
        return Data([0x80 | UInt8(bytes.count)] + bytes)
    }

    private static func derTag(_ tag: UInt8, _ content: Data) -> Data {
        Data([tag]) + derLength(content.count) + content
    }

    private static func derSequence(_ content: Data) -> Data { derTag(0x30, content) }
    private static func derSet(_ content: Data) -> Data { derTag(0x31, content) }
    private static func derUTF8String(_ value: String) -> Data { derTag(0x0C, Data(value.utf8)) }

    private static func derBitString(_ bytes: Data) -> Data {
        derTag(0x03, Data([0x00]) + bytes)
    }

    private static func derInteger(_ raw: Data) -> Data {
        var bytes = raw
        if let first = bytes.first, first & 0x80 != 0 {
            bytes.insert(0x00, at: 0)
        }
        return derTag(0x02, bytes)
    }

    private static func derX509Time(_ date: Date) -> Data {
        let year = Calendar(identifier: .gregorian).component(.year, from: date)
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        if year < 2050 {
            formatter.dateFormat = "yyMMddHHmmss'Z'"
            return derTag(0x17, Data(formatter.string(from: date).utf8)) // UTCTime per RFC 5280
        } else {
            formatter.dateFormat = "yyyyMMddHHmmss'Z'"
            return derTag(0x18, Data(formatter.string(from: date).utf8)) // GeneralizedTime
        }
    }

    private static func oid(_ components: [UInt64]) -> Data {
        precondition(components.count >= 2)
        var encoded: [UInt8] = [UInt8(components[0] * 40 + components[1])]
        for comp in components.dropFirst(2) {
            var value = comp
            var subBytes: [UInt8] = [UInt8(value & 0x7F)]
            value >>= 7
            while value > 0 {
                subBytes.insert(UInt8((value & 0x7F) | 0x80), at: 0)
                value >>= 7
            }
            encoded.append(contentsOf: subBytes)
        }
        return derTag(0x06, Data(encoded))
    }
}

extension Digest {
    var hexString: String {
        map { String(format: "%02x", $0) }.joined()
    }
}
