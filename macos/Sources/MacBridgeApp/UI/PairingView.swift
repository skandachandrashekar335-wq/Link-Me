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
            Text("CURVE25519 // LIQUID CONTINUITY PAIRING")
                .font(.system(size: 10, weight: .bold, design: .monospaced))
                .foregroundStyle(pal.primaryAccent)

            Text("Pair Android Device")
                .font(.system(size: 18, weight: .bold))
                .foregroundStyle(pal.onSurface)

            if let nsImage = Self.makeQRNSImage(from: viewModel.pairingUriString) {
                Image(nsImage: nsImage)
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 196, height: 196)
                    .padding(12)
                    .background(RoundedRectangle(cornerRadius: 16).fill(Color.white))
                    .overlay(RoundedRectangle(cornerRadius: 16).stroke(pal.outlineVariant, lineWidth: 1))
            } else {
                RoundedRectangle(cornerRadius: 16)
                    .fill(pal.surfaceContainer)
                    .frame(width: 196, height: 196)
                    .overlay {
                        Image(systemName: "qrcode")
                            .font(.largeTitle)
                            .foregroundStyle(pal.secondaryText)
                    }
            }

            Text("EXPIRES IN \(formattedCountdown) • BLE + LAN")
                .font(.system(size: 11, design: .monospaced))
                .foregroundStyle(pal.secondaryText)

            if let approval = viewModel.pendingApproval {
                VStack(spacing: 6) {
                    Text("Incoming peer: \(approval.peerDeviceName)")
                        .font(.system(size: 13, weight: .medium))
                    Text(approval.sasCode)
                        .font(.system(size: 28, weight: .bold, design: .monospaced))
                        .tracking(4)
                        .foregroundStyle(pal.secondaryAccent)
                }
                .padding(12)
                .frame(maxWidth: .infinity)
                .liquidGlassCard(pal, cornerRadius: 14, highlighted: true)

                HStack(spacing: 12) {
                    Button("Reject", role: .destructive) {
                        viewModel.rejectPairing()
                    }
                    .buttonStyle(.bordered)
                    Button("Confirm & Trust") {
                        viewModel.confirmPairing()
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(pal.primaryAccent)
                }
            } else {
                Text("Scan with AndroidBridge CameraX QR Scanner")
                    .font(.system(size: 12))
                    .foregroundStyle(pal.secondaryText)
                HStack(spacing: 10) {
                    Button("Copy Pairing URI") {
                        let pb = NSPasteboard.general
                        pb.clearContents()
                        pb.setString(viewModel.pairingUriString, forType: .string)
                        viewModel.statusBanner = "Copied linkme://pair URI"
                    }
                    .buttonStyle(.bordered)
                    Button("Close") {
                        viewModel.rejectPairing()
                    }
                    .buttonStyle(.bordered)
                }
            }
        }
        .padding(22)
        .frame(width: 380)
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

    static func makeQRNSImage(from uri: String) -> NSImage? {
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
