import AppKit
import MacBridgeCore
import SwiftUI
import UniformTypeIdentifiers

struct StudioThemePalette {
    let surfaceLowest: Color
    let surface: Color
    let surfaceLow: Color
    let surfaceContainer: Color
    let surfaceHigh: Color
    let surfaceHighest: Color
    let primaryAccent: Color
    let secondaryAccent: Color
    let activeCardLight: Color
    let activeCardInk: Color
    let outlineVariant: Color
    let onSurface: Color
    let secondaryText: Color
    let isLight: Bool

    static func color(_ c: BridgeRgba) -> Color {
        Color(red: c.r, green: c.g, blue: c.b, opacity: c.a)
    }

    static func from(_ spec: BridgePaletteSpec) -> StudioThemePalette {
        StudioThemePalette(
            surfaceLowest: color(spec.surfaceLowest),
            surface: color(spec.surface),
            surfaceLow: color(spec.surfaceLow),
            surfaceContainer: color(spec.surfaceContainer),
            surfaceHigh: color(spec.surfaceHigh),
            surfaceHighest: color(spec.surfaceHighest),
            primaryAccent: color(spec.primaryAccent),
            secondaryAccent: color(spec.secondaryAccent),
            activeCardLight: color(spec.activeCardLight),
            activeCardInk: color(spec.activeCardInk),
            outlineVariant: color(spec.outlineVariant),
            onSurface: color(spec.onSurface),
            secondaryText: color(spec.secondaryText),
            isLight: spec.isLight
        )
    }
}

struct LiquidGlassCardModifier: ViewModifier {
    let palette: StudioThemePalette
    var cornerRadius: CGFloat = 18
    var isHighlighted: Bool = false

    func body(content: Content) -> some View {
        content
            .background(
                ZStack {
                    RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                        .fill(palette.isLight ? palette.surfaceLow.opacity(0.86) : palette.surfaceContainer.opacity(0.74))
                    RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                        .fill(palette.isLight ? .regularMaterial : .ultraThinMaterial)
                        .opacity(0.55)
                }
            )
            .overlay(
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .strokeBorder(
                        LinearGradient(
                            colors: [
                                (isHighlighted ? palette.primaryAccent : Color.white).opacity(palette.isLight ? 0.55 : 0.18),
                                (isHighlighted ? palette.primaryAccent : Color.white).opacity(0.05)
                            ],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        ),
                        lineWidth: 1
                    )
            )
            .shadow(color: Color.black.opacity(palette.isLight ? 0.08 : 0.35), radius: 14, y: 6)
    }
}

extension View {
    func liquidGlassCard(_ palette: StudioThemePalette, cornerRadius: CGFloat = 18, highlighted: Bool = false) -> some View {
        modifier(LiquidGlassCardModifier(palette: palette, cornerRadius: cornerRadius, isHighlighted: highlighted))
    }
}

struct MenuBarDashboardView: View {
    @ObservedObject var viewModel: BridgeDashboardViewModel
    @Environment(\.openWindow) private var openWindow

    private var pal: StudioThemePalette { viewModel.palette }

    var body: some View {
        VStack(spacing: 12) {
            if let banner = viewModel.statusBanner {
                HStack {
                    Text(banner)
                        .font(.system(size: 10, weight: .medium, design: .monospaced))
                        .foregroundStyle(pal.secondaryAccent)
                        .lineLimit(1)
                    Spacer()
                    Button {
                        viewModel.statusBanner = nil
                    } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 9, weight: .bold))
                            .foregroundStyle(pal.secondaryText)
                    }
                    .buttonStyle(.plain)
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .liquidGlassCard(pal, cornerRadius: 10)
            }

            // Paired Phone Card (matches macos_menu_bar_popover)
            HStack(spacing: 12) {
                ZStack {
                    Circle()
                        .stroke(pal.surfaceHigh, lineWidth: 3)
                    Circle()
                        .trim(from: 0, to: viewModel.peerBatteryFraction)
                        .stroke(pal.secondaryAccent, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                        .rotationEffect(.degrees(-90))
                    Image(systemName: "iphone")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(pal.activeCardLight)
                }
                .frame(width: 38, height: 38)

                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 6) {
                        Text(viewModel.peerName)
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(pal.onSurface)
                            .lineLimit(1)

                        Text(viewModel.bridgeState == .activeWifiStream ? "TLS 1.3 LAN" : "BLE E2EE")
                            .font(.system(size: 9, weight: .semibold, design: .monospaced))
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(Capsule().fill(pal.surfaceHigh))
                            .foregroundStyle(pal.secondaryText)
                    }
                    let latLabel = viewModel.latencyMs.map { "\($0)ms" } ?? "--"
                    let modeLabel = viewModel.bridgeState == .activeWifiStream ? "Wi-Fi Turbo" : "Bluetooth E2EE"
                    Text("\(viewModel.peerBatteryLabel) • \(modeLabel) • \(latLabel)")
                        .font(.system(size: 10, design: .monospaced))
                        .foregroundStyle(pal.secondaryText)
                }

