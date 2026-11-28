import AppKit
import MacBridgeCore
import SwiftUI

private struct CursorRipple: Identifiable {
    let id = UUID()
    let point: CGPoint
    let isDrag: Bool
}

@MainActor
private final class ScreenMirrorUiState: ObservableObject {
    @Published var textToType: String = ""
    @Published var isKeyboardFocusActive: Bool = false
    @Published var ripples: [CursorRipple] = []
    var dragStartPoint: CGPoint?
    var dragStartTime: Date?
    var eventMonitor: Any?
    var lastScrollSentAt: Date = .distantPast
}

struct ScreenMirrorView: View {
    @ObservedObject var viewModel: BridgeDashboardViewModel
    @StateObject private var uiState = ScreenMirrorUiState()

    private var pal: StudioThemePalette { viewModel.palette }

    private var frameAspectRatio: CGFloat {
        let w = CGFloat(max(1, viewModel.remoteScreenWidth))
        let h = CGFloat(max(1, viewModel.remoteScreenHeight))
        return w / h
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            // Controls Bar
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 3) {
                    Text("Interactive Phone & Desktop Mirroring")
                        .font(.system(size: 18, weight: .bold))
                        .foregroundStyle(pal.onSurface)
                    Text(viewModel.mirrorStatusText)
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundStyle(pal.secondaryText)
                }

                Spacer()

                Toggle(isOn: $uiState.isKeyboardFocusActive) {
                    Label("Mac Keyboard Capture", systemImage: "keyboard")
                        .font(.system(size: 11, weight: .medium))
                }
                .toggleStyle(.button)
                .tint(pal.primaryAccent)
                .help("Capture trackpad scroll & keystrokes when hovering/clicking the mirrored phone")

                Button {
                    viewModel.openFloatingMirrorWindow()
                } label: {
                    Label("Float Phone (⌘M)", systemImage: "macwindow.on.rectangle")
                        .font(.system(size: 12, weight: .semibold))
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .background(Capsule().fill(pal.secondaryAccent))
                        .foregroundStyle(pal.activeCardInk)
                }
                .buttonStyle(.plain)

