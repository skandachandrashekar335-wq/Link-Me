import AppKit
import Combine
import MacBridgeCore
import SwiftUI
import UniformTypeIdentifiers

struct BridgeStudioWindow: View {
    @ObservedObject var viewModel: BridgeDashboardViewModel
    @StateObject private var qrCountdown = PairingCountdownHelper()

    private var pal: StudioThemePalette { viewModel.palette }

    var body: some View {
        VStack(spacing: 0) {
            topNavigationHeader

            if let banner = viewModel.statusBanner {
                HStack {
                    Image(systemName: "sparkle")
                        .font(.system(size: 11))
                        .foregroundStyle(pal.primaryAccent)
                    Text(banner)
                        .font(.system(size: 11, weight: .medium, design: .monospaced))
                        .foregroundStyle(pal.onSurface)
                    Spacer()
                    Button {
                        viewModel.statusBanner = nil
                    } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 10, weight: .bold))
                            .foregroundStyle(pal.secondaryText)
                    }
                    .buttonStyle(.plain)
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 7)
                .background(pal.surfaceLow)
                .overlay(alignment: .bottom) { Rectangle().fill(pal.outlineVariant).frame(height: 1) }
            }

            HStack(spacing: 0) {
                leftSidebar
                ScrollView {
                    VStack(alignment: .leading, spacing: viewModel.preferences.compactMode ? 12 : 18) {
                        switch viewModel.selectedTab {
                        case .bridge:
                            bridgeWorkspace
                        case .pairing:
                            securePairingWorkspace
                        case .screenMirror:
                            ScreenMirrorView(viewModel: viewModel)
                        case .activity:
                            activityWorkspace
                        case .customize:
                            customizeWorkspace
                        case .diagnostics:
                            diagnosticsWorkspace
                        }
                    }
                    .padding(22)
                }
            }

            if viewModel.preferences.showTelemetryFooter {
                telemetryFooter
            }
        }
        .background(
            ZStack {
                pal.surface.ignoresSafeArea()
                RadialGradient(
                    colors: [pal.primaryAccent.opacity(pal.isLight ? 0.08 : 0.14), Color.clear],
                    center: .topTrailing,
                    startRadius: 20,
                    endRadius: 600
                )
                RadialGradient(
                    colors: [pal.secondaryAccent.opacity(pal.isLight ? 0.06 : 0.10), Color.clear],
                    center: .bottomLeading,
                    startRadius: 20,
                    endRadius: 520
                )
            }
        )
        .foregroundStyle(pal.onSurface)
        .frame(minWidth: 920, minHeight: 620)
        .onReceive(qrCountdown.timer) { now in
            viewModel.pairingRemainingSeconds = max(0, Int(viewModel.pairingExpiresAt.timeIntervalSince(now)))
        }
    }

    // MARK: - Top Glass Header

    private var topNavigationHeader: some View {
        HStack(spacing: 14) {
            HStack(spacing: 10) {
                Image(systemName: "link.circle.fill")
                    .font(.system(size: 20))
                    .foregroundStyle(pal.primaryAccent)
                Text("Link-Me")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(pal.onSurface)
            }

            Spacer()

            HStack(spacing: 4) {
                ForEach([StudioTab.bridge, .pairing, .screenMirror, .activity, .customize], id: \.self) { tab in
                    let active = viewModel.selectedTab == tab
                    Button {
                        if tab == .pairing && viewModel.pairingUriString.isEmpty {
                            viewModel.startPairingFlow(inStudioWindow: true)
                        } else {
                            viewModel.selectedTab = tab
                        }
                    } label: {
                        Text(tab.rawValue)
                            .font(.system(size: 12, weight: .medium))
                            .padding(.horizontal, 14)
                            .padding(.vertical, 6)
                            .background(Capsule().fill(active ? pal.surfaceHigh : Color.clear))
                            .foregroundStyle(active ? pal.onSurface : pal.secondaryText)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(3)
            .background(Capsule().fill(pal.surfaceLow))
            .overlay(Capsule().stroke(pal.outlineVariant, lineWidth: 1))

            Spacer()

            HStack(spacing: 8) {
                HStack(spacing: 6) {
                    Circle()
                        .fill(viewModel.peerPaired ? pal.secondaryAccent : Color.orange)
                        .frame(width: 7, height: 7)
                    let modeTag = viewModel.bridgeState == .activeWifiStream ? "mTLS LAN Stream" : "Bluetooth E2EE"
                    Text("\(viewModel.peerName) • \(viewModel.peerBatteryLabel) • \(modeTag)")
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(pal.onSurface)
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 5)
                .background(Capsule().fill(pal.surfaceLow))
                .overlay(Capsule().stroke(pal.outlineVariant, lineWidth: 1))

                Button {
                    viewModel.preferences.accentTheme = viewModel.preferences.accentTheme == .alabaster ? .liquidBlue : .alabaster
                } label: {
                    Image(systemName: pal.isLight ? "moon.fill" : "sun.max.fill")
                        .font(.system(size: 12))
                        .frame(width: 28, height: 28)
                        .background(Circle().fill(pal.surfaceLow))
                        .overlay(Circle().stroke(pal.outlineVariant, lineWidth: 1))
                }
                .buttonStyle(.plain)

                Button {
                    viewModel.startPairingFlow(inStudioWindow: true)
                } label: {
                    HStack(spacing: 5) {
                        Image(systemName: "qrcode")
                        Text("Pair Device")
                    }
                    .font(.system(size: 12, weight: .semibold))
                    .padding(.horizontal, 14)
                    .padding(.vertical, 7)
                    .background(Capsule().fill(pal.primaryAccent))
                    .foregroundStyle(Color.white)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 12)
        .background(pal.surfaceLow.opacity(0.85))
        .overlay(alignment: .bottom) { Rectangle().fill(pal.outlineVariant).frame(height: 1) }
    }

    // MARK: - Left Sidebar

    private var leftSidebar: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("WORKSPACE")
                .font(.system(size: 10, weight: .bold, design: .monospaced))
                .tracking(0.8)
                .foregroundStyle(pal.secondaryText)
                .padding(.horizontal, 10)
                .padding(.bottom, 4)

            sidebarNavItem(.bridge, icon: "rectangle.connected.to.line.below", title: "Bridge")
            sidebarNavItem(.pairing, icon: "qrcode.viewfinder", title: "Secure Pairing")
            sidebarNavItem(.screenMirror, icon: "rectangle.inset.filled.and.person.filled", title: "Screen Mirror")
            sidebarNavItem(.activity, icon: "clock.arrow.circlepath", title: "Activity")
            sidebarNavItem(.customize, icon: "slider.horizontal.3", title: "Customize")
            sidebarNavItem(.diagnostics, icon: "stethoscope", title: "Diagnostics")

            Spacer()

            VStack(alignment: .leading, spacing: 6) {
                Text("LINK INTEGRITY")
                    .font(.system(size: 9, weight: .bold, design: .monospaced))
                    .foregroundStyle(pal.secondaryText)
                HStack(spacing: 6) {
                    Circle()
                        .fill(viewModel.peripheralDiagnostics.isAdvertising ? pal.secondaryAccent : Color.orange)
                        .frame(width: 6, height: 6)
                    Text(viewModel.peripheralDiagnostics.isAdvertising ? "BLE GATT Active" : "BLE Initializing")
                        .font(.system(size: 11, weight: .medium))
                }
                Text("ChaCha20-Poly1305 • TLS 1.3")
                    .font(.system(size: 10, design: .monospaced))
                    .foregroundStyle(pal.secondaryText)
            }
            .padding(12)
            .liquidGlassCard(pal, cornerRadius: 12)
        }
        .padding(14)
        .frame(width: 210)
        .background(pal.surfaceLow.opacity(0.5))
        .overlay(alignment: .trailing) { Rectangle().fill(pal.outlineVariant).frame(width: 1) }
    }

    private func sidebarNavItem(_ tab: StudioTab, icon: String, title: String) -> some View {
        let active = viewModel.selectedTab == tab
        return Button {
            if tab == .pairing && viewModel.pairingUriString.isEmpty {
                viewModel.startPairingFlow(inStudioWindow: true)
            } else {
                viewModel.selectedTab = tab
            }
        } label: {
            HStack(spacing: 10) {
                Image(systemName: icon)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(active ? pal.primaryAccent : pal.secondaryText)
                    .frame(width: 18)
                Text(title)
                    .font(.system(size: 12, weight: active ? .semibold : .medium))
                    .foregroundStyle(active ? pal.onSurface : pal.secondaryText)
                Spacer()
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 9)
            .background(
                RoundedRectangle(cornerRadius: 10)
                    .fill(active ? pal.primaryAccent.opacity(0.16) : Color.clear)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 10)
                    .stroke(active ? pal.primaryAccent.opacity(0.35) : Color.clear, lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
    }

    // MARK: - Bridge Tab (matches macos_studio_bridge_liquid_glass)

    @ViewBuilder
    private var bridgeWorkspace: some View {
        // Hero Mac ↔ Android Continuity Banner
        HStack(spacing: 20) {
            VStack(spacing: 10) {
                RoundedRectangle(cornerRadius: 12)
                    .fill(pal.surfaceLowest.opacity(0.7))
                    .frame(width: 120, height: 76)
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(pal.outlineVariant, lineWidth: 1))
                    .overlay {
                        Image(systemName: "laptopcomputer")
                            .font(.system(size: 34, weight: .light))
                            .foregroundStyle(pal.secondaryAccent)
                    }
                Text(viewModel.preferences.normalizedAlias)
                    .font(.system(size: 14, weight: .semibold))
                Text("MACOS STUDIO • HOST")
                    .font(.system(size: 9, weight: .bold, design: .monospaced))
                    .foregroundStyle(pal.secondaryText)
            }

            Spacer()

            VStack(spacing: 10) {
                HStack(spacing: 6) {
                    Circle()
                        .fill(pal.secondaryAccent)
                        .frame(width: 7, height: 7)
                    Text(viewModel.bridgeState == .activeWifiStream
                        ? "mTLS 1.3 LAN Stream Active"
                        : "Encrypted Bluetooth E2EE Active")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(pal.secondaryAccent)
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 6)
                .background(Capsule().fill(pal.secondaryAccent.opacity(0.12)))
                .overlay(Capsule().stroke(pal.secondaryAccent.opacity(0.35), lineWidth: 1))

                HStack(spacing: 10) {
                    Circle().fill(pal.activeCardLight).frame(width: 6, height: 6)
                    Rectangle()
                        .stroke(style: StrokeStyle(lineWidth: 1.5, dash: [4, 4]))
                        .foregroundStyle(pal.outlineVariant)
                        .frame(width: 120, height: 2)
                    Circle().fill(pal.secondaryAccent).frame(width: 6, height: 6)
                }

                let latString = viewModel.latencyMs.map { "Latency: \($0) ms" } ?? "Latency: Standby"
                Text("\(latString) • Peer Battery: \(viewModel.peerBatteryLabel)")
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(pal.secondaryText)
            }

            Spacer()

            VStack(spacing: 10) {
                RoundedRectangle(cornerRadius: 12)
                    .fill(pal.surfaceLowest.opacity(0.7))
                    .frame(width: 76, height: 76)
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(pal.outlineVariant, lineWidth: 1))
                    .overlay {
                        Image(systemName: "smartphone")
                            .font(.system(size: 32, weight: .light))
                            .foregroundStyle(viewModel.peerPaired ? pal.secondaryAccent : pal.secondaryText)
                    }
                Text(viewModel.peerName)
                    .font(.system(size: 14, weight: .semibold))
                Text("ANDROID • ENDPOINT")
                    .font(.system(size: 9, weight: .bold, design: .monospaced))
                    .foregroundStyle(pal.secondaryText)
            }
        }
        .padding(20)
        .liquidGlassCard(pal, cornerRadius: 20)

        // Connection Lifecycle 4-State Strip
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("CONNECTION LIFECYCLE")
                    .font(.system(size: 10, weight: .bold, design: .monospaced))
                    .foregroundStyle(pal.secondaryText)
                Spacer()
                Text(viewModel.preferences.bleOnlyBatterySaver ? "Mode: Bluetooth-Only Battery Saver" : "Mode: Bluetooth-First + On-Demand Wi-Fi Turbo")
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(pal.secondaryAccent)
            }

            HStack(spacing: 10) {
                lifecycleTile(.dormantBle, icon: "antenna.radiowaves.left.and.right", title: "Dormant BLE", sub: "Ready • Encrypted")
                lifecycleTile(.handshakeWake, icon: "arrow.triangle.2.circlepath", title: "Handshake Wake", sub: "Session negotiation")
                lifecycleTile(.activeWifiStream, icon: "wifi", title: "Wi-Fi Stream", sub: "High-speed TLS 1.3")
                lifecycleTile(.cooldownIdle, icon: "moon.zzz", title: "Cooldown Idle", sub: "\(viewModel.preferences.wifiCooldownSeconds)s grace timer")
            }
        }

        // 01 / Quick Actions
        VStack(alignment: .leading, spacing: 8) {
            Text("01 / QUICK ACTIONS")
                .font(.system(size: 10, weight: .bold, design: .monospaced))
                .foregroundStyle(pal.secondaryText)
            HStack(spacing: 10) {
                studioQuickButton(icon: "doc.badge.plus", title: "Send File") { viewModel.openFilePickerAndSend() }
                studioQuickButton(icon: "doc.on.clipboard", title: "Clipboard Sync") { viewModel.syncClipboardNow() }
                studioQuickButton(icon: "qrcode", title: "Show Pairing QR") { viewModel.startPairingFlow(inStudioWindow: true) }
                studioQuickButton(icon: "bell.and.waves.left.and.right", title: "Ring Phone") { viewModel.ringMyPhone() }
                studioQuickButton(icon: "link", title: "Send URL") { viewModel.sendUrlToAndroid() }
                studioQuickButton(icon: "stethoscope", title: "Diagnostics") { viewModel.selectedTab = .diagnostics }
            }
        }

        // Pending Incoming Offer Banner
        if let pending = viewModel.pendingIncomingOffer {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Incoming File From \(viewModel.peerName)")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(pal.onSurface)
                    Text("\(pending.offer.filename) • \(pending.offer.byteSize / 1024) KB")
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundStyle(pal.secondaryText)
                }
                Spacer()
                Button("Decline") { viewModel.resolvePendingIncomingOffer(accepted: false) }
                    .buttonStyle(.bordered)
                Button("Accept") { viewModel.resolvePendingIncomingOffer(accepted: true) }
                    .buttonStyle(.borderedProminent)
                    .tint(pal.primaryAccent)
            }
            .padding(14)
            .liquidGlassCard(pal, cornerRadius: 16, highlighted: true)
        }

        // Main Two-Column Content Grid
        HStack(alignment: .top, spacing: 16) {
            VStack(spacing: 16) {
                // File Transfer Card
                VStack(alignment: .leading, spacing: 12) {
                    HStack {
                        Label("File Transfer", systemImage: "square.and.arrow.up.on.square")
                            .font(.system(size: 14, weight: .semibold))
                        Spacer()
                        Text(viewModel.bridgeState == .activeWifiStream ? "Wi-Fi mTLS 1.3" : "Bluetooth / Auto-Turbo")
                            .font(.system(size: 10, design: .monospaced))
                            .foregroundStyle(pal.secondaryText)
                    }

                    Button {
                        viewModel.openFilePickerAndSend()
                    } label: {
                        VStack(spacing: 8) {
                            Image(systemName: "icloud.and.arrow.up")
                                .font(.system(size: 24))
                                .foregroundStyle(viewModel.isDropTargeted ? pal.primaryAccent : pal.activeCardLight)
                                .frame(width: 46, height: 46)
                                .background(Circle().fill(pal.surfaceHigh))
                            Text("Drop files here or click to beam")
                                .font(.system(size: 13, weight: .medium))
                            Text("RAW photos, APKs & documents over Wi-Fi TLS 1.3 or Bluetooth E2EE")
                                .font(.system(size: 10))
                                .foregroundStyle(pal.secondaryText)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 26)
                        .background(
                            RoundedRectangle(cornerRadius: 14)
                                .fill(viewModel.isDropTargeted ? pal.primaryAccent.opacity(0.12) : pal.surfaceLowest.opacity(0.65))
                        )
                        .overlay(
                            RoundedRectangle(cornerRadius: 14)
                                .strokeBorder(
                                    viewModel.isDropTargeted ? pal.primaryAccent : pal.outlineVariant,
                                    style: StrokeStyle(lineWidth: 1, dash: [6, 4])
                                )
                        )
                    }
                    .buttonStyle(.plain)
                    .onDrop(of: [.fileURL], isTargeted: $viewModel.isDropTargeted) { providers in
                        guard let provider = providers.first else { return false }
                        _ = provider.loadObject(ofClass: URL.self) { url, _ in
                            guard let url else { return }
                            Task { @MainActor in viewModel.sendFile(url: url) }
                        }
                        return true
                    }

                    if let transfer = viewModel.activeTransfer {
                        VStack(alignment: .leading, spacing: 6) {
                            HStack {
                                Text(transfer.filename).font(.system(size: 12, weight: .semibold))
                                Spacer()
                                Button("Cancel") { viewModel.cancelActiveTransfer() }
                                    .foregroundStyle(Color.red)
                                    .buttonStyle(.plain)
                            }
                            ProgressView(value: transfer.fractionCompleted).tint(pal.primaryAccent)
                            HStack {
                                Text("\(transfer.bytesTransferred / 1024) / \(transfer.totalBytes / 1024) KB")
                                Spacer()
                                Text(String(format: "%.1f MB/s", transfer.bytesPerSecond / 1_048_576))
                            }
                            .font(.system(size: 10, design: .monospaced))
                            .foregroundStyle(pal.secondaryText)
                        }
                        .padding(10)
                        .background(RoundedRectangle(cornerRadius: 10).fill(pal.surfaceLow))
                    }
                }
                .padding(16)
                .liquidGlassCard(pal, cornerRadius: 18)

                // URL Handoff Card
                VStack(alignment: .leading, spacing: 10) {
                    HStack {
                        Label("URL Handoff", systemImage: "safari")
                            .font(.system(size: 14, weight: .semibold))
                        Spacer()
                        Text("Strict http/https validation")
                            .font(.system(size: 10, design: .monospaced))
                            .foregroundStyle(pal.secondaryText)
                    }
                    HStack(spacing: 8) {
                        TextField("https://...", text: $viewModel.urlDraft)
                            .textFieldStyle(.plain)
                            .font(.system(size: 12, design: .monospaced))
                            .padding(.horizontal, 12)
                            .padding(.vertical, 9)
                            .background(RoundedRectangle(cornerRadius: 10).fill(pal.surfaceLowest))
                            .overlay(RoundedRectangle(cornerRadius: 10).stroke(pal.outlineVariant, lineWidth: 1))

                        Button {
                            viewModel.sendUrlToAndroid()
                        } label: {
                            Label("Send to Android", systemImage: "paperplane.fill")
                                .font(.system(size: 12, weight: .semibold))
                                .padding(.horizontal, 14)
                                .padding(.vertical, 9)
                                .background(Capsule().fill(pal.primaryAccent))
                                .foregroundStyle(Color.white)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(16)
                .liquidGlassCard(pal, cornerRadius: 18)
            }
            .frame(maxWidth: .infinity)

            VStack(spacing: 16) {
                // Universal Clipboard Card
                VStack(alignment: .leading, spacing: 10) {
                    HStack {
                        Label("Universal Clipboard", systemImage: "doc.on.clipboard")
                            .font(.system(size: 14, weight: .semibold))
                        Spacer()
                        Text(viewModel.clipboardSyncEnabled ? "Sync Enabled" : "Paused")
                            .font(.system(size: 10, weight: .semibold))
                            .padding(.horizontal, 9)
                            .padding(.vertical, 3)
                            .background(Capsule().fill(pal.secondaryAccent.opacity(0.15)))
                            .foregroundStyle(pal.secondaryAccent)
                    }
                    HStack {
                        Text(viewModel.preferences.clipboardDirection.displayName)
                            .font(.system(size: 12))
                        Spacer()
                        Button("Sync Now") { viewModel.syncClipboardNow() }
                            .buttonStyle(.bordered)
                    }
                    .padding(10)
                    .background(RoundedRectangle(cornerRadius: 10).fill(pal.surfaceLow))
                    HStack(spacing: 6) {
                        Image(systemName: "lock.shield.fill").foregroundStyle(pal.secondaryAccent)
                        Text("Concealed password-manager clips filtered")
                            .font(.system(size: 11))
                            .foregroundStyle(pal.secondaryText)
                    }
                }
                .padding(16)
                .liquidGlassCard(pal, cornerRadius: 18)

                // Now Playing Media Deck
                VStack(alignment: .leading, spacing: 12) {
                    HStack {
                        Label("Now Playing & Audio", systemImage: "waveform")
                            .font(.system(size: 14, weight: .semibold))
                        Spacer()
                        Text("\(viewModel.peerName) Media")
                            .font(.system(size: 10, design: .monospaced))
                            .foregroundStyle(pal.secondaryText)
                    }
                    HStack(spacing: 8) {
                        studioMediaBtn("backward.fill", .prev)
                        Button {
                            viewModel.sendMediaAction(.playPause)
                        } label: {
                            Image(systemName: "playpause.fill")
                                .font(.system(size: 15, weight: .bold))
                                .frame(width: 44, height: 44)
                                .background(Circle().fill(pal.primaryAccent))
                                .foregroundStyle(Color.white)
                        }
                        .buttonStyle(.plain)
                        studioMediaBtn("forward.fill", .next)
                        studioMediaBtn("speaker.wave.1.fill", .volDown)
                        studioMediaBtn("speaker.slash.fill", .mute)
                        studioMediaBtn("speaker.wave.3.fill", .volUp)
                    }
                }
                .padding(16)
                .liquidGlassCard(pal, cornerRadius: 18)

                // Notification Mirroring Card
                VStack(alignment: .leading, spacing: 10) {
                    HStack {
                        Label("Notification Mirroring", systemImage: "bell.badge")
                            .font(.system(size: 14, weight: .semibold))
                        Spacer()
                        Toggle("", isOn: Binding(
                            get: { viewModel.notificationMirrorEnabled },
                            set: { viewModel.toggleNotificationMirror($0) }
                        ))
                        .labelsHidden()
                        .toggleStyle(.switch)
                        .tint(pal.primaryAccent)
                    }
                    if viewModel.recentNotifications.isEmpty {
                        Text("No mirrored notifications yet.")
                            .font(.system(size: 11, design: .monospaced))
                            .foregroundStyle(pal.secondaryText)
                            .padding(10)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(RoundedRectangle(cornerRadius: 10).fill(pal.surfaceLow))
                    } else {
                        ForEach(viewModel.recentNotifications.prefix(2)) { item in
                            ReplyableNotificationCard(item: item, pal: pal) { replyText in
                                viewModel.sendNotificationReply(sbnKey: item.id, replyText: replyText)
                            }
                        }
                    }
                }
                .padding(16)
                .liquidGlassCard(pal, cornerRadius: 18)
            }
            .frame(maxWidth: .infinity)
        }
    }

    private func lifecycleTile(_ state: BridgeState, icon: String, title: String, sub: String) -> some View {
        let active = viewModel.bridgeState == state
        return HStack(spacing: 10) {
            Image(systemName: icon)
                .font(.system(size: 14))
                .frame(width: 30, height: 30)
                .background(Circle().fill(active ? Color.white.opacity(0.18) : pal.surfaceHigh))
                .foregroundStyle(active ? Color.white : pal.secondaryText)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(active ? Color.white : pal.onSurface)
                Text(sub)
                    .font(.system(size: 10))
                    .foregroundStyle(active ? Color.white.opacity(0.8) : pal.secondaryText)
            }
            Spacer()
        }
        .padding(12)
        .background(
            RoundedRectangle(cornerRadius: 14)
                .fill(active ? pal.primaryAccent.opacity(0.85) : pal.surfaceContainer.opacity(0.72))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 14)
                .stroke(active ? pal.activeCardLight.opacity(0.6) : pal.outlineVariant, lineWidth: 1)
        )
    }

    private func studioQuickButton(icon: String, title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 8) {
                Image(systemName: icon)
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(pal.activeCardLight)
                    .frame(width: 32, height: 32)
                    .background(RoundedRectangle(cornerRadius: 8).fill(pal.surfaceHigh))
                Text(title)
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(pal.onSurface)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 12)
            .liquidGlassCard(pal, cornerRadius: 14)
        }
        .buttonStyle(.plain)
    }

    private func studioMediaBtn(_ symbol: String, _ action: MediaControlAction) -> some View {
        Button {
            viewModel.sendMediaAction(action)
        } label: {
            Image(systemName: symbol)
                .font(.system(size: 13, weight: .semibold))
                .frame(maxWidth: .infinity)
                .frame(height: 36)
                .background(Circle().fill(pal.surfaceHigh))
                .foregroundStyle(pal.onSurface)
        }
        .buttonStyle(.plain)
    }

    // MARK: - Secure Pairing Workspace (matches macos_studio_secure_pairing_liquid_glass)

    private var securePairingWorkspace: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("CONTINUITY STUDIO • LOCAL-FIRST P2P")
                        .font(.system(size: 10, weight: .bold, design: .monospaced))
                        .foregroundStyle(pal.primaryAccent)
                    Text("Secure Device Pairing")
                        .font(.system(size: 22, weight: .bold))
                }
                Spacer()
                Button("Regenerate QR") {
                    Task { await viewModel.refreshPairingSession() }
                }
                .buttonStyle(.bordered)
            }

            HStack(alignment: .top, spacing: 20) {
                // Left QR Card
                VStack(spacing: 14) {
                    HStack {
                        HStack(spacing: 6) {
                            Circle().fill(pal.secondaryAccent).frame(width: 7, height: 7)
                            Text("Waiting for Android scan...")
                                .font(.system(size: 11, weight: .medium))
                        }
                        Spacer()
                        Text(String(format: "%02d:%02d", viewModel.pairingRemainingSeconds / 60, viewModel.pairingRemainingSeconds % 60))
                            .font(.system(size: 11, weight: .bold, design: .monospaced))
                            .foregroundStyle(pal.secondaryText)
                    }

                    if let img = PairingView.makeQRNSImage(from: viewModel.pairingUriString) {
                        Image(nsImage: img)
                            .interpolation(.none)
                            .resizable()
                            .scaledToFit()
                            .frame(width: 210, height: 210)
                            .padding(14)
                            .background(RoundedRectangle(cornerRadius: 16).fill(Color.white))
                    } else {
                        RoundedRectangle(cornerRadius: 16)
                            .fill(pal.surfaceLow)
                            .frame(width: 210, height: 210)
                    }

                    Text("Scan with AndroidBridge CameraX Scanner")
                        .font(.system(size: 13, weight: .semibold))
                    Text("X25519 Ephemeral Key Agreement • ChaCha20 BLE E2EE + TLS 1.3")
                        .font(.system(size: 10))
                        .foregroundStyle(pal.secondaryText)
                        .multilineTextAlignment(.center)

                    HStack {
                        Text("BLE: \(viewModel.peripheralDiagnostics.isAdvertising ? "Advertising AM01" : "Initializing") • Port :\(viewModel.localListeningPort)")
                            .font(.system(size: 10, design: .monospaced))
                            .foregroundStyle(pal.secondaryAccent)
                        Spacer()
                    }
                    .padding(10)
                    .background(RoundedRectangle(cornerRadius: 10).fill(pal.surfaceLowest))
                }
                .padding(18)
                .frame(maxWidth: .infinity)
                .liquidGlassCard(pal, cornerRadius: 20)

                // Right SAS Verification Card
                VStack(alignment: .leading, spacing: 16) {
                    Text("STEP 3 • SECURITY VERIFICATION")
                        .font(.system(size: 10, weight: .bold, design: .monospaced))
                        .foregroundStyle(pal.primaryAccent)
                    Text("Verify Short Authentication String")
                        .font(.system(size: 18, weight: .bold))
                    Text("Confirm this 6-digit code matches the one displayed on your Android device after scanning the QR code.")
                        .font(.system(size: 12))
                        .foregroundStyle(pal.secondaryText)

                    VStack(spacing: 10) {
                        Text("SHORT AUTHENTICATION STRING (SAS)")
                            .font(.system(size: 10, weight: .bold, design: .monospaced))
                            .foregroundStyle(pal.secondaryText)
                        Text(viewModel.pendingApproval.map { formatSas($0.sasCode) } ?? "--- ---")
                            .font(.system(size: 36, weight: .bold, design: .monospaced))
                            .tracking(4)
                            .foregroundStyle(viewModel.pendingApproval != nil ? pal.onSurface : pal.secondaryText)
                        Text(viewModel.pendingApproval != nil
                            ? "SHA-256 Pin Ready • Peer: \(viewModel.pendingApproval!.peerDeviceName)"
                            : "Waiting for Android PAIR_HELLO over Bluetooth or LAN...")
                            .font(.system(size: 11, design: .monospaced))
                            .foregroundStyle(viewModel.pendingApproval != nil ? pal.secondaryAccent : pal.secondaryText)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(24)
                    .background(RoundedRectangle(cornerRadius: 14).fill(pal.surfaceLowest))
                    .overlay(RoundedRectangle(cornerRadius: 14).stroke(pal.outlineVariant, lineWidth: 1))

                    HStack(spacing: 12) {
                        Button("Reject & Cancel", role: .destructive) {
                            viewModel.rejectPairing()
                        }
                        .buttonStyle(.bordered)
                        Spacer()
                        Button("Confirm & Trust") {
                            viewModel.confirmPairing()
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(pal.primaryAccent)
                        .disabled(viewModel.pendingApproval == nil)
                    }
                }
                .padding(18)
                .frame(maxWidth: .infinity)
                .liquidGlassCard(pal, cornerRadius: 20)
            }
        }
    }

    private func formatSas(_ code: String) -> String {
        guard code.count == 6 else { return code }
        let idx = code.index(code.startIndex, offsetBy: 3)
        return "\(code[..<idx]) \(code[idx...])"
    }

    // MARK: - Activity Workspace (matches macos_studio_activity)

    private var activityWorkspace: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("CONTINUITY LOG • 01")
                        .font(.system(size: 10, weight: .bold, design: .monospaced))
                        .foregroundStyle(pal.secondaryText)
                    Text("Activity")
                        .font(.system(size: 24, weight: .bold))
                }
                Spacer()
                HStack(spacing: 8) {
                    TextField("Filter events...", text: $viewModel.activitySearchQuery)
                        .textFieldStyle(.plain)
                        .font(.system(size: 11))
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .frame(width: 190)
                        .background(RoundedRectangle(cornerRadius: 8).fill(pal.surfaceLow))
                        .overlay(RoundedRectangle(cornerRadius: 8).stroke(pal.outlineVariant, lineWidth: 1))

                    Button("Reset") {
                        viewModel.activityLog.removeAll()
                    }
                    .buttonStyle(.bordered)
                }
            }

            HStack(spacing: 8) {
                ForEach(ActivityFilter.allCases, id: \.self) { filter in
                    let active = viewModel.activityFilter == filter
                    Button {
                        viewModel.activityFilter = filter
                    } label: {
                        Text(filter.rawValue)
                            .font(.system(size: 11, weight: .semibold))
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .background(Capsule().fill(active ? pal.activeCardLight : pal.surfaceContainer))
                            .foregroundStyle(active ? pal.activeCardInk : pal.secondaryText)
                    }
                    .buttonStyle(.plain)
                }
            }

            if viewModel.filteredActivities.isEmpty {
                Text("No continuity events logged yet. Transfer a file, sync clipboard, or run Diagnostics.")
                    .font(.system(size: 12, design: .monospaced))
                    .foregroundStyle(pal.secondaryText)
                    .padding(18)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .liquidGlassCard(pal, cornerRadius: 16)
            } else {
                ForEach(viewModel.filteredActivities) { item in
                    HStack(spacing: 12) {
                        Image(systemName: iconForCategory(item.category))
                            .font(.system(size: 14))
                            .foregroundStyle(pal.secondaryAccent)
                            .frame(width: 34, height: 34)
                            .background(RoundedRectangle(cornerRadius: 9).fill(pal.surfaceHigh))

                        VStack(alignment: .leading, spacing: 3) {
                            HStack(spacing: 8) {
                                Text(item.title).font(.system(size: 13, weight: .semibold))
                                Text(item.badgeText)
                                    .font(.system(size: 9, weight: .semibold, design: .monospaced))
                                    .padding(.horizontal, 7)
                                    .padding(.vertical, 2)
                                    .background(Capsule().fill(pal.secondaryAccent.opacity(0.16)))
                                    .foregroundStyle(pal.secondaryAccent)
                            }
                            Text(item.detail)
                                .font(.system(size: 11))
                                .foregroundStyle(pal.secondaryText)
                                .lineLimit(2)
                        }
                        Spacer()

                        if let fileURL = item.fileURL {
                            Button("Reveal in Finder") { viewModel.revealInFinder(fileURL) }
                                .buttonStyle(.bordered)
                        } else if let clip = item.clipboardText {
                            Button("Copy Again") { viewModel.copySnippetAgain(clip) }
                                .buttonStyle(.bordered)
                        }

                        Text(item.category.rawValue)
                            .font(.system(size: 10, design: .monospaced))
                            .foregroundStyle(pal.secondaryText)
                    }
                    .padding(12)
                    .liquidGlassCard(pal, cornerRadius: 14)
                }
            }
        }
    }

    private func iconForCategory(_ cat: ActivityCategory) -> String {
        switch cat {
        case .files: return "doc.fill"
        case .clipboard: return "doc.on.clipboard.fill"
        case .media: return "play.circle.fill"
        case .system: return "bolt.shield.fill"
        }
    }

    // MARK: - Customize Workspace (matches macos_studio_customize)

    private var customizeWorkspace: some View {
        VStack(alignment: .leading, spacing: 18) {
            VStack(alignment: .leading, spacing: 2) {
                Text("SYSTEM SETTINGS")
                    .font(.system(size: 10, weight: .bold, design: .monospaced))
                    .foregroundStyle(pal.secondaryText)
                Text("Customize Studio")
                    .font(.system(size: 24, weight: .bold))
            }

            // 01 Appearance
            VStack(alignment: .leading, spacing: 12) {
                Text("01 / INTERFACE • APPEARANCE")
                    .font(.system(size: 10, weight: .bold, design: .monospaced))
                    .foregroundStyle(pal.secondaryText)

                HStack(spacing: 8) {
                    ForEach(BridgeAccentTheme.allCases, id: \.self) { theme in
                        let spec = BridgePaletteSpec.forTheme(theme)
                        let selected = viewModel.preferences.accentTheme == theme
                        Button {
                            viewModel.preferences.accentTheme = theme
                        } label: {
                            HStack(spacing: 6) {
                                Circle()
                                    .fill(StudioThemePalette.color(spec.primaryAccent))
                                    .frame(width: 10, height: 10)
                                Text(theme.displayName)
                                    .font(.system(size: 11, weight: .semibold))
                            }
                            .padding(.horizontal, 12)
                            .padding(.vertical, 7)
                            .background(Capsule().fill(selected ? pal.surfaceHigh : pal.surfaceLow))
                            .overlay(Capsule().stroke(selected ? pal.primaryAccent : pal.outlineVariant, lineWidth: selected ? 1.5 : 1))
                        }
                        .buttonStyle(.plain)
                    }
                }

                customizeToggleRow(
                    title: "Compact Mode",
                    subtitle: "Reduce row padding for dense multi-device telemetry.",
                    isOn: Binding(get: { viewModel.preferences.compactMode }, set: { viewModel.preferences.compactMode = $0 })
                )
            }
            .padding(16)
            .liquidGlassCard(pal, cornerRadius: 18)

            // 02 Continuity & Sync
            VStack(alignment: .leading, spacing: 12) {
                Text("02 / HANDOFF • CONTINUITY & SYNC")
                    .font(.system(size: 10, weight: .bold, design: .monospaced))
                    .foregroundStyle(pal.secondaryText)

                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Device Alias").font(.system(size: 12, weight: .semibold))
                        Text("Displayed in pairing URIs and Android header").font(.system(size: 11)).foregroundStyle(pal.secondaryText)
                    }
                    Spacer()
                    TextField("MacBridge", text: Binding(
                        get: { viewModel.preferences.deviceAlias },
                        set: { viewModel.preferences.deviceAlias = $0 }
                    ))
                    .textFieldStyle(.plain)
                    .font(.system(size: 12, design: .monospaced))
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .frame(width: 210)
                    .background(RoundedRectangle(cornerRadius: 8).fill(pal.surfaceLow))
                    .overlay(RoundedRectangle(cornerRadius: 8).stroke(pal.outlineVariant, lineWidth: 1))
                }

                HStack {
                    Text("Clipboard Direction").font(.system(size: 12, weight: .semibold))
                    Spacer()
                    Picker("", selection: Binding(
                        get: { viewModel.preferences.clipboardDirection },
                        set: { viewModel.preferences.clipboardDirection = $0 }
                    )) {
                        ForEach(ClipboardDirection.allCases, id: \.self) { dir in
                            Text(dir.displayName).tag(dir)
                        }
                    }
                    .labelsHidden()
                    .frame(width: 190)
                }

                customizeToggleRow(
                    title: "Telemetry Visibility",
                    subtitle: "Show live latency, Bluetooth/Wi-Fi band, and E2EE status bar.",
                    isOn: Binding(get: { viewModel.preferences.showTelemetryFooter }, set: { viewModel.preferences.showTelemetryFooter = $0 })
                )
            }
            .padding(16)
            .liquidGlassCard(pal, cornerRadius: 18)

            // 03 Connection & Power
            VStack(alignment: .leading, spacing: 12) {
                Text("03 / TRANSPORT • CONNECTION & POWER")
                    .font(.system(size: 10, weight: .bold, design: .monospaced))
                    .foregroundStyle(pal.secondaryText)

                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Wi-Fi Cooldown Timer (\(viewModel.preferences.wifiCooldownSeconds)s)")
                            .font(.system(size: 12, weight: .semibold))
                        Text("Returns from Wi-Fi Turbo to Bluetooth E2EE after inactivity.")
                            .font(.system(size: 11)).foregroundStyle(pal.secondaryText)
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
                    .frame(width: 170)
                    .tint(pal.primaryAccent)
                }

                customizeToggleRow(
                    title: "Bluetooth-Only Battery Saver",
                    subtitle: "Operate exclusively over encrypted BLE GATT / L2CAP with zero Wi-Fi wakeups.",
                    isOn: Binding(get: { viewModel.preferences.bleOnlyBatterySaver }, set: { viewModel.preferences.bleOnlyBatterySaver = $0 })
                )

                customizeToggleRow(
                    title: "Auto-Accept Incoming Files",
                    subtitle: "Prompt for manual confirmation on incoming files when disabled.",
                    isOn: Binding(get: { viewModel.preferences.autoAcceptFiles }, set: { viewModel.preferences.autoAcceptFiles = $0 })
                )
            }
            .padding(16)
            .liquidGlassCard(pal, cornerRadius: 18)

            // 04 Device & Pairing Management
            VStack(alignment: .leading, spacing: 12) {
                Text("04 / SECURITY • DEVICE & PAIRING MANAGEMENT")
                    .font(.system(size: 10, weight: .bold, design: .monospaced))
                    .foregroundStyle(pal.secondaryText)

                HStack {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(viewModel.peerName).font(.system(size: 14, weight: .semibold))
                        Text(viewModel.peerPaired && !viewModel.peerCertFingerprint.isEmpty
                            ? "SHA-256 Pin: \(viewModel.peerCertFingerprint.prefix(24))…"
                            : "No paired device in Keychain TrustStore")
                            .font(.system(size: 11, design: .monospaced))
                            .foregroundStyle(pal.secondaryText)
                    }
                    Spacer()
                    Button("Re-pair") { viewModel.startPairingFlow(inStudioWindow: true) }
                        .buttonStyle(.bordered)
                    if viewModel.peerPaired {
                        Button("Forget Device", role: .destructive) { viewModel.unpairAllDevices() }
                            .buttonStyle(.bordered)
                    }
                }
            }
            .padding(16)
            .liquidGlassCard(pal, cornerRadius: 18)
        }
    }

    private func customizeToggleRow(title: String, subtitle: String, isOn: Binding<Bool>) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: 12, weight: .semibold))
                Text(subtitle).font(.system(size: 11)).foregroundStyle(pal.secondaryText)
            }
            Spacer()
            Toggle("", isOn: isOn)
                .labelsHidden()
                .toggleStyle(.switch)
                .tint(pal.primaryAccent)
        }
    }

    // MARK: - Diagnostics Workspace (Phase 1 Requirement)

    private var diagnosticsWorkspace: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("HARDWARE & CRYPTO TELEMETRY")
                        .font(.system(size: 10, weight: .bold, design: .monospaced))
                        .foregroundStyle(pal.secondaryText)
                    Text("Connection Diagnostics")
                        .font(.system(size: 24, weight: .bold))
                }
                Spacer()
                Button {
                    viewModel.runLoopbackSelfTest()
                } label: {
                    Label("Run Loopback Self-Test", systemImage: "play.circle.fill")
                        .font(.system(size: 12, weight: .semibold))
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .background(Capsule().fill(pal.primaryAccent))
                        .foregroundStyle(Color.white)
                }
                .buttonStyle(.plain)
            }

            VStack(alignment: .leading, spacing: 10) {
                diagRow("BLE Power & Advertising", viewModel.peripheralDiagnostics.isAdvertising ? "Powered On • Advertising 414D0001" : viewModel.peripheralDiagnostics.lastStatusMessage)
                diagRow("GATT Subscribers", "\(viewModel.peripheralDiagnostics.subscribedCentralsCount) connected")
                diagRow("Bluetooth L2CAP CoC PSM", viewModel.peripheralDiagnostics.l2capPsm.map { "\($0)" } ?? "Pending")
                diagRow("Pairing TCP Listener Port", ":\(viewModel.localListeningPort)")
                diagRow("Keychain Cert SHA-256", viewModel.localCertFingerprint.isEmpty ? "Unavailable" : viewModel.localCertFingerprint)
                diagRow("Peer E2EE Session", viewModel.peerPaired ? "ChaCha20-Poly1305 + X25519 Pin Active" : "Not Paired")
            }
            .padding(16)
            .liquidGlassCard(pal, cornerRadius: 18)

            if let result = viewModel.loopbackDiagnosticsResult {
                HStack(spacing: 10) {
                    Image(systemName: result.hasPrefix("PASS") ? "checkmark.seal.fill" : "exclamationmark.triangle.fill")
                        .foregroundStyle(result.hasPrefix("PASS") ? pal.secondaryAccent : Color.red)
                    Text(result)
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundStyle(pal.onSurface)
                }
                .padding(14)
                .frame(maxWidth: .infinity, alignment: .leading)
                .liquidGlassCard(pal, cornerRadius: 14, highlighted: true)
            }
        }
    }

    private func diagRow(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label)
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(pal.secondaryText)
            Spacer()
            Text(value)
                .font(.system(size: 11, weight: .semibold, design: .monospaced))
                .foregroundStyle(pal.onSurface)
                .textSelection(.enabled)
        }
    }

    private var telemetryFooter: some View {
        HStack {
            Text("Mac Battery: \(viewModel.localBatteryLabel)   •   Peer Battery: \(viewModel.peerBatteryLabel)   •   Transport: \(viewModel.bridgeState == .activeWifiStream ? "Local Wi-Fi mTLS 1.3" : "Encrypted Bluetooth")")
                .font(.system(size: 10, design: .monospaced))
                .foregroundStyle(pal.secondaryText)
            Spacer()
            let latStr = viewModel.latencyMs.map { "LATENCY: \($0)ms" } ?? "LATENCY: --"
            Text("\(latStr)   •   Bridge: \(viewModel.peerPaired ? "Verified" : "Ready")")
                .font(.system(size: 10, design: .monospaced))
                .foregroundStyle(pal.secondaryAccent)
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 9)
        .background(pal.surfaceLow.opacity(0.9))
        .overlay(alignment: .top) { Rectangle().fill(pal.outlineVariant).frame(height: 1) }
    }
}

@MainActor
private final class PairingCountdownHelper: ObservableObject {
    let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()
}
