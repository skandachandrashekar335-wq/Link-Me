import Foundation
import Network
import Security

/// Mutual TLS 1.3 Wi-Fi Client built on Network.framework NWConnection with TrustStore certificate pinning.
public final class WifiTlsClient: @unchecked Sendable {
    private let identity: DeviceIdentity
    private let expectedPeerDeviceId: String
    private let trustStore: TrustStore
    private let queue = DispatchQueue(label: "com.linkme.macbridge.wifi.tls.client")
    private var connection: NWConnection?

    public init(
        identity: DeviceIdentity,
        expectedPeerDeviceId: String,
        trustStore: TrustStore
    ) {
        self.identity = identity
        self.expectedPeerDeviceId = expectedPeerDeviceId
        self.trustStore = trustStore
    }

    public func connect(host: String, port: UInt16) async throws {
        guard let secIdentity = identity.makeSecIdentity() else {
            throw WifiTlsError.missingLocalIdentity
        }
        guard let nwPort = NWEndpoint.Port(rawValue: port) else {
            throw WifiTlsError.invalidPort
        }

        let parameters = MtlsTransport(trustStore: trustStore).makeTLSParameters(
            localIdentity: secIdentity,
            expectedDeviceId: expectedPeerDeviceId
        )
        let conn = NWConnection(host: NWEndpoint.Host(host), port: nwPort, using: parameters)
        self.connection = conn

        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            final class ResumeBox: @unchecked Sendable {
                var resumed = false
                let lock = NSLock()
            }
            let box = ResumeBox()

            conn.stateUpdateHandler = { state in
                box.lock.lock()
                defer { box.lock.unlock() }
                guard !box.resumed else { return }

                switch state {
                case .ready:
                    box.resumed = true
                    continuation.resume(returning: ())
                case .failed(let err):
                    box.resumed = true
                    continuation.resume(throwing: WifiTlsError.handshakeOrPinningFailed(err.localizedDescription))
                case .waiting(let err):
                    // Immediate TLS error surfaces on .waiting when local cert/pin fails
                    if case .tls = err {
                        box.resumed = true
                        conn.cancel()
                        continuation.resume(throwing: WifiTlsError.handshakeOrPinningFailed(err.localizedDescription))
                    }
                case .cancelled:
                    box.resumed = true
                    continuation.resume(throwing: WifiTlsError.connectionClosed)
                default:
                    break
                }
            }
            conn.start(queue: queue)
        }
    }

    public func send(frame: WireFrame) async throws {
        guard let conn = connection else {
            throw WifiTlsError.connectionClosed
        }
        let encoded = FrameEncoder.encode(frame)
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            conn.send(content: encoded, completion: .contentProcessed { error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: ())
                }
            })
        }
    }

    public func close() {
        connection?.send(content: nil, contentContext: .finalMessage, isComplete: true, completion: .contentProcessed { [weak self] _ in
            self?.connection?.cancel()
            self?.connection = nil
        })
    }
}