                Button {
                    viewModel.toggleViewingPhoneScreen()
                } label: {
                    Label(
                        viewModel.isViewingRemoteScreen ? "Stop Mirror" : "Mirror Phone",
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
            .padding(14)
            .liquidGlassCard(pal, cornerRadius: 16)

            // Main Framed iPhone-Mirroring-Style Viewport + Controls
            VStack(spacing: 12) {
                HStack {
                    HStack(spacing: 6) {
                        Circle()
                            .fill(viewModel.remoteScreenImage != nil || viewModel.isSharingLocalScreen ? pal.secondaryAccent : Color.orange)
                            .frame(width: 7, height: 7)
                        Text(viewModel.remoteScreenImage != nil
                            ? "Live Android \(viewModel.remoteScreenSizeText) • \(viewModel.remoteMirrorFps) fps"
                            : (viewModel.isSharingLocalScreen ? "Broadcasting macOS Display to \(viewModel.peerName)" : "Standby"))
                            .font(.system(size: 11, weight: .medium, design: .monospaced))
                    }
                    Spacer()
                    Text(viewModel.bridgeState == .activeWifiStream ? "Wi-Fi mTLS 1.3 (24 fps)" : "BLE Preview (3 fps)")
                        .font(.system(size: 10, design: .monospaced))
                        .foregroundStyle(pal.secondaryText)
                }

                // Dark Titanium Phone Bezel
                VStack(spacing: 8) {
                    // Speaker / front sensor notch
                    Capsule()
                        .fill(Color.white.opacity(0.16))
                        .frame(width: 54, height: 5)
                        .padding(.top, 8)

                    ZStack {
                        RoundedRectangle(cornerRadius: 24, style: .continuous)
                            .fill(Color.black)

                        if let nsImg = viewModel.remoteScreenImage {
                            GeometryReader { geo in
                                ZStack {
                                    Image(nsImage: nsImg)
                                        .resizable()
                                        .aspectRatio(contentMode: .fit)
                                        .frame(width: geo.size.width, height: geo.size.height)

                                    ForEach(uiState.ripples) { ripple in
                                        Circle()
                                            .stroke(pal.primaryAccent, lineWidth: 2.5)
                                            .frame(width: ripple.isDrag ? 36 : 26, height: ripple.isDrag ? 36 : 26)
                                            .position(ripple.point)
                                    }
                                }
                                .contentShape(Rectangle())
                                .gesture(
                                    DragGesture(minimumDistance: 0)
                                        .onChanged { value in
                                            if uiState.dragStartPoint == nil {
                                                uiState.dragStartPoint = value.startLocation
                                                uiState.dragStartTime = Date()
                                            }
                                        }
                                        .onEnded { value in
                                            let start = uiState.dragStartPoint ?? value.startLocation
                                            let end = value.location
                                            let startedAt = uiState.dragStartTime ?? Date()
                                            uiState.dragStartPoint = nil
                                            uiState.dragStartTime = nil
                                            uiState.isKeyboardFocusActive = true

                                            let w = max(1, geo.size.width)
                                            let h = max(1, geo.size.height)
                                            let nx1 = min(1.0, max(0.0, Double(start.x / w)))
                                            let ny1 = min(1.0, max(0.0, Double(start.y / h)))
                                            let nx2 = min(1.0, max(0.0, Double(end.x / w)))
                                            let ny2 = min(1.0, max(0.0, Double(end.y / h)))

                                            let dist = hypot(end.x - start.x, end.y - start.y)
                                            let elapsed = Date().timeIntervalSince(startedAt)

                                            spawnRipple(at: end, isDrag: dist >= 10)

                                            if dist >= 10 {
                                                viewModel.sendRemoteInput(.swipe(x1: nx1, y1: ny1, x2: nx2, y2: ny2, durationMs: 220))
                                            } else if elapsed >= 0.4 {
                                                viewModel.sendRemoteInput(.longPress(x: nx2, y: ny2, durationMs: 550))
                                            } else {
                                                viewModel.sendRemoteInput(.tap(x: nx2, y: ny2))
                                            }
                                        }
                                )
                            }
                        } else {
                            VStack(spacing: 12) {
                                Image(systemName: "iphone.gen3.radiowaves.left.and.right")
                                    .font(.system(size: 38, weight: .light))
                                    .foregroundStyle(pal.secondaryAccent)
                                Text(viewModel.isSharingLocalScreen
                                    ? "Streaming your macOS screen to \(viewModel.peerName)..."
                                    : "Click Mirror Phone to project & control your Android screen using your Mac mouse and keyboard.")
                                    .font(.system(size: 12))
                                    .foregroundStyle(pal.secondaryText)
                                    .multilineTextAlignment(.center)
                                    .padding(.horizontal, 18)

                                Button("Open macOS Screen Recording Privacy") {
                                    if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture") {
                                        NSWorkspace.shared.open(url)
                                    }
                                }
                                .font(.system(size: 11))
                                .buttonStyle(.link)
                            }
                        }
                    }
                    .aspectRatio(frameAspectRatio, contentMode: .fit)
                    .frame(maxHeight: 400)
                    .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
                    .padding(.horizontal, 10)
                    .padding(.bottom, 10)
                }
                .background(
                    RoundedRectangle(cornerRadius: 32, style: .continuous)
                        .fill(Color(red: 0.08, green: 0.09, blue: 0.11))
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 32, style: .continuous)
                        .stroke(pal.outlineVariant, lineWidth: 2)
                )
                .frame(maxWidth: .infinity)

                // Hardware Pill Bar (Back, Home, Recents, Inline Text)
                HStack(spacing: 8) {
                    navPillButton(icon: "chevron.left", title: "Back") {
                        viewModel.sendRemoteInput(.key("back"))
                    }
                    navPillButton(icon: "circle", title: "Home") {
                        viewModel.sendRemoteInput(.key("home"))
                    }
                    navPillButton(icon: "square.stack", title: "Recents") {
                        viewModel.sendRemoteInput(.key("recents"))
                    }

                    HStack(spacing: 6) {
                        TextField("Type into Android input...", text: $uiState.textToType)
                            .textFieldStyle(.plain)
                            .font(.system(size: 12))
                            .padding(.horizontal, 10)
                            .padding(.vertical, 6)
                            .background(RoundedRectangle(cornerRadius: 8).fill(pal.surfaceLowest))
                            .overlay(RoundedRectangle(cornerRadius: 8).stroke(pal.outlineVariant, lineWidth: 1))
                            .onSubmit { submitRemoteText() }

                        Button("Send Text") {
                            submitRemoteText()
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
            .padding(16)
            .liquidGlassCard(pal, cornerRadius: 18)
        }
        .onAppear { installLocalEventMonitor() }
        .onDisappear { removeLocalEventMonitor() }
    }

    private func navPillButton(icon: String, title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 5) {
                Image(systemName: icon)
                    .font(.system(size: 11, weight: .semibold))
                Text(title)
                    .font(.system(size: 11, weight: .medium))
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(Capsule().fill(pal.surfaceHigh))
            .foregroundStyle(pal.onSurface)
        }
        .buttonStyle(.plain)
    }

    private func submitRemoteText() {
        let trimmed = uiState.textToType
        guard !trimmed.isEmpty else { return }
        viewModel.sendRemoteInput(.key("text", text: trimmed))
        uiState.textToType = ""
    }

    private func spawnRipple(at point: CGPoint, isDrag: Bool) {
        let item = CursorRipple(point: point, isDrag: isDrag)
        uiState.ripples.append(item)
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
            uiState.ripples.removeAll { $0.id == item.id }
        }
    }

    private func installLocalEventMonitor() {
        removeLocalEventMonitor()
        uiState.eventMonitor = NSEvent.addLocalMonitorForEvents(matching: [.scrollWheel, .keyDown]) { event in
            guard viewModel.selectedTab == .screenMirror, viewModel.remoteScreenImage != nil else {
                return event
            }
            if event.type == .scrollWheel {
                let now = Date()
                guard now.timeIntervalSince(uiState.lastScrollSentAt) >= 0.14 else { return event }
                let dy = event.scrollingDeltaY
                guard abs(dy) > 2 else { return event }
                uiState.lastScrollSentAt = now
                let y2 = dy > 0 ? 0.72 : 0.28
                viewModel.sendRemoteInput(.swipe(x1: 0.5, y1: 0.5, x2: 0.5, y2: y2, durationMs: 180))
                return event
            }
            if event.type == .keyDown && uiState.isKeyboardFocusActive {
                if event.keyCode == 53 { // Escape -> Back
                    viewModel.sendRemoteInput(.key("back"))
                    return nil
                }
                if let chars = event.characters, !chars.isEmpty,
                   !chars.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }) {
                    viewModel.sendRemoteInput(.key("text", text: chars))
                    return nil
                }
            }
            return event
        }
    }

    private func removeLocalEventMonitor() {
        if let monitor = uiState.eventMonitor {
            NSEvent.removeMonitor(monitor)
            uiState.eventMonitor = nil
        }
    }
}
