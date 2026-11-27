import CryptoKit
import Foundation

/// Computes and verifies 8-byte rotating BLE identifiers:
/// HMAC-SHA256(key: sharedBleSecret, data: uint64_be(floor(unixTimestamp / 900)))[0..<8].
public struct BlePrivacyBeacon: Sendable {
    public static let rotationWindowSeconds: TimeInterval = 900 // 15 minutes

    private let secretData: Data

    public init(sharedBleSecret: Data) {
        precondition(sharedBleSecret.count == 32, "sharedBleSecret must be 32 bytes")
        self.secretData = sharedBleSecret
    }

    public func token(at date: Date = Date()) -> Data {
        let bucket = Self.bucket(for: date)
        return Self.computeToken(key: SymmetricKey(data: secretData), bucket: bucket)
    }

    public func verify(token: Data, at date: Date = Date()) -> Bool {
        guard token.count == 8 else { return false }
        let key = SymmetricKey(data: secretData)
        let current = Self.bucket(for: date)
        let buckets: [UInt64] = current > 0 ? [current, current - 1, current + 1] : [current, current + 1]
        for bucket in buckets {
            let expected = Self.computeToken(key: key, bucket: bucket)
            if ConstantTime.equals(token, expected) {
                return true
            }
        }
        return false
    }

    private static func bucket(for date: Date) -> UInt64 {
        UInt64(max(0, date.timeIntervalSince1970) / rotationWindowSeconds)
    }

    private static func computeToken(key: SymmetricKey, bucket: UInt64) -> Data {
        var bigEndianBucket = bucket.bigEndian
        let bucketData = withUnsafeBytes(of: &bigEndianBucket) { Data($0) }
        let mac = HMAC<SHA256>.authenticationCode(for: bucketData, using: key)
        return Data(mac.prefix(8))
    }
}
