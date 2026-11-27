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

enum StitchTokens {
    static let surfaceLowest = Color(red: 0x0D/255.0, green: 0x0E/255.0, blue: 0x10/255.0)
    static let surface = Color(red: 0x12/255.0, green: 0x13/255.0, blue: 0x15/255.0)
    static let surfaceLow = Color(red: 0x1B/255.0, green: 0x1C/255.0, blue: 0x1E/255.0)
    static let surfaceContainer = Color(red: 0x1F/255.0, green: 0x20/255.0, blue: 0x22/255.0)
    static let surfaceHigh = Color(red: 0x29/255.0, green: 0x2A/255.0, blue: 0x2C/255.0)
    static let surfaceHighest = Color(red: 0x34/255.0, green: 0x35/255.0, blue: 0x37/255.0)
    static let primaryCrimson = Color(red: 0xE1/255.0, green: 0x1D/255.0, blue: 0x48/255.0)
    static let tertiaryMint = Color(red: 0x74/255.0, green: 0xD8/255.0, blue: 0xBD/255.0)
    static let activeCardLight = Color(red: 0xE4/255.0, green: 0xE2/255.0, blue: 0xDE/255.0)
    static let activeCardInk = Color(red: 0x1B/255.0, green: 0x1C/255.0, blue: 0x1A/255.0)
    static let outlineVariant = Color(red: 0x5C/255.0, green: 0x3F/255.0, blue: 0x40/255.0)
    static let onSurface = Color(red: 0xE3/255.0, green: 0xE2/255.0, blue: 0xE4/255.0)
    static let secondaryText = Color(red: 0xC8/255.0, green: 0xC6/255.0, blue: 0xC3/255.0)
}

struct MenuBarDashboardView: View {
    @ObservedObject var viewModel: BridgeDashboardViewModel
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        BridgeStudioContentView(
            viewModel: viewModel,
            isStandaloneWindow: false,
            onExpandWindow: { openWindow(id: "studio") }
        )
        .frame(width: 420, height: 560)
    }
}

struct BridgeStudioContentView: View {
    @ObservedObject var viewModel: BridgeDashboardViewModel
    let isStandaloneWindow: Bool
    let onExpandWindow: (() -> Void)?

    private var pal: StudioThemePalette { viewModel.palette }
    private var spacing: CGFloat { viewModel.preferences.compactMode ? 8 : 12 }

