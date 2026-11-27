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

    public var onFrameReceived: (@Sendable (WireFrame) -> Void)?

    public init(
        identity: DeviceIdentity,
        expectedPeerDeviceId: String,
        trustStore: TrustStore
    ) {
        self.identity = identity
        self.expectedPeerDeviceId = expectedPeerDeviceId
        self.trustStore = trustStore
    }

    private actor ClientStreamProcessor {
        private var decoder = FrameDecoder()
        private let replayGuard = ReplayGuard()

        func process(_ data: Data) async throws -> [WireFrame] {
            decoder.append(data)
            let frames = try decoder.drainFrames()
            for frame in frames {
                try await replayGuard.validateAndAccept(frame.sequenceId)
            }
            return frames
        }
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
        let processor = ClientStreamProcessor()

        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            final class ResumeBox: @unchecked Sendable {
                var resumed = false
                let lock = NSLock()
            }
            let box = ResumeBox()

            conn.stateUpdateHandler = { [weak self] state in
                box.lock.lock()
                let alreadyResumed = box.resumed
                if !alreadyResumed {
                    switch state {
                    case .ready:
                        box.resumed = true
                        box.lock.unlock()
                        self?.receiveLoop(on: conn, processor: processor)
                        continuation.resume(returning: ())
                        return
                    case .failed(let err):
                        box.resumed = true
                        box.lock.unlock()
                        continuation.resume(throwing: WifiTlsError.handshakeOrPinningFailed(err.localizedDescription))
                        return
                    case .waiting(let err):
                        // Immediate TLS error surfaces on .waiting when local cert/pin fails
                        if case .tls = err {
                            box.resumed = true
                            box.lock.unlock()
                            conn.cancel()
                            continuation.resume(throwing: WifiTlsError.handshakeOrPinningFailed(err.localizedDescription))
                            return
                        }
                    case .cancelled:
                        box.resumed = true
                        box.lock.unlock()
                        continuation.resume(throwing: WifiTlsError.connectionClosed)
                        return
                    default:
                        break
                    }
                }
                box.lock.unlock()

                if case .failed = state {
                    conn.cancel()
                }
            }
            conn.start(queue: queue)
        }
    }

    private func receiveLoop(
        on conn: NWConnection,
        processor: ClientStreamProcessor
    ) {
        conn.receive(minimumIncompleteLength: 1, maximumLength: 65_536) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            if let data, !data.isEmpty {
                Task {
                    do {
                        let frames = try await processor.process(data)
                        for frame in frames {
                            self.onFrameReceived?(frame)
                        }
                        if isComplete || error != nil {
                            conn.cancel()
                        } else {
                            self.receiveLoop(on: conn, processor: processor)
                        }
                    } catch {
                        conn.cancel()
                    }
                }
                return
            }
            if isComplete || error != nil {
                conn.cancel()
                return
            }
            self.receiveLoop(on: conn, processor: processor)
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
