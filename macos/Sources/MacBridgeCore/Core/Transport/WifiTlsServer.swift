import Foundation
import Network
import Security

public enum WifiTlsError: Error, Sendable, Equatable {
    case missingLocalIdentity
    case invalidPort
    case handshakeOrPinningFailed(String)
    case connectionClosed
}

/// Mutual TLS 1.3 Wi-Fi Server built on Network.framework NWListener with TrustStore certificate pinning.
public final class WifiTlsServer: @unchecked Sendable {
    public static let bonjourServiceType = "_andromac._tcp"

    private let identity: DeviceIdentity
    private let expectedPeerDeviceId: String
    private let trustStore: TrustStore
    private let queue = DispatchQueue(label: "com.linkme.macbridge.wifi.tls.server")
    private let stateLock = NSLock()
    private var listener: NWListener?
    private var activeConnections: [ObjectIdentifier: NWConnection] = [:]
    private var readyConnections: [ObjectIdentifier: NWConnection] = [:]
    private var clientWaiters: [(UUID, WaiterBox)] = []

    public var onFrameReceived: (@Sendable (WireFrame) -> Void)?
    public var onClientConnected: (@Sendable () -> Void)?

    public var hasActiveClients: Bool {
        stateLock.lock()
        defer { stateLock.unlock() }
        return !readyConnections.isEmpty
    }

    public init(
        identity: DeviceIdentity,
        expectedPeerDeviceId: String,
        trustStore: TrustStore
    ) {
        self.identity = identity
        self.expectedPeerDeviceId = expectedPeerDeviceId
        self.trustStore = trustStore
    }

    public func start(port: UInt16 = 0, advertiseBonjour: Bool = true) async throws -> UInt16 {
        guard let secIdentity = identity.makeSecIdentity() else {
            throw WifiTlsError.missingLocalIdentity
        }
        let parameters = MtlsTransport(trustStore: trustStore).makeTLSParameters(
            localIdentity: secIdentity,
            expectedDeviceId: expectedPeerDeviceId
        )

        let nwPort: NWEndpoint.Port? = port == 0 ? .any : NWEndpoint.Port(rawValue: port)
        let newListener = try NWListener(using: parameters, on: nwPort ?? .any)
        if advertiseBonjour {
            newListener.service = NWListener.Service(
                name: identity.deviceId,
                type: Self.bonjourServiceType,
                domain: "local."
            )
        }

        newListener.newConnectionHandler = { [weak self] connection in
            self?.accept(connection)
        }

        let boundPort: UInt16 = try await withCheckedThrowingContinuation { continuation in
            final class ResumeBox: @unchecked Sendable {
                var resumed = false
                let lock = NSLock()
            }
            let box = ResumeBox()

            newListener.stateUpdateHandler = { state in
                box.lock.lock()
                defer { box.lock.unlock() }
                guard !box.resumed else { return }

                switch state {
                case .ready:
                    box.resumed = true
                    continuation.resume(returning: newListener.port?.rawValue ?? 0)
                case .failed(let err):
                    box.resumed = true
                    continuation.resume(throwing: WifiTlsError.handshakeOrPinningFailed(err.localizedDescription))
                case .cancelled:
                    box.resumed = true
                    continuation.resume(throwing: WifiTlsError.connectionClosed)
                default:
                    break
                }
            }
            newListener.start(queue: queue)
        }

        self.listener = newListener
        return boundPort
    }

    private final class WaiterBox: @unchecked Sendable {
        let lock = NSLock()
        var resumed = false
        var continuation: CheckedContinuation<Bool, Never>?

        func finish(_ value: Bool) {
            lock.lock()
            guard !resumed, let cont = continuation else {
                lock.unlock()
                return
            }
            resumed = true
            continuation = nil
            lock.unlock()
            cont.resume(returning: value)
        }
    }

    public func waitForClientConnection(timeoutSeconds: TimeInterval = 2.0) async -> Bool {
        if hasActiveClients {
            return true
        }
        let box = WaiterBox()
        return await withCheckedContinuation { continuation in
            box.lock.lock()
            box.continuation = continuation
            box.lock.unlock()

            stateLock.lock()
            if !readyConnections.isEmpty {
                stateLock.unlock()
                box.finish(true)
                return
            }
            clientWaiters.append((UUID(), box))
            stateLock.unlock()

            queue.asyncAfter(deadline: .now() + timeoutSeconds) { [weak self] in
                guard let self else {
                    box.finish(false)
                    return
                }
                self.stateLock.lock()
                self.clientWaiters.removeAll { $0.1 === box }
                let ready = !self.readyConnections.isEmpty
                self.stateLock.unlock()
                box.finish(ready)
            }
        }
    }

    private func firstReadyConnection() -> NWConnection? {
        stateLock.lock()
        defer { stateLock.unlock() }
        return readyConnections.values.first
    }

    public func send(frame: WireFrame) async throws {
        guard let conn = firstReadyConnection() else {
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

    public func stop() {
        stateLock.lock()
        readyConnections.removeAll()
        let waiters = clientWaiters
        clientWaiters.removeAll()
        stateLock.unlock()
        for (_, box) in waiters {
            box.finish(false)
        }

        queue.async { [weak self] in
            guard let self else { return }
            for conn in self.activeConnections.values {
                conn.cancel()
            }
            self.activeConnections.removeAll()
            self.listener?.cancel()
            self.listener = nil
        }
    }

    private actor ConnectionStreamProcessor {
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

    private func accept(_ connection: NWConnection) {
        let key = ObjectIdentifier(connection)
        activeConnections[key] = connection
        let processor = ConnectionStreamProcessor()

        connection.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                self.stateLock.lock()
                self.readyConnections[key] = connection
                let waiters = self.clientWaiters
                self.clientWaiters.removeAll()
                self.stateLock.unlock()
                for (_, box) in waiters {
                    box.finish(true)
                }
                self.onClientConnected?()
                self.receiveLoop(on: connection, processor: processor)
            case .failed, .cancelled:
                self.stateLock.lock()
                self.readyConnections.removeValue(forKey: key)
                self.stateLock.unlock()
                self.activeConnections.removeValue(forKey: key)
            default:
                break
            }
        }
        connection.start(queue: queue)
    }

    private func receiveLoop(
        on connection: NWConnection,
        processor: ConnectionStreamProcessor
    ) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65_536) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            if let data, !data.isEmpty {
                Task {
                    do {
                        let frames = try await processor.process(data)
                        for frame in frames {
                            self.onFrameReceived?(frame)
                        }
                        if isComplete || error != nil {
                            connection.cancel()
                        } else {
                            self.receiveLoop(on: connection, processor: processor)
                        }
                    } catch {
                        connection.cancel()
                    }
                }
                return
            }
            if isComplete || error != nil {
                connection.cancel()
                return
            }
            self.receiveLoop(on: connection, processor: processor)
        }
    }
}
