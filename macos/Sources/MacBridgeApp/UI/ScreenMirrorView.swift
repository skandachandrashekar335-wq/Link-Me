import AppKit
import MacBridgeCore
import SwiftUI

struct ScreenMirrorView: View {
    @ObservedObject var viewModel: BridgeDashboardViewModel

    private var pal: StudioThemePalette { viewModel.palette }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            // Controls Header
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Live Encrypted Screen Mirror")
                        .font(.system(size: 18, weight: .bold))
                        .foregroundStyle(pal.onSurface)
                    Text(viewModel.mirrorStatusText)
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundStyle(pal.secondaryText)
                }

                Spacer()

                Button {
                    viewModel.toggleViewingPhoneScreen()
                } label: {
                    Label(
                        viewModel.isViewingRemoteScreen ? "Stop Viewing Phone" : "View Phone Screen",
                        systemImage: viewModel.isViewingRemoteScreen ? "stop.circle.fill" : "iphone.and.arrow.forward"
                    )
                    .font(.system(size: 12, weight: .semibold))
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                    .background(Capsule().fill(viewModel.isViewingRemoteScreen ? Color.red : pal.secondaryAccent))
                    .foregroundStyle(viewModel.isViewingRemoteScreen ? Color.white : pal.activeCardInk)
                }
                .buttonStyle(.plain)

                Button {
                    viewModel.toggleSharingMacScreen()
                } label: {
                    Label(
                        viewModel.isSharingLocalScreen ? "Stop Mac Share" : "Share Mac Screen",
                        systemImage: viewModel.isSharingLocalScreen ? "stop.fill" : "macwindow.badge.plus"
                    )
                    .font(.system(size: 12, weight: .semibold))
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                    .background(Capsule().fill(viewModel.isSharingLocalScreen ? Color.red : pal.primaryAccent))
                    .foregroundStyle(Color.white)
                }
                .buttonStyle(.plain)
            }
            .padding(16)
            .liquidGlassCard(pal, cornerRadius: 16)

            // Live Stream Canvas
            VStack(spacing: 10) {
                HStack {
                    HStack(spacing: 6) {
                        Circle()
                            .fill(viewModel.remoteScreenImage != nil || viewModel.isSharingLocalScreen ? pal.secondaryAccent : Color.orange)
                            .frame(width: 7, height: 7)
                        Text(viewModel.remoteScreenImage != nil
                            ? "Receiving Android \(viewModel.remoteScreenSizeText) • \(viewModel.remoteMirrorFps) fps"
                            : (viewModel.isSharingLocalScreen ? "Broadcasting macOS Display to \(viewModel.peerName)" : "Standby"))
                            .font(.system(size: 11, weight: .medium, design: .monospaced))
                    }
                    Spacer()
                    Text(viewModel.bridgeState == .activeWifiStream ? "Wi-Fi mTLS 1.3 (15 fps)" : "BLE Preview Mode (3 fps)")
                        .font(.system(size: 10, design: .monospaced))
                        .foregroundStyle(pal.secondaryText)
                }

                ZStack {
                    RoundedRectangle(cornerRadius: 14)
                        .fill(pal.surfaceLowest)
                        .overlay(RoundedRectangle(cornerRadius: 14).stroke(pal.outlineVariant, lineWidth: 1))

                    if let nsImg = viewModel.remoteScreenImage {
                        Image(nsImage: nsImg)
                            .resizable()
                            .aspectRatio(contentMode: .fit)
                            .clipShape(RoundedRectangle(cornerRadius: 12))
                            .padding(8)
                    } else {
                        VStack(spacing: 12) {
                            Image(systemName: "rectangle.inset.filled.and.person.filled")
                                .font(.system(size: 38, weight: .light))
                                .foregroundStyle(pal.secondaryAccent)
                            Text(viewModel.isSharingLocalScreen
                                ? "Streaming your Mac screen to \(viewModel.peerName)..."
                                : "Tap \"View Phone Screen\" to mirror \(viewModel.peerName), or tap \"Share Mac Screen\" to project your desktop.")
                                .font(.system(size: 12))
                                .foregroundStyle(pal.secondaryText)
                                .multilineTextAlignment(.center)
                                .frame(maxWidth: 420)

                            Button("Open Screen Recording Permissions") {
                                if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture") {
                                    NSWorkspace.shared.open(url)
                                }
                            }
                            .font(.system(size: 11))
                            .buttonStyle(.link)
                        }
                        .padding(28)
                    }
                }
                .frame(maxWidth: .infinity, minHeight: 360)
            }
            .padding(16)
            .liquidGlassCard(pal, cornerRadius: 18)
        }
    }
}
