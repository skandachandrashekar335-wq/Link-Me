import CoreImage
import CryptoKit
import Foundation
import Network

public struct PendingPairingApproval: Sendable, Equatable {
    public let peerDeviceId: String
    public let peerDeviceName: String
    public let peerCertSha256: String
    public let sasCode: String
    public let sharedBleSecret: Data
}

/// Feature 1: Encrypted QR Code Pairing & 6-digit SAS confirmation on macOS.
public actor PairingFeature {
    private let identityManager: IdentityManager
    private let trustStore: TrustStore
    private var activeSession: PairingCrypto?
    private var pendingApproval: PendingPairingApproval?
    private var listener: NWListener?

    public init(identityManager: IdentityManager, trustStore: TrustStore) {
        self.identityManager = identityManager
        self.trustStore = trustStore
    }

    public func beginPairingSession(tcpPort: UInt16 = 49152) throws -> (uri: PairingUriPayload, expiresAt: Date) {
        let identity = try identityManager.loadOrCreateIdentity()
        let session = PairingCrypto()
        self.activeSession = session
        self.pendingApproval = nil
        let uri = session.makePairingUri(
            deviceId: identity.deviceId,
            certSha256Hex: identity.certificateSha256Hex,
            tcpPort: tcpPort
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
            sharedBleSecret: derived.sharedBleSecret
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
            sharedBleSecret: approval.sharedBleSecret
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
        onReadyPort: @escaping @Sendable (UInt16) -> Void
    ) throws {
        listener?.cancel()
        let newListener = try NWListener(using: parameters)
        newListener.stateUpdateHandler = { state in
            if case .ready = state, let port = newListener.port?.rawValue {
                onReadyPort(port)
            }
        }
        newListener.start(queue: DispatchQueue(label: "com.linkme.macbridge.pairing-listener"))
        self.listener = newListener
    }

    public static func generateQRCodeCIImage(from uri: String) -> CIImage? {
        guard let data = uri.data(using: .utf8),
              let filter = CIFilter(name: "CIQRCodeGenerator") else {
            return nil
        }
        filter.setValue(data, forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")
        return filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 8, y: 8))
    }
}