    var body: some View {
        VStack(spacing: 0) {
            // Segmented Top Navigation Bar + Optional Expand Studio Window Button
            HStack(spacing: 8) {
                HStack(spacing: 4) {
                    ForEach(StudioTab.allCases, id: \.self) { tab in
                        let selected = viewModel.selectedTab == tab
                        Button {
                            viewModel.selectedTab = tab
                        } label: {
                            Text(tab.rawValue)
                                .font(.system(size: 11, weight: .semibold))
                                .padding(.horizontal, 12)
                                .padding(.vertical, 6)
                                .background(
                                    RoundedRectangle(cornerRadius: 7)
                                        .fill(selected ? pal.primaryAccent : Color.clear)
                                )
                                .foregroundStyle(selected ? Color.white : pal.secondaryText)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(3)
                .background(RoundedRectangle(cornerRadius: 9).fill(pal.surfaceLowest))
                .overlay(RoundedRectangle(cornerRadius: 9).stroke(pal.outlineVariant, lineWidth: 1))

                Spacer()

                if !isStandaloneWindow, let onExpandWindow {
                    Button {
                        onExpandWindow()
                        NSApp.activate(ignoringOtherApps: true)
                    } label: {
                        Label("Studio", systemImage: "arrow.up.left.and.arrow.down.right")
                            .font(.system(size: 10, weight: .semibold))
                            .padding(.horizontal, 8)
                            .padding(.vertical, 6)
                            .background(RoundedRectangle(cornerRadius: 7).fill(pal.surfaceHigh))
                            .foregroundStyle(pal.onSurface)
                    }
                    .buttonStyle(.plain)
                    .help("Expand Studio Window")
                }
            }
            .padding(.horizontal, 14)
            .padding(.top, 12)
            .padding(.bottom, 8)

            ScrollView {
                VStack(alignment: .leading, spacing: spacing) {
                    if let banner = viewModel.statusBanner {
                        HStack {
                            Text(banner)
                                .font(.system(size: 10, weight: .medium, design: .monospaced))
                                .foregroundStyle(pal.secondaryAccent)
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
                        .background(RoundedRectangle(cornerRadius: 8).fill(pal.surfaceLow))
                        .overlay(RoundedRectangle(cornerRadius: 8).stroke(pal.outlineVariant, lineWidth: 1))
                    }

                    switch viewModel.selectedTab {
                    case .bridge:
                        bridgeTabContent
                    case .activity:
                        activityTabContent
                    case .customize:
                        customizeTabContent
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
            }

            if viewModel.preferences.showTelemetryFooter {
                bottomTelemetryBar
            }
        }
        .background(pal.surface)
        .foregroundStyle(pal.onSurface)
        .sheet(isPresented: $viewModel.isShowingPairingSheet) {
            PairingView(viewModel: viewModel)
        }
    }

    // MARK: - Tab 1: Bridge

    @ViewBuilder
    private var bridgeTabContent: some View {
        headerSection

        if let pending = viewModel.pendingIncomingOffer {
            pendingOfferPrompt(pending)
        }

        beamDropzoneCard

        if let transfer = viewModel.activeTransfer {
            activeTransferCard(transfer)
        }

        mediaDeckCard
        quickUrlBeamCard
        featureSwitchesSection
        quickControlsStrip
        notificationsSection
    }

    private var headerSection: some View {
        HStack(spacing: 12) {
            ZStack {
                Circle()
                    .stroke(pal.surfaceHigh, lineWidth: 3.5)
                Circle()
                    .trim(from: 0, to: CGFloat(max(0, min(100, viewModel.peerBatteryPercent))) / 100.0)
                    .stroke(
                        pal.secondaryAccent,
                        style: StrokeStyle(lineWidth: 3.5, lineCap: .round)
                    )
                    .rotationEffect(.degrees(-90))
                Image(systemName: viewModel.peerIsCharging ? "bolt.fill" : "battery.100")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(pal.secondaryAccent)
            }
            .frame(width: 40, height: 40)

            VStack(alignment: .leading, spacing: 3) {
                Text(viewModel.peerName)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(pal.onSurface)
                    .lineLimit(1)

                let chargeTag = viewModel.peerIsCharging ? "CHARGING" : "BATTERY"
                let pairTag = viewModel.peerPaired ? "PAIRED" : "UNPAIRED"
                Text("\(viewModel.peerBatteryPercent)% \(chargeTag) • \(pairTag) • \(viewModel.preferences.normalizedAlias.uppercased())")
                    .font(.system(size: 10, weight: .semibold, design: .monospaced))
                    .tracking(0.5)
                    .foregroundStyle(pal.secondaryText)
                    .lineLimit(1)
            }

            Spacer()

            let isTurbo = viewModel.bridgeState == .activeWifiStream
            HStack(spacing: 5) {
                Circle()
                    .fill(isTurbo ? pal.secondaryAccent : pal.primaryAccent)
                    .frame(width: 6, height: 6)
                Text(viewModel.preferences.bleOnlyBatterySaver ? "BLE ECO LOCK" : (isTurbo ? "WI-FI TURBO 5GHZ" : "BLE STANDBY"))
                    .font(.system(size: 10, weight: .bold, design: .monospaced))
                    .tracking(0.6)
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 5)
            .background(Capsule().fill(pal.surfaceHigh))
            .overlay(
                Capsule().stroke(isTurbo ? pal.secondaryAccent : pal.outlineVariant, lineWidth: 1)
            )
            .foregroundStyle(isTurbo ? pal.secondaryAccent : pal.onSurface)
        }
        .padding(viewModel.preferences.compactMode ? 8 : 11)
        .background(
            RoundedRectangle(cornerRadius: 10)
                .fill(pal.surfaceLow)
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(pal.outlineVariant, lineWidth: 1))
        )
    }

    private func pendingOfferPrompt(_ pending: PendingIncomingOffer) -> some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                Text("INCOMING FILE OFFER")
                    .font(.system(size: 10, weight: .bold, design: .monospaced))
                    .foregroundStyle(pal.primaryAccent)
                Text("\(pending.offer.filename) (\(pending.offer.byteSize / 1024) KB)")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(pal.onSurface)
                    .lineLimit(1)
            }
            Spacer()
            Button("Decline") {
                viewModel.resolvePendingIncomingOffer(accepted: false)
            }
            .buttonStyle(.bordered)
            Button("Accept") {
                viewModel.resolvePendingIncomingOffer(accepted: true)
            }
            .buttonStyle(.borderedProminent)
            .tint(pal.primaryAccent)
        }
        .padding(10)
        .background(
            RoundedRectangle(cornerRadius: 10)
                .fill(pal.surfaceContainer)
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(pal.primaryAccent, lineWidth: 1))
        )
    }

    private var beamDropzoneCard: some View {
        Button {
            viewModel.openFilePickerAndSend()
        } label: {
            VStack(spacing: 6) {
                Image(systemName: "arrow.up.doc.on.clipboard")
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(viewModel.isDropTargeted ? pal.primaryAccent : pal.secondaryAccent)
                    .frame(width: 34, height: 34)
                    .background(RoundedRectangle(cornerRadius: 8).fill(pal.surfaceHigh))
                Text("Drop files to beam to Android")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(pal.onSurface)
                Text("MAX 4.0 GB • DIRECT P2P")
                    .font(.system(size: 10, weight: .medium, design: .monospaced))
                    .tracking(0.6)
                    .foregroundStyle(pal.secondaryText)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, viewModel.preferences.compactMode ? 10 : 14)
            .background(
                RoundedRectangle(cornerRadius: 10)
                    .fill(viewModel.isDropTargeted ? pal.primaryAccent.opacity(0.12) : pal.surfaceLowest)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 10)
                    .strokeBorder(
                        viewModel.isDropTargeted ? pal.primaryAccent : pal.outlineVariant,
                        style: StrokeStyle(lineWidth: 1, dash: [5, 4])
                    )
            )
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
    }

    private func activeTransferCard(_ transfer: FileTransferProgress) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(transfer.filename)
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(pal.onSurface)
                    .lineLimit(1)
                Spacer()
                Button("CANCEL") {
                    viewModel.cancelActiveTransfer()
                }
                .font(.system(size: 10, weight: .bold, design: .monospaced))
                .foregroundStyle(pal.primaryAccent)
                .buttonStyle(.plain)
            }
            ProgressView(value: transfer.fractionCompleted)
                .tint(pal.primaryAccent)
            HStack {
                Text("\(transfer.bytesTransferred / 1024) / \(transfer.totalBytes / 1024) KB")
                Spacer()
                Text(String(format: "%.1f MB/s", transfer.bytesPerSecond / 1_048_576))
            }
            .font(.system(size: 10, design: .monospaced))
            .foregroundStyle(pal.secondaryText)
        }
        .padding(10)
        .background(
            RoundedRectangle(cornerRadius: 10)
                .fill(pal.surfaceContainer)
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(pal.outlineVariant, lineWidth: 1))
        )
    }

    private var mediaDeckCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("REMOTE MEDIA DECK")
                .font(.system(size: 10, weight: .bold, design: .monospaced))
                .tracking(0.6)
                .foregroundStyle(pal.secondaryText)

            HStack(spacing: 6) {
                mediaButton(symbol: "backward.fill", action: .prev, label: "Prev")
                mediaButton(symbol: "playpause.fill", action: .playPause, label: "Play", highlighted: true)
                mediaButton(symbol: "forward.fill", action: .next, label: "Next")
                mediaButton(symbol: "speaker.wave.1.fill", action: .volDown, label: "Vol-")
                mediaButton(symbol: "speaker.slash.fill", action: .mute, label: "Mute")
                mediaButton(symbol: "speaker.wave.3.fill", action: .volUp, label: "Vol+")
            }
        }
        .padding(10)
        .background(
            RoundedRectangle(cornerRadius: 10)
                .fill(pal.surfaceContainer)
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(pal.outlineVariant, lineWidth: 1))
        )
    }

    private func mediaButton(symbol: String, action: MediaControlAction, label: String, highlighted: Bool = false) -> some View {
        Button {
            viewModel.sendMediaAction(action)
        } label: {
            Image(systemName: symbol)
                .font(.system(size: 12, weight: .semibold))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 7)
                .background(
                    RoundedRectangle(cornerRadius: 7)
                        .fill(highlighted ? pal.primaryAccent : pal.surfaceHigh)
                )
                .foregroundStyle(highlighted ? Color.white : pal.onSurface)
        }
        .buttonStyle(.plain)
        .help(label)
    }

    private var quickUrlBeamCard: some View {
        HStack(spacing: 8) {
            TextField("https://example.com", text: $viewModel.urlDraft)
                .textFieldStyle(.plain)
                .font(.system(size: 11, design: .monospaced))
                .foregroundStyle(pal.onSurface)
                .padding(.horizontal, 9)
                .padding(.vertical, 7)
                .background(RoundedRectangle(cornerRadius: 7).fill(pal.surfaceLowest))
                .overlay(RoundedRectangle(cornerRadius: 7).stroke(pal.outlineVariant, lineWidth: 1))

            Button("Send URL") {
                viewModel.sendUrlToAndroid()
            }
            .font(.system(size: 11, weight: .semibold))
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(RoundedRectangle(cornerRadius: 7).fill(pal.primaryAccent))
            .foregroundStyle(Color.white)
            .buttonStyle(.plain)
        }
        .padding(10)
        .background(
            RoundedRectangle(cornerRadius: 10)
                .fill(pal.surfaceContainer)
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(pal.outlineVariant, lineWidth: 1))
        )
    }

    private var featureSwitchesSection: some View {
        VStack(spacing: 8) {
            stitchToggleCard(
                title: "Universal Clipboard Sync",
                subtitle: viewModel.preferences.clipboardDirection.displayName,
                isOn: Binding(
                    get: { viewModel.clipboardSyncEnabled },
                    set: { viewModel.toggleClipboardSync($0) }
                )
            )
            stitchToggleCard(
                title: "Notification Mirroring",
                subtitle: "Interactive replies",
                isOn: Binding(
                    get: { viewModel.notificationMirrorEnabled },
                    set: { viewModel.toggleNotificationMirror($0) }
                )
            )
        }
    }

    private func stitchToggleCard(title: String, subtitle: String, isOn: Binding<Bool>) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(pal.onSurface)
                Text(subtitle)
                    .font(.system(size: 10, design: .monospaced))
                    .foregroundStyle(pal.secondaryText)
            }
            Spacer()
            Toggle("", isOn: isOn)
                .labelsHidden()
                .toggleStyle(.switch)
                .tint(pal.primaryAccent)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, viewModel.preferences.compactMode ? 7 : 10)
        .background(
            RoundedRectangle(cornerRadius: 10)
                .fill(pal.surfaceContainer)
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(pal.outlineVariant, lineWidth: 1))
        )
    }

    private var quickControlsStrip: some View {
        HStack(spacing: 8) {
            Button {
                viewModel.syncClipboardNow()
            } label: {
                Label("Sync Clip", systemImage: "doc.on.clipboard")
                    .font(.system(size: 11, weight: .medium))
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .tint(pal.surfaceHighest)

            Button {
                viewModel.ringMyPhone()
            } label: {
                Label("Ring", systemImage: "bell.and.waves.left.and.right")
                    .font(.system(size: 11, weight: .medium))
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .tint(pal.surfaceHighest)

            Button {
                viewModel.startPairingFlow()
            } label: {
                Label("Pair QR", systemImage: "qrcode")
                    .font(.system(size: 11, weight: .medium))
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .tint(pal.primaryAccent)
        }
    }

    private var notificationsSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("RECENT NOTIFICATIONS")
                .font(.system(size: 10, weight: .bold, design: .monospaced))
                .tracking(0.6)
                .foregroundStyle(pal.secondaryText)

            if viewModel.recentNotifications.isEmpty {
                Text("No mirrored notifications yet")
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(pal.secondaryText.opacity(0.75))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(10)
                    .background(
                        RoundedRectangle(cornerRadius: 10)
                            .fill(pal.surfaceContainer)
                            .overlay(RoundedRectangle(cornerRadius: 10).stroke(pal.outlineVariant, lineWidth: 1))
                    )
            } else {
                ForEach(viewModel.recentNotifications.prefix(3)) { item in
                    if item.canReply {
                        ReplyableNotificationCard(item: item, pal: pal) { replyText in
                            viewModel.sendNotificationReply(sbnKey: item.id, replyText: replyText)
                        }
                    } else {
                        HStack(alignment: .top, spacing: 8) {
                            Image(systemName: "bell.fill")
                                .font(.system(size: 12))
                                .foregroundStyle(pal.secondaryAccent)
                                .padding(.top, 2)
                            VStack(alignment: .leading, spacing: 3) {
                                Text("\(item.appName.uppercased()) • \(item.title)")
                                    .font(.system(size: 10, weight: .bold, design: .monospaced))
                                    .tracking(0.6)
                                    .foregroundStyle(pal.secondaryText)
                                    .lineLimit(1)
                                Text(item.body)
                                    .font(.system(size: 12))
                                    .foregroundStyle(pal.onSurface)
                                    .lineLimit(2)
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(10)
                        .background(
                            RoundedRectangle(cornerRadius: 10)
                                .fill(pal.surfaceContainer)
                                .overlay(RoundedRectangle(cornerRadius: 10).stroke(pal.outlineVariant, lineWidth: 1))
                        )
                    }
                }
            }
        }
    }

    // MARK: - Tab 2: Activity

    @ViewBuilder
    private var activityTabContent: some View {
        HStack(spacing: 6) {
            ForEach(ActivityFilter.allCases, id: \.self) { filter in
                let active = viewModel.activityFilter == filter
                Button {
                    viewModel.activityFilter = filter
                } label: {
                    Text(filter.rawValue)
                        .font(.system(size: 11, weight: .semibold))
                        .padding(.horizontal, 10)
                        .padding(.vertical, 5)
                        .background(RoundedRectangle(cornerRadius: 7).fill(active ? pal.primaryAccent : pal.surfaceContainer))
                        .overlay(RoundedRectangle(cornerRadius: 7).stroke(pal.outlineVariant, lineWidth: active ? 0 : 1))
                        .foregroundStyle(active ? Color.white : pal.onSurface)
                }
                .buttonStyle(.plain)
            }
            Spacer()
        }

        if viewModel.filteredActivities.isEmpty {
            Text("No activity logged yet. Send a file, sync clipboard, or run a media command.")
                .font(.system(size: 11, design: .monospaced))
                .foregroundStyle(pal.secondaryText)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(14)
                .background(
                    RoundedRectangle(cornerRadius: 10)
                        .fill(pal.surfaceContainer)
                        .overlay(RoundedRectangle(cornerRadius: 10).stroke(pal.outlineVariant, lineWidth: 1))
                )
        } else {
            ForEach(viewModel.filteredActivities) { item in
                HStack(alignment: .center, spacing: 10) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("\(item.category.rawValue) • \(item.title)")
                            .font(.system(size: 10, weight: .bold, design: .monospaced))
                            .tracking(0.5)
                            .foregroundStyle(pal.secondaryAccent)
                        Text(item.detail)
                            .font(.system(size: 12))
                            .foregroundStyle(pal.onSurface)
                            .lineLimit(2)
                    }
                    Spacer()
                    if let fileURL = item.fileURL {
                        Button("Reveal in Finder") {
                            viewModel.revealInFinder(fileURL)
                        }
                        .font(.system(size: 10, weight: .semibold))
                        .padding(.horizontal, 9)
                        .padding(.vertical, 5)
                        .background(RoundedRectangle(cornerRadius: 6).fill(pal.surfaceHigh))
                        .foregroundStyle(pal.onSurface)
                        .buttonStyle(.plain)
                    } else if let clipText = item.clipboardText {
                        Button("Copy Again") {
                            viewModel.copySnippetAgain(clipText)
                        }
                        .font(.system(size: 10, weight: .semibold))
                        .padding(.horizontal, 9)
                        .padding(.vertical, 5)
                        .background(RoundedRectangle(cornerRadius: 6).fill(pal.surfaceHigh))
                        .foregroundStyle(pal.onSurface)
                        .buttonStyle(.plain)
                    }
                }
                .padding(10)
                .background(
                    RoundedRectangle(cornerRadius: 10)
                        .fill(pal.surfaceContainer)
                        .overlay(RoundedRectangle(cornerRadius: 10).stroke(pal.outlineVariant, lineWidth: 1))
                )
            }
        }
    }

    // MARK: - Tab 3: Customize

    @ViewBuilder
    private var customizeTabContent: some View {
        // Palette Swatches
        VStack(alignment: .leading, spacing: 8) {
            Text("ACCENT THEME PALETTE")
                .font(.system(size: 10, weight: .bold, design: .monospaced))
                .tracking(0.6)
                .foregroundStyle(pal.secondaryText)

            HStack(spacing: 8) {
                ForEach(BridgeAccentTheme.allCases, id: \.self) { theme in
                    let spec = BridgePaletteSpec.forTheme(theme)
                    let selected = viewModel.preferences.accentTheme == theme
                    Button {
                        viewModel.preferences.accentTheme = theme
                    } label: {
                        VStack(spacing: 5) {
                            HStack(spacing: 4) {
                                Circle().fill(StudioThemePalette.color(spec.surface)).frame(width: 12, height: 12)
                                    .overlay(Circle().stroke(StudioThemePalette.color(spec.outlineVariant), lineWidth: 1))
                                Circle().fill(StudioThemePalette.color(spec.primaryAccent)).frame(width: 12, height: 12)
                                Circle().fill(StudioThemePalette.color(spec.secondaryAccent)).frame(width: 12, height: 12)
                            }
                            Text(theme.displayName)
                                .font(.system(size: 10, weight: .semibold))
                                .foregroundStyle(pal.onSurface)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                        .background(RoundedRectangle(cornerRadius: 8).fill(pal.surfaceLow))
                        .overlay(
                            RoundedRectangle(cornerRadius: 8)
                                .stroke(selected ? pal.primaryAccent : pal.outlineVariant, lineWidth: selected ? 2 : 1)
                        )
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .padding(12)
        .background(
            RoundedRectangle(cornerRadius: 10)
                .fill(pal.surfaceContainer)
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(pal.outlineVariant, lineWidth: 1))
        )

        // Transport & Battery Engine Controls
        VStack(alignment: .leading, spacing: 10) {
            Text("TRANSPORT & BATTERY ENGINE")
                .font(.system(size: 10, weight: .bold, design: .monospaced))
                .tracking(0.6)
                .foregroundStyle(pal.secondaryText)

            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Wi-Fi Cooldown Timer")
                        .font(.system(size: 12, weight: .semibold))
                    Text("Teardown after \(viewModel.preferences.wifiCooldownSeconds)s idle")
                        .font(.system(size: 10, design: .monospaced))
                        .foregroundStyle(pal.secondaryText)
                }
                Spacer()
                Slider(
                    value: Binding(
                        get: { Double(viewModel.preferences.wifiCooldownSeconds) },
                        set: { viewModel.preferences.wifiCooldownSeconds = Int($0.rounded()) }
                    ),
                    in: 5...60,
                    step: 1
                )
                .frame(width: 140)
                .tint(pal.primaryAccent)
            }

            Divider().overlay(pal.outlineVariant)

            preferenceToggleRow(
                title: "BLE-Only Battery Saver",
                subtitle: "Block automatic Wi-Fi wakeups",
                isOn: Binding(
                    get: { viewModel.preferences.bleOnlyBatterySaver },
                    set: { viewModel.preferences.bleOnlyBatterySaver = $0 }
                )
            )

            preferenceToggleRow(
                title: "Auto-Accept Incoming Files",
                subtitle: "Prompt before writing if OFF",
                isOn: Binding(
                    get: { viewModel.preferences.autoAcceptFiles },
                    set: { viewModel.preferences.autoAcceptFiles = $0 }
                )
            )

            preferenceToggleRow(
                title: "Compact Density Mode",
                subtitle: "Dense Studio telemetry rows",
                isOn: Binding(
                    get: { viewModel.preferences.compactMode },
                    set: { viewModel.preferences.compactMode = $0 }
                )
            )

            preferenceToggleRow(
                title: "Show Telemetry Footer Bar",
                subtitle: "LATENCY • E2EE • TRANSPORT",
                isOn: Binding(
                    get: { viewModel.preferences.showTelemetryFooter },
                    set: { viewModel.preferences.showTelemetryFooter = $0 }
                )
            )
        }
        .padding(12)
        .background(
            RoundedRectangle(cornerRadius: 10)
                .fill(pal.surfaceContainer)
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(pal.outlineVariant, lineWidth: 1))
        )

        // Clipboard Direction, Device Alias & Pairing
        VStack(alignment: .leading, spacing: 10) {
            Text("CONTINUITY & IDENTITY")
                .font(.system(size: 10, weight: .bold, design: .monospaced))
                .tracking(0.6)
                .foregroundStyle(pal.secondaryText)

            Picker("Clipboard Direction", selection: Binding(
                get: { viewModel.preferences.clipboardDirection },
                set: { viewModel.preferences.clipboardDirection = $0 }
            )) {
                ForEach(ClipboardDirection.allCases, id: \.self) { dir in
                    Text(dir.displayName).tag(dir)
                }
            }
            .pickerStyle(.menu)

            HStack(spacing: 8) {
                Text("Device Alias")
                    .font(.system(size: 12, weight: .semibold))
                Spacer()
                TextField("MacBridge", text: Binding(
                    get: { viewModel.preferences.deviceAlias },
                    set: { viewModel.preferences.deviceAlias = $0 }
                ))
                .textFieldStyle(.plain)
                .font(.system(size: 12, design: .monospaced))
                .padding(.horizontal, 8)
                .padding(.vertical, 5)
                .frame(width: 170)
                .background(RoundedRectangle(cornerRadius: 6).fill(pal.surfaceLowest))
                .overlay(RoundedRectangle(cornerRadius: 6).stroke(pal.outlineVariant, lineWidth: 1))
            }

            HStack(spacing: 8) {
                Button("Pair New Device") {
                    viewModel.startPairingFlow()
                }
                .buttonStyle(.borderedProminent)
                .tint(pal.primaryAccent)

                if viewModel.peerPaired {
                    Button("Unpair Device", role: .destructive) {
                        viewModel.unpairAllDevices()
                    }
                    .buttonStyle(.bordered)
                }
            }
        }
        .padding(12)
        .background(
            RoundedRectangle(cornerRadius: 10)
                .fill(pal.surfaceContainer)
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(pal.outlineVariant, lineWidth: 1))
        )
    }

    private func preferenceToggleRow(title: String, subtitle: String, isOn: Binding<Bool>) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(pal.onSurface)
                Text(subtitle)
                    .font(.system(size: 10, design: .monospaced))
                    .foregroundStyle(pal.secondaryText)
            }
            Spacer()
            Toggle("", isOn: isOn)
                .labelsHidden()
                .toggleStyle(.switch)
                .tint(pal.primaryAccent)
        }
    }

    // MARK: - Bottom Telemetry Bar

    private var bottomTelemetryBar: some View {
        HStack {
            let latText = viewModel.latencyMs.map { "\($0)MS" } ?? "-- MS"
            let transportLabel = viewModel.bridgeState == .activeWifiStream ? "WIFI" : "BLE"
            Text("LATENCY: \(latText) • \(transportLabel)")
                .font(.system(size: 10, weight: .semibold, design: .monospaced))
                .tracking(0.6)
                .foregroundStyle(pal.secondaryAccent)

            Spacer()

            Text("E2EE: TLS 1.3 PINNED")
                .font(.system(size: 10, weight: .semibold, design: .monospaced))
                .tracking(0.6)
                .foregroundStyle(pal.secondaryText)

            Button {
                NSApplication.shared.terminate(nil)
            } label: {
                Image(systemName: "power")
                    .font(.system(size: 10, weight: .bold))
                    .foregroundStyle(pal.secondaryText)
            }
            .buttonStyle(.plain)
            .padding(.leading, 8)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 9)
        .background(pal.surfaceLowest)
        .overlay(alignment: .top) {
            Rectangle()
                .fill(pal.outlineVariant)
                .frame(height: 1)
        }
    }
}

