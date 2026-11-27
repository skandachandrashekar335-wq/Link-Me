import AppKit
import Combine
import CoreImage
import MacBridgeCore
import SwiftUI

struct PairingView: View {
    @ObservedObject var viewModel: BridgeDashboardViewModel

    private let countdownTimer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        VStack(spacing: 14) {
            Text("Pair Android Device")
                .font(.headline)

            if let nsImage = qrNSImage(from: viewModel.pairingUriString) {
                Image(nsImage: nsImage)
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 180, height: 180)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
            } else {
                RoundedRectangle(cornerRadius: 8)
                    .fill(.quaternary)
                    .frame(width: 180, height: 180)
                    .overlay {
                        Image(systemName: "qrcode")
                            .font(.largeTitle)
                    }
            }

            Text("QR expires in \(formattedCountdown)")
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)

            if let approval = viewModel.pendingApproval {
                VStack(spacing: 6) {
                    Text("Incoming peer: \(approval.peerDeviceName)")
                        .font(.subheadline.weight(.medium))
                    Text(approval.sasCode)
                        .font(.system(.title, design: .monospaced).bold())
                        .tracking(4)
                }
                HStack(spacing: 12) {
                    Button("Reject", role: .destructive) {
                        viewModel.rejectPairing()
                    }
                    Button("Confirm & Trust") {
                        viewModel.confirmPairing()
                    }
                    .buttonStyle(.borderedProminent)
                }
            } else {
                Text("Scan with AndroidBridge to verify 6-digit SAS")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Button("Close") {
                    viewModel.rejectPairing()
                }
            }
        }
        .padding(20)
        .frame(width: 320)
        .onReceive(countdownTimer) { now in
            viewModel.pairingRemainingSeconds = max(0, Int(viewModel.pairingExpiresAt.timeIntervalSince(now)))
        }
    }

    private var formattedCountdown: String {
        let mins = viewModel.pairingRemainingSeconds / 60
        let secs = viewModel.pairingRemainingSeconds % 60
        return String(format: "%d:%02d", mins, secs)
    }

    private func qrNSImage(from uri: String) -> NSImage? {
        guard !uri.isEmpty,
              let ciImage = PairingFeature.generateQRCodeCIImage(from: uri) else {
            return nil
        }
        let rep = NSCIImageRep(ciImage: ciImage)
        let image = NSImage(size: rep.size)
        image.addRepresentation(rep)
        return image
    }
}