                Spacer()

                HStack(spacing: 5) {
                    Circle()
                        .fill(viewModel.peerPaired ? pal.secondaryAccent : Color.orange)
                        .frame(width: 6, height: 6)
                    Text(viewModel.isBluetoothConnected || viewModel.bridgeState == .activeWifiStream ? "Connected" : (viewModel.peerPaired ? "Standby" : "Unpaired"))
                        .font(.system(size: 10, weight: .semibold))
                        .foregroundStyle(viewModel.peerPaired ? pal.secondaryAccent : pal.onSurface)
                }
                .padding(.horizontal, 9)
                .padding(.vertical, 5)
                .background(Capsule().fill(pal.secondaryAccent.opacity(viewModel.peerPaired ? 0.16 : 0.08)))
            }
            .padding(12)
            .liquidGlassCard(pal, cornerRadius: 16)

            // Clipboard Sync Toggle Row
            HStack(spacing: 10) {
                Image(systemName: "doc.on.clipboard")
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(pal.activeCardLight)
                    .frame(width: 30, height: 30)
                    .background(RoundedRectangle(cornerRadius: 8).fill(pal.surfaceHigh))

                VStack(alignment: .leading, spacing: 2) {
                    Text("Clipboard Sync")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(pal.onSurface)
                    Text("Auto-syncing \(viewModel.preferences.clipboardDirection.displayName)")
                        .font(.system(size: 10))
                        .foregroundStyle(pal.secondaryText)
                }
                Spacer()
                Toggle("", isOn: Binding(
                    get: { viewModel.clipboardSyncEnabled },
                    set: { viewModel.toggleClipboardSync($0) }
                ))
                .labelsHidden()
                .toggleStyle(.switch)
                .tint(pal.primaryAccent)
            }
            .padding(12)
            .liquidGlassCard(pal, cornerRadius: 14)

            // Quick File Transfer Drop Card
            Button {
                viewModel.openFilePickerAndSend()
            } label: {
                HStack(spacing: 10) {
                    Image(systemName: "arrow.up.doc")
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(viewModel.isDropTargeted ? pal.primaryAccent : pal.activeCardLight)
                        .frame(width: 30, height: 30)
                        .background(RoundedRectangle(cornerRadius: 8).fill(pal.surfaceHigh))

                    VStack(alignment: .leading, spacing: 2) {
                        Text("Quick File Transfer")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(pal.onSurface)
                        Text(viewModel.activeTransfer?.filename ?? "Drop file or click to beam")
                            .font(.system(size: 10))
                            .foregroundStyle(pal.secondaryText)
                            .lineLimit(1)
                    }
                    Spacer()
                    Text("Send")
                        .font(.system(size: 11, weight: .semibold))
                        .padding(.horizontal, 12)
                        .padding(.vertical, 5)
                        .background(Capsule().fill(pal.surfaceHigh))
                        .foregroundStyle(pal.onSurface)
                }
                .padding(12)
                .liquidGlassCard(pal, cornerRadius: 14, highlighted: viewModel.isDropTargeted)
            }
            .buttonStyle(.plain)
            .onDrop(of: [.fileURL], isTargeted: $viewModel.isDropTargeted) { providers in
                guard let provider = providers.first else { return false }
                _ = provider.loadObject(ofClass: URL.self) { url, _ in
                    guard let url else { return }
                    Task { @MainActor in
                        viewModel.sendFile(url: url)
                    }
                }
                return true
            }

            // Now Playing Media Deck
            VStack(spacing: 10) {
                HStack {
                    Image(systemName: "music.note.list")
                        .font(.system(size: 13))
                        .foregroundStyle(pal.activeCardLight)
                        .frame(width: 28, height: 28)
                        .background(RoundedRectangle(cornerRadius: 8).fill(pal.surfaceHigh))
                    VStack(alignment: .leading, spacing: 1) {
                        Text("Remote Media Deck")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(pal.onSurface)
                        Text("\(viewModel.peerName) Audio")
                            .font(.system(size: 10))
                            .foregroundStyle(pal.secondaryText)
                    }
                    Spacer()
                    Image(systemName: "waveform")
                        .font(.system(size: 12))
                        .foregroundStyle(pal.primaryAccent)
                }
                HStack(spacing: 8) {
                    popoverMediaBtn("backward.fill", .prev)
                    Button {
                        viewModel.sendMediaAction(.playPause)
                    } label: {
                        Image(systemName: "playpause.fill")
                            .font(.system(size: 13, weight: .bold))
                            .frame(width: 34, height: 34)
                            .background(Circle().fill(pal.activeCardLight))
                            .foregroundStyle(pal.activeCardInk)
                    }
                    .buttonStyle(.plain)
                    popoverMediaBtn("forward.fill", .next)
                    Spacer()
                    popoverMediaBtn("speaker.wave.1.fill", .volDown)
                    popoverMediaBtn("speaker.slash.fill", .mute)
                    popoverMediaBtn("speaker.wave.3.fill", .volUp)
                }
            }
            .padding(12)
            .liquidGlassCard(pal, cornerRadius: 14)

            // 3 Quick Tile Actions: Ring Phone / Send Link / Show Pairing QR
            HStack(spacing: 8) {
                quickTile(icon: "bell.and.waves.left.and.right", title: "Ring Phone") {
                    viewModel.ringMyPhone()
                }
                quickTile(icon: "link", title: "Sync Clip") {
                    viewModel.syncClipboardNow()
                }
                quickTile(icon: "qrcode", title: "Show QR") {
                    viewModel.startPairingFlow(inStudioWindow: false)
                }
            }

            if let latestNotif = viewModel.recentNotifications.first {
                ReplyableNotificationCard(item: latestNotif, pal: pal) { replyText in
                    viewModel.sendNotificationReply(sbnKey: latestNotif.id, replyText: replyText)
                }
            }

            // Bottom Primary CTA + Quick Theme Toggle
            HStack(spacing: 8) {
                Button {
                    openWindow(id: "studio")
                    NSApp.activate(ignoringOtherApps: true)
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "macwindow.on.rectangle")
                        Text("Open Link-Me Studio")
                    }
                    .font(.system(size: 13, weight: .semibold))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 10)
                    .background(Capsule().fill(pal.primaryAccent))
                    .foregroundStyle(Color.white)
                }
                .buttonStyle(.plain)

                Button {
                    viewModel.preferences.accentTheme = viewModel.preferences.accentTheme == .alabaster ? .liquidBlue : .alabaster
                } label: {
                    Image(systemName: pal.isLight ? "moon.fill" : "sun.max.fill")
                        .font(.system(size: 13))
                        .frame(width: 36, height: 36)
                        .background(Circle().fill(pal.surfaceHigh))
                        .foregroundStyle(pal.onSurface)
                }
                .buttonStyle(.plain)
            }

            HStack {
                Text(viewModel.bridgeState == .activeWifiStream ? "Secured: Wi-Fi mTLS 1.3" : "Secured: Bluetooth E2EE")
                Spacer()
                Text(viewModel.peerPaired ? "● Peer Verified" : "○ Scan QR to Pair")
                    .foregroundStyle(viewModel.peerPaired ? pal.secondaryAccent : pal.secondaryText)
            }
            .font(.system(size: 10, design: .monospaced))
            .foregroundStyle(pal.secondaryText)
        }
        .padding(14)
        .frame(width: 420)
        .background(
            LinearGradient(
                colors: [pal.surface, pal.surfaceLowest],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
        )
        .foregroundStyle(pal.onSurface)
        .sheet(isPresented: $viewModel.isShowingPairingSheet) {
            PairingView(viewModel: viewModel)
        }
    }

    private func popoverMediaBtn(_ symbol: String, _ action: MediaControlAction) -> some View {
        Button {
            viewModel.sendMediaAction(action)
        } label: {
            Image(systemName: symbol)
                .font(.system(size: 12, weight: .medium))
                .frame(width: 28, height: 28)
                .background(Circle().fill(pal.surfaceHigh.opacity(0.8)))
                .foregroundStyle(pal.onSurface)
        }
        .buttonStyle(.plain)
    }

    private func quickTile(icon: String, title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 5) {
                Image(systemName: icon)
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(pal.onSurface)
                Text(title)
                    .font(.system(size: 10, weight: .medium))
                    .foregroundStyle(pal.secondaryText)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 10)
            .liquidGlassCard(pal, cornerRadius: 12)
        }
        .buttonStyle(.plain)
    }
}