private struct ReplyableNotificationCard: View {
    let item: MirroredNotificationItem
    let pal: StudioThemePalette
    let onSendReply: (String) -> Void
    @StateObject private var replyState = InlineReplyBoxModel()

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("\(item.appName.uppercased()) • \(item.title)")
                .font(.system(size: 10, weight: .bold, design: .monospaced))
                .foregroundStyle(pal.activeCardInk.opacity(0.75))
                .lineLimit(1)

            Text(item.body)
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(pal.activeCardInk)
                .lineLimit(2)

            HStack(spacing: 6) {
                TextField("Reply inline...", text: $replyState.text)
                    .textFieldStyle(.plain)
                    .font(.system(size: 11))
                    .foregroundStyle(pal.onSurface)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 6)
                    .background(RoundedRectangle(cornerRadius: 6).fill(pal.surfaceLowest))

                Button("Send") {
                    onSendReply(replyState.text)
                    replyState.text = ""
                }
                .font(.system(size: 11, weight: .semibold))
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .background(RoundedRectangle(cornerRadius: 6).fill(pal.primaryAccent))
                .foregroundStyle(Color.white)
                .buttonStyle(.plain)
            }
        }
        .padding(11)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 10).fill(pal.activeCardLight))
    }
}

@MainActor
private final class InlineReplyBoxModel: ObservableObject {
    @Published var text: String = ""
}
