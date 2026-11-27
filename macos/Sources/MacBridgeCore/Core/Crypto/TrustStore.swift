import CryptoKit
import Foundation

public struct TrustedPeer: Codable, Sendable, Equatable {
    public let deviceId: String
    public let deviceName: String
    public let pinnedCertSha256: String
    public let sharedBleSecret: Data

    public init(deviceId: String, deviceName: String, pinnedCertSha256: String, sharedBleSecret: Data) {
        self.deviceId = deviceId
        self.deviceName = deviceName
        self.pinnedCertSha256 = pinnedCertSha256.lowercased()
        self.sharedBleSecret = sharedBleSecret
    }
}

/// Stores paired peer certificate pins and 32-byte BLE secrets, performing constant-time DER/SPKI verification.
public final class TrustStore: @unchecked Sendable {
    private let store: SecureKeyValueStore
    private let peersKey = "trusted_peers_v1"
    private let lock = NSLock()

    public init(store: SecureKeyValueStore = KeychainSecureStore()) {
        self.store = store
    }

    public func savePeer(_ peer: TrustedPeer) throws {
        precondition(peer.sharedBleSecret.count == 32, "sharedBleSecret must be 32 bytes")
        precondition(peer.pinnedCertSha256.count == 64, "pinnedCertSha256 must be 64 hex chars")
        lock.lock()
        defer { lock.unlock() }

        var peers = try loadAllPeersLocked()
        peers[peer.deviceId] = peer
        let data = try JSONEncoder().encode(peers)
        try store.saveData(data, forKey: peersKey)
    }

    public func peer(forDeviceId deviceId: String) throws -> TrustedPeer? {
        lock.lock()
        defer { lock.unlock() }
        return try loadAllPeersLocked()[deviceId]
    }

    public func allPeers() throws -> [TrustedPeer] {
        lock.lock()
        defer { lock.unlock() }
        return Array(try loadAllPeersLocked().values)
    }

    public func removePeer(deviceId: String) throws {
        lock.lock()
        defer { lock.unlock() }
        var peers = try loadAllPeersLocked()
        peers.removeValue(forKey: deviceId)
        let data = try JSONEncoder().encode(peers)
        try store.saveData(data, forKey: peersKey)
    }

    public func verifyPeerCertificate(derBytes: Data, expectedDeviceId: String) -> Bool {
        guard let peer = try? peer(forDeviceId: expectedDeviceId),
              let expectedDigest = Data(hexString: peer.pinnedCertSha256) else {
            return false
        }
        let actualDigest = Data(SHA256.hash(data: derBytes))
        return ConstantTime.equals(actualDigest, expectedDigest)
    }

    private func loadAllPeersLocked() throws -> [String: TrustedPeer] {
        guard let data = try store.loadData(forKey: peersKey) else { return [:] }
        return (try? JSONDecoder().decode([String: TrustedPeer].self, from: data)) ?? [:]
    }
}

public enum ConstantTime {
    public static func equals(_ lhs: Data, _ rhs: Data) -> Bool {
        guard lhs.count == rhs.count else { return false }
        var diff: UInt8 = 0
        for (a, b) in zip(lhs, rhs) {
            diff |= a ^ b
        }
        return diff == 0
    }
}

extension Data {
    public init?(hexString: String) {
        guard hexString.count % 2 == 0 else { return nil }
        var out = Data(capacity: hexString.count / 2)
        var idx = hexString.startIndex
        while idx < hexString.endIndex {
            let next = hexString.index(idx, offsetBy: 2)
            guard let byte = UInt8(hexString[idx..<next], radix: 16) else { return nil }
            out.append(byte)
            idx = next
        }
        self = out
    }

    public var hexString: String {
        map { String(format: "%02x", $0) }.joined()
    }
}