struct ReplyableNotificationCard: View {
    let item: MirroredNotificationItem
    let pal: StudioThemePalette
    let onSendReply: (String) -> Void
    @StateObject private var replyState = InlineReplyBoxModel()

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("\(item.appName) • \(item.title)")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(pal.onSurface)
                    .lineLimit(1)
                Spacer()
                Text("Android")
                    .font(.system(size: 9, design: .monospaced))
                    .foregroundStyle(pal.secondaryText)
            }

            Text(item.body)
                .font(.system(size: 12))
                .foregroundStyle(pal.secondaryText)
                .lineLimit(2)

            if item.canReply {
                HStack(spacing: 6) {
                    TextField("Reply inline...", text: $replyState.text)
                        .textFieldStyle(.plain)
                        .font(.system(size: 11))
                        .foregroundStyle(pal.onSurface)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(RoundedRectangle(cornerRadius: 8).fill(pal.surfaceLowest))
                        .overlay(RoundedRectangle(cornerRadius: 8).stroke(pal.outlineVariant, lineWidth: 1))

                    Button("Send") {
                        onSendReply(replyState.text)
                        replyState.text = ""
                    }
                    .font(.system(size: 11, weight: .semibold))
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(Capsule().fill(pal.primaryAccent))
                    .foregroundStyle(Color.white)
                    .buttonStyle(.plain)
                }
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .liquidGlassCard(pal, cornerRadius: 14)
    }
}

@MainActor
private final class InlineReplyBoxModel: ObservableObject {
    @Published var text: String = ""
}
