import CoreImage
import CryptoKit
import Darwin
import Foundation
import Network

public struct PendingPairingApproval: Sendable, Equatable {
    public let peerDeviceId: String
    public let peerDeviceName: String
    public let peerCertSha256: String
    public let sasCode: String
    public let sharedBleSecret: Data
    public let bluetoothSessionKey: Data

    public init(
        peerDeviceId: String,
        peerDeviceName: String,
        peerCertSha256: String,
        sasCode: String,
        sharedBleSecret: Data,
        bluetoothSessionKey: Data = Data(repeating: 0, count: 32)
    ) {
        self.peerDeviceId = peerDeviceId
        self.peerDeviceName = peerDeviceName
        self.peerCertSha256 = peerCertSha256
        self.sasCode = sasCode
        self.sharedBleSecret = sharedBleSecret
        self.bluetoothSessionKey = bluetoothSessionKey.count == 32 ? bluetoothSessionKey : sharedBleSecret
    }
}

/// Feature 1: Encrypted QR Code Pairing & 6-digit SAS confirmation on macOS.
public actor PairingFeature {
    private let identityManager: IdentityManager
    private let trustStore: TrustStore
    private var activeSession: PairingCrypto?
    private var pendingApproval: PendingPairingApproval?
    private var listener: NWListener?
    public private(set) var activeListeningPort: UInt16 = 49152

    public init(identityManager: IdentityManager, trustStore: TrustStore) {
        self.identityManager = identityManager
        self.trustStore = trustStore
    }

    public func beginPairingSession(
        tcpPort: UInt16 = 49152,
        deviceName: String? = nil
    ) throws -> (uri: PairingUriPayload, expiresAt: Date) {
        let identity = try identityManager.loadOrCreateIdentity()
        let session = PairingCrypto()
        self.activeSession = session
        self.pendingApproval = nil
        let effectivePort = activeListeningPort > 0 && tcpPort == 49152 ? activeListeningPort : tcpPort
        let uri = session.makePairingUri(
            deviceId: identity.deviceId,
            certSha256Hex: identity.certificateSha256Hex,
            tcpPort: effectivePort,
            bleServiceUuid: PairingUriPayload.defaultBleServiceUuid,
            lanHost: Self.localLanIPv4Address(),
            deviceName: deviceName ?? Host.current().localizedName
        )
        let expiresAt = session.createdAt.addingTimeInterval(PairingCrypto.sessionTtlSeconds)
        return (uri, expiresAt)
    }

    public func handleIncomingPairHello(_ hello: PairHelloPayload, now: Date = Date()) throws -> PendingPairingApproval {
        guard let session = activeSession else {
            throw PairingCryptoError.sessionExpired
        }
        let derived = try session.deriveSecrets(
            peerPublicKeyString: hello.ephemeralPublicKey,
            nonce: session.nonce,
            now: now
        )
        let approval = PendingPairingApproval(
            peerDeviceId: hello.deviceId,
            peerDeviceName: hello.deviceName,
            peerCertSha256: hello.spkiSha256,
            sasCode: derived.sasCode,
            sharedBleSecret: derived.sharedBleSecret,
            bluetoothSessionKey: derived.sessionKey
        )
        self.pendingApproval = approval
        return approval
    }

    public func confirmAndTrust() throws -> TrustedPeer {
        guard let approval = pendingApproval else {
            throw PairingCryptoError.sessionExpired
        }
        let peer = TrustedPeer(
            deviceId: approval.peerDeviceId,
            deviceName: approval.peerDeviceName,
            pinnedCertSha256: approval.peerCertSha256,
            sharedBleSecret: approval.sharedBleSecret,
            bluetoothSessionKey: approval.bluetoothSessionKey
        )
        try trustStore.savePeer(peer)
        activeSession = nil
        pendingApproval = nil
        listener?.cancel()
        listener = nil
        return peer
    }

    public func rejectPairing() {
        activeSession = nil
        pendingApproval = nil
        listener?.cancel()
        listener = nil
    }

    public func startTemporaryListener(
        parameters: NWParameters = .tcp,
        onReadyPort: @escaping @Sendable (UInt16) -> Void,
        onPairHelloReceived: (@Sendable (PairHelloPayload) -> Void)? = nil
    ) throws {
        listener?.cancel()
        let newListener = try NWListener(using: parameters)
        newListener.service = NWListener.Service(name: "LinkMe-Pair", type: "_linkme-pair._tcp", domain: "local.")
        newListener.stateUpdateHandler = { [weak self] state in
            if case .ready = state, let port = newListener.port?.rawValue {
                Task { await self?.setActivePort(port) }
                onReadyPort(port)
            }
        }
        newListener.newConnectionHandler = { connection in
            connection.start(queue: DispatchQueue(label: "com.linkme.macbridge.pairing-conn"))
            Self.readPairHelloFrame(from: connection, onPairHelloReceived: onPairHelloReceived)
        }
        newListener.start(queue: DispatchQueue(label: "com.linkme.macbridge.pairing-listener"))
        self.listener = newListener
    }

    private func setActivePort(_ port: UInt16) {
        self.activeListeningPort = port
    }

    private static func readPairHelloFrame(
        from connection: NWConnection,
        onPairHelloReceived: (@Sendable (PairHelloPayload) -> Void)?
    ) {
        connection.receive(minimumIncompleteLength: 16, maximumLength: 65_536) { data, _, _, _ in
            guard let data,
                  let frame = try? WireFrame.decode(from: data),
                  frame.messageType == .pairHello,
                  let hello = try? JSONDecoder().decode(PairHelloPayload.self, from: frame.payload) else {
                connection.cancel()
                return
            }
            onPairHelloReceived?(hello)
            connection.cancel()
        }
    }

    public static func generateQRCodeCIImage(from uri: String) -> CIImage? {
        guard let data = uri.data(using: .utf8),
              let filter = CIFilter(name: "CIQRCodeGenerator") else {
            return nil
        }
        filter.setValue(data, forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")
        return filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 10, y: 10))
    }

    public static func localLanIPv4Address() -> String? {
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let firstAddr = ifaddr else { return nil }
        defer { freeifaddrs(ifaddr) }

        for ptr in sequence(first: firstAddr, next: { $0.pointee.ifa_next }) {
            let interface = ptr.pointee
            let addrFamily = interface.ifa_addr.pointee.sa_family
            guard addrFamily == UInt8(AF_INET) else { continue }
            let name = String(cString: interface.ifa_name)
            guard name.hasPrefix("en") else { continue }
            var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            if getnameinfo(
                interface.ifa_addr,
                socklen_t(interface.ifa_addr.pointee.sa_len),
                &hostname,
                socklen_t(hostname.count),
                nil,
                0,
                NI_NUMERICHOST
            ) == 0 {
                let ip = String(cString: hostname)
                if !ip.hasPrefix("127.") {
                    return ip
                }
            }
        }
        return nil
    }
}
