import AppKit
import Combine
import CoreImage
import MacBridgeCore
import SwiftUI

struct PairingView: View {
    @ObservedObject var viewModel: BridgeDashboardViewModel

    private let countdownTimer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()
    private var pal: StudioThemePalette { viewModel.palette }

    var body: some View {
        VStack(spacing: 14) {
            Text("CURVE25519 // QR PAIRING")
                .font(.system(size: 11, weight: .bold, design: .monospaced))
                .foregroundStyle(pal.primaryAccent)

            Text("Pair Android Device")
                .font(.headline)
                .foregroundStyle(pal.onSurface)

            if let nsImage = qrNSImage(from: viewModel.pairingUriString) {
                Image(nsImage: nsImage)
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 184, height: 184)
                    .padding(10)
                    .background(RoundedRectangle(cornerRadius: 10).fill(Color.white))
                    .overlay(RoundedRectangle(cornerRadius: 10).stroke(pal.outlineVariant, lineWidth: 1))
            } else {
                RoundedRectangle(cornerRadius: 10)
                    .fill(pal.surfaceContainer)
                    .frame(width: 184, height: 184)
                    .overlay {
                        Image(systemName: "qrcode")
                            .font(.largeTitle)
                            .foregroundStyle(pal.secondaryText)
                    }
            }

            Text("EXPIRES IN \(formattedCountdown)")
                .font(.system(.caption, design: .monospaced))
                .foregroundStyle(pal.secondaryText)

            if let approval = viewModel.pendingApproval {
                VStack(spacing: 6) {
                    Text("Incoming peer: \(approval.peerDeviceName)")
                        .font(.subheadline.weight(.medium))
                    Text(approval.sasCode)
                        .font(.system(.title, design: .monospaced).bold())
                        .tracking(4)
                        .foregroundStyle(pal.secondaryAccent)
                }
                .padding(10)
                .frame(maxWidth: .infinity)
                .background(
                    RoundedRectangle(cornerRadius: 8)
                        .fill(pal.surfaceContainer)
                        .overlay(RoundedRectangle(cornerRadius: 8).stroke(pal.primaryAccent, lineWidth: 1))
                )
                HStack(spacing: 12) {
                    Button("Reject", role: .destructive) {
                        viewModel.rejectPairing()
                    }
                    Button("Confirm & Trust") {
                        viewModel.confirmPairing()
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(pal.primaryAccent)
                }
            } else {
                Text("Scan with AndroidBridge to verify 6-digit SAS")
                    .font(.caption)
                    .foregroundStyle(pal.secondaryText)
                Button("Close") {
                    viewModel.rejectPairing()
                }
                .buttonStyle(.bordered)
            }
        }
        .padding(20)
        .frame(width: 340)
        .background(pal.surface)
        .foregroundStyle(pal.onSurface)
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
