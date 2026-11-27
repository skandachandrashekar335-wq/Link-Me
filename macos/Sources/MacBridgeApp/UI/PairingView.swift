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
            Text("CURVE25519 // QR PAIRING")
                .font(.system(size: 11, weight: .bold, design: .monospaced))
                .foregroundStyle(StitchTokens.primaryCrimson)

            Text("Pair Android Device")
                .font(.headline)
                .foregroundStyle(StitchTokens.onSurface)

            if let nsImage = qrNSImage(from: viewModel.pairingUriString) {
                Image(nsImage: nsImage)
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 184, height: 184)
                    .padding(10)
                    .background(RoundedRectangle(cornerRadius: 10).fill(Color.white))
                    .overlay(RoundedRectangle(cornerRadius: 10).stroke(StitchTokens.outlineVariant, lineWidth: 1))
            } else {
                RoundedRectangle(cornerRadius: 10)
                    .fill(StitchTokens.surfaceContainer)
                    .frame(width: 184, height: 184)
                    .overlay {
                        Image(systemName: "qrcode")
                            .font(.largeTitle)
                            .foregroundStyle(StitchTokens.secondaryText)
                    }
            }

            Text("EXPIRES IN \(formattedCountdown)")
                .font(.system(.caption, design: .monospaced))
                .foregroundStyle(StitchTokens.secondaryText)

            if let approval = viewModel.pendingApproval {
                VStack(spacing: 6) {
                    Text("Incoming peer: \(approval.peerDeviceName)")
                        .font(.subheadline.weight(.medium))
                    Text(approval.sasCode)
                        .font(.system(.title, design: .monospaced).bold())
                        .tracking(4)
                        .foregroundStyle(StitchTokens.tertiaryMint)
                }
                .padding(10)
                .frame(maxWidth: .infinity)
                .background(
                    RoundedRectangle(cornerRadius: 8)
                        .fill(StitchTokens.surfaceContainer)
                        .overlay(RoundedRectangle(cornerRadius: 8).stroke(StitchTokens.primaryCrimson, lineWidth: 1))
                )
                HStack(spacing: 12) {
                    Button("Reject", role: .destructive) {
                        viewModel.rejectPairing()
                    }
                    Button("Confirm & Trust") {
                        viewModel.confirmPairing()
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(StitchTokens.primaryCrimson)
                }
            } else {
                Text("Scan with AndroidBridge to verify 6-digit SAS")
                    .font(.caption)
                    .foregroundStyle(StitchTokens.secondaryText)
                Button("Close") {
                    viewModel.rejectPairing()
                }
                .buttonStyle(.bordered)
            }
        }
        .padding(20)
        .frame(width: 340)
        .background(StitchTokens.surface)
        .foregroundStyle(StitchTokens.onSurface)
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
