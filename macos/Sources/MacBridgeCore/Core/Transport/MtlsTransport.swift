import Foundation
import Network
import Security

/// Builds TLS 1.3 Network.framework parameters with Keychain TrustStore certificate pinning.
public final class MtlsTransport: @unchecked Sendable {
    public static let bonjourServiceType = "_linkme._tcp"
    private let trustStore: TrustStore

    public init(trustStore: TrustStore = TrustStore()) {
        self.trustStore = trustStore
    }

    public func makeTLSParameters(
        localIdentity: SecIdentity?,
        expectedDeviceId: String
    ) -> NWParameters {
        let tlsOptions = NWProtocolTLS.Options()
        let secOptions = tlsOptions.securityProtocolOptions

        sec_protocol_options_set_min_tls_protocol_version(secOptions, .TLSv13)
        sec_protocol_options_set_max_tls_protocol_version(secOptions, .TLSv13)
        sec_protocol_options_set_peer_authentication_required(secOptions, true)

        if let localIdentity, let secIdentity = sec_identity_create(localIdentity) {
            sec_protocol_options_set_local_identity(secOptions, secIdentity)
        }

        let store = self.trustStore
        sec_protocol_options_set_verify_block(
            secOptions,
            { _, secTrust, completion in
                let trust = sec_trust_copy_ref(secTrust).takeRetainedValue()
                guard let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate],
                      let leaf = chain.first else {
                    completion(false)
                    return
                }
                let certDer = SecCertificateCopyData(leaf) as Data
                let matched = store.verifyPeerCertificate(derBytes: certDer, expectedDeviceId: expectedDeviceId)
                completion(matched)
            },
            DispatchQueue(label: "com.linkme.macbridge.tls-verify")
        )

        let parameters = NWParameters(tls: tlsOptions, tcp: NWProtocolTCP.Options())
        parameters.includePeerToPeer = true
        return parameters
    }
}
