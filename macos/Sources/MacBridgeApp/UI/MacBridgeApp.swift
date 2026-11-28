import AppKit
import MacBridgeCore
import SwiftUI
import UserNotifications

@MainActor
final class MacBridgeAppDelegate: NSObject, NSApplicationDelegate {
    var onWillTerminate: (() -> Void)?
    var onReopenMainWindow: (() -> Void)?

    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.regular)
        NSApp.activate(ignoringOtherApps: true)
    }

    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        if !flag {
            onReopenMainWindow?()
        }
        NSApp.activate(ignoringOtherApps: true)
        return true
    }

    func applicationWillTerminate(_ notification: Notification) {
        onWillTerminate?()
    }
}

@main
struct MacBridgeApp: App {
    @NSApplicationDelegateAdaptor(MacBridgeAppDelegate.self) private var appDelegate
    @StateObject private var viewModel = BridgeDashboardViewModel()
    @Environment(\.openWindow) private var openWindow

    var body: some Scene {
        Window("Link-Me Studio", id: "studio") {
            BridgeStudioWindow(viewModel: viewModel)
                .onAppear {
                    let vm = viewModel
                    appDelegate.onWillTerminate = { [weak vm] in
                        vm?.teardownOnQuit()
                    }
                    appDelegate.onReopenMainWindow = {
                        openWindow(id: "studio")
                    }
                    NSApp.activate(ignoringOtherApps: true)
                }
        }
        .defaultSize(width: 980, height: 680)

        MenuBarExtra {
            MenuBarDashboardView(viewModel: viewModel)
        } label: {
            Image(nsImage: MenuBarVectorIcon.makeTemplateImage(isActive: viewModel.isBluetoothConnected || viewModel.bridgeState == .activeWifiStream))
        }
        .menuBarExtraStyle(.window)
        .commands {
            CommandMenu("Mirror") {
                Button("Mirror Phone") {
                    viewModel.openFloatingMirrorWindow()
                }
                .keyboardShortcut("m", modifiers: .command)

                Button("Open Link-Me Studio") {
                    openWindow(id: "studio")
                    NSApp.activate(ignoringOtherApps: true)
                }
            }
        }
    }
}

/// Custom vector interlocking link-bridge menu bar emblem rendered cleanly via NSBezierPath.
enum MenuBarVectorIcon {
    static func makeTemplateImage(isActive: Bool) -> NSImage {
        let size = NSSize(width: 18, height: 18)
        let image = NSImage(size: size, flipped: false) { _ in
            NSColor.labelColor.setStroke()
            let leftRect = NSRect(x: 2.0, y: 6.5, width: 8.5, height: 5.5)
            let leftPath = NSBezierPath(roundedRect: leftRect, xRadius: 2.75, yRadius: 2.75)
            leftPath.lineWidth = isActive ? 1.8 : 1.5
            leftPath.stroke()

            let rightRect = NSRect(x: 7.5, y: 5.5, width: 8.5, height: 5.5)
            let rightPath = NSBezierPath(roundedRect: rightRect, xRadius: 2.75, yRadius: 2.75)
            rightPath.lineWidth = isActive ? 1.8 : 1.5
            rightPath.stroke()
            return true
        }
        image.isTemplate = true
        return image
    }
}

enum StudioTab: String, CaseIterable {
    case bridge = "Bridge"
    case pairing = "Secure Pairing"
    case screenMirror = "Screen Mirror"
    case activity = "Activity"
    case customize = "Customize"
    case diagnostics = "Diagnostics"
}

enum ActivityFilter: String, CaseIterable {
    case all = "All"
    case files = "Files"
    case clipboard = "Clipboard"
    case media = "Media"
    case system = "System"
}

enum ActivityCategory: String {
    case files = "Files"
    case clipboard = "Clipboard"
    case media = "Media"
    case system = "System"
}

struct BridgeActivityRecord: Identifiable {
    let id = UUID()
    let category: ActivityCategory
    let title: String
    let detail: String
    let badgeText: String
    let timestamp: Date
    let clipboardText: String?
    let fileURL: URL?

    init(
        category: ActivityCategory,
        title: String,
        detail: String,
        badgeText: String = "OK",
        timestamp: Date = Date(),
        clipboardText: String? = nil,
        fileURL: URL? = nil
    ) {
        self.category = category
        self.title = title
        self.detail = detail
        self.badgeText = badgeText
        self.timestamp = timestamp
        self.clipboardText = clipboardText
        self.fileURL = fileURL
    }
}

struct PendingIncomingOffer: Identifiable {
    let id: String
    let offer: FileOfferPayload
}

@MainActor
final class BridgeDashboardViewModel: ObservableObject {
    @Published var selectedTab: StudioTab = .bridge
    @Published var activityFilter: ActivityFilter = .all
    @Published var activitySearchQuery: String = ""
    @Published var bridgeState: BridgeState = .dormantBle
    @Published var peerName: String = "No Device Paired"
    @Published var peerDeviceId: String = ""
    @Published var peerCertFingerprint: String = ""
    @Published var peerBatteryPercent: Int? = nil
    @Published var peerIsCharging: Bool = false
    @Published var localBatteryPercent: Int? = MacBatteryMonitor.readBattery().level
    @Published var localIsCharging: Bool = MacBatteryMonitor.readBattery().isCharging
    @Published var peerPaired: Bool = false

    // Live Screen Mirroring state (0x0C / 0x0D / 0x0E)
    @Published var isViewingRemoteScreen: Bool = false
    @Published var isSharingLocalScreen: Bool = false
    @Published var isMirrorAuthenticated: Bool = false
    @Published var isMirrorStealthBlack: Bool = false
    @Published var remoteScreenImage: NSImage?
    @Published var remoteScreenWidth: Int = 1080
    @Published var remoteScreenHeight: Int = 2400
    @Published var remoteScreenSizeText: String = ""
    @Published var remoteMirrorFps: Int = 0
    @Published var mirrorStatusText: String = "Standby"
    private var mirrorWindowController: PhoneMirrorWindowController?
    @Published var isBluetoothConnected: Bool = false
    @Published var latencyMs: Int?
    @Published var clipboardSyncEnabled: Bool = true
    @Published var notificationMirrorEnabled: Bool = true
    @Published var activeTransfer: FileTransferProgress?
    @Published var pendingIncomingOffer: PendingIncomingOffer?
    @Published var recentNotifications: [MirroredNotificationItem] = []
    @Published var activityLog: [BridgeActivityRecord] = []
    @Published var urlDraft: String = "https://developer.android.com/studio"
    @Published var isShowingPairingSheet: Bool = false
    @Published var isDropTargeted: Bool = false
    @Published var pairingRemainingSeconds: Int = 300
    @Published var pairingUriString: String = ""
    @Published var pairingExpiresAt: Date = Date().addingTimeInterval(300)
    @Published var pendingApproval: PendingPairingApproval?
    @Published var statusBanner: String?

    // Diagnostics & real hardware state
    @Published var peripheralDiagnostics = BlePeripheralDiagnostics()
    @Published var wifiMtlsStatus: String = "Standby"
    @Published var wireEventLog: [String] = []
    @Published var pingTestStatus: String?
    @Published var localCertFingerprint: String = ""
    @Published var localListeningPort: UInt16 = 49152
    @Published var loopbackDiagnosticsResult: String?

    private var pendingPingStartByNonce: [String: Date] = [:]
    private static let diagTimeFormatter: DateFormatter = {
        let df = DateFormatter()
        df.dateFormat = "HH:mm:ss.SSS"
        return df
    }()

    let preferences = BridgePreferences()
    let transportManager = TransportManager()
    let identityManager = IdentityManager()
    let trustStore = TrustStore()
    lazy var pairingFeature = PairingFeature(identityManager: identityManager, trustStore: trustStore)
    lazy var clipboardManager = ClipboardSyncManager(
        deviceId: localDeviceId,
        transport: transportManager
    )
    let fileTransferManager = FileTransferManager()
    lazy var notificationManager = MacNotificationManager(transport: transportManager)
    lazy var telemetryFeature = TelemetryFeature(transport: transportManager)
    lazy var continuityController = MacContinuityController(transport: transportManager)
    let screenMirrorManager = ScreenMirrorManager()

    private var localDeviceId: String = "11111111-1111-4111-8111-111111111111"
    private var localIdentity: DeviceIdentity?
    private var bleCentralManager: BleCentralManager?
    private var blePeripheralManager: BlePeripheralManager?
    private var wifiTlsServer: WifiTlsServer?
    private var wifiTlsClient: WifiTlsClient?
    private var activeTransferId: UUID?
    private var telemetryLoopTask: Task<Void, Never>?
    private var mirrorFpsWindowStart: Date = Date()
    private var mirrorFrameCounter: Int = 0
    private var isBootstrapped = false

    var localBatteryLabel: String {
        localBatteryPercent.map { "\($0)%" } ?? "AC Power"
    }

    var peerBatteryLabel: String {
        peerBatteryPercent.map { "\($0)%" } ?? "--"
    }

    var peerBatteryFraction: CGFloat {
        CGFloat(max(0, min(100, peerBatteryPercent ?? 0))) / 100.0
    }

    var palette: StudioThemePalette {
        StudioThemePalette.from(preferences.paletteSpec)
    }

    init() {
        bootstrap()
    }

    var filteredActivities: [BridgeActivityRecord] {
        let byCategory: [BridgeActivityRecord]
        switch activityFilter {
        case .all: byCategory = activityLog
        case .files: byCategory = activityLog.filter { $0.category == .files }
        case .clipboard: byCategory = activityLog.filter { $0.category == .clipboard }
        case .media: byCategory = activityLog.filter { $0.category == .media }
        case .system: byCategory = activityLog.filter { $0.category == .system }
        }
        let q = activitySearchQuery.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !q.isEmpty else { return byCategory }
        return byCategory.filter {
            $0.title.lowercased().contains(q) || $0.detail.lowercased().contains(q)
        }
    }

    func bootstrap() {
        guard !isBootstrapped else { return }
        isBootstrapped = true

        if let identity = try? identityManager.loadOrCreateIdentity() {
            localIdentity = identity
            localDeviceId = identity.deviceId
            localCertFingerprint = identity.certificateSha256Hex
        }

        preferences.onPreferencesChanged = { [weak self] snapshot in
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.objectWillChange.send()
                await self.applyPreferencesSnapshot(snapshot)
            }
        }

        let central = BleCentralManager(trustStore: trustStore)
        central.onVerifiedPeerDiscovered = { [weak self] peer in
            Task { @MainActor in
                guard let self else { return }
                self.peerPaired = true
                self.peerName = peer.deviceName
                self.peerDeviceId = peer.deviceId
                self.peerCertFingerprint = peer.pinnedCertSha256
                self.statusBanner = "Bluetooth E2EE linked: \(peer.deviceName)"
                self.appendActivity(
                    BridgeActivityRecord(
                        category: .system,
                        title: "Bluetooth Peer Connected",
                        detail: peer.deviceName,
                        badgeText: "BLE E2EE"
                    )
                )
                await self.clipboardManager.setPeerReachable(true)
            }
        }
        central.onConnectionStateChanged = { [weak self] connected in
            Task { @MainActor in
                guard let self else { return }
                self.isBluetoothConnected = connected || (self.peripheralDiagnostics.subscribedCentralsCount > 0)
                await self.clipboardManager.setPeerReachable(self.isBluetoothConnected || self.peerPaired)
                if connected {
                    await self.emitLocalTelemetryOnce()
                }
            }
        }
        central.onLatencyMeasured = { [weak self] rtt in
            Task { @MainActor in
                self?.latencyMs = rtt
            }
        }
        central.onInboundFrame = { [weak self] frame in
            Task { await self?.handleInboundFrame(frame) }
        }
        bleCentralManager = central

        let peripheral = BlePeripheralManager(trustStore: trustStore)
        peripheral.onDiagnosticsChanged = { [weak self] diag in
            Task { @MainActor in
                guard let self else { return }
                let hadSubscribers = self.peripheralDiagnostics.subscribedCentralsCount > 0
                self.peripheralDiagnostics = diag
                if let latest = diag.eventLog.first {
                    self.recordWireEvent(latest)
                }
                self.isBluetoothConnected = (diag.subscribedCentralsCount > 0) || (self.bleCentralManager?.isConnected == true)
                await self.clipboardManager.setPeerReachable(self.isBluetoothConnected || self.peerPaired)
                if !hadSubscribers && diag.subscribedCentralsCount > 0 && self.peerPaired {
                    await self.emitLocalTelemetryOnce()
                }
            }
        }
        peripheral.onInboundFrame = { [weak self] frame in
            Task { await self?.handleInboundFrame(frame) }
        }
        blePeripheralManager = peripheral
        peripheral.start(localName: preferences.normalizedAlias)

        if let firstPeer = (try? trustStore.allPeers())?.first {
            peerPaired = true
            peerName = firstPeer.deviceName
            peerDeviceId = firstPeer.deviceId
            peerCertFingerprint = firstPeer.pinnedCertSha256
            central.startScanning()
        } else {
            peerPaired = false
            peerName = "No Device Paired"
        }

        UNUserNotificationCenter.current().setNotificationCategories([
            MacNotificationManager.makeNotificationCategory()
        ])

        Task { [weak self] in
            guard let self else { return }
            await self.applyPreferencesSnapshot(self.preferences.snapshot)
            await self.clipboardManager.setPeerReachable(self.peerPaired)
            try? await self.pairingFeature.startTemporaryListener(
                onReadyPort: { [weak self] port in
                    Task { @MainActor [weak self] in
                        guard let self else { return }
                        self.localListeningPort = port
                        if !self.peerPaired {
                            await self.refreshPairingSession()
                        }
                    }
                },
                onPairHelloReceived: { [weak self] hello in
                    Task { @MainActor [weak self] in
                        await self?.processIncomingPairHello(hello)
                    }
                },
                onPairCommitReceived: { [weak self] commit in
                    guard let self else { return nil }
                    return await self.confirmPeerFromCommit(commit)
                }
            )
            await self.refreshPairingSession()
            self.startTelemetryTimer()

            await self.transportManager.configureTransportHooks(
                bleSender: { [weak central, weak peripheral] frame in
                    if central?.isConnected == true {
                        central?.sendControlFrame(frame)
                    } else {
                        peripheral?.sendFrame(frame)
                    }
                },
                wifiOpener: { [weak self] in
                    guard let self else { return 49152 }
                    return try await self.openOrStartWifiServer()
                },
                wifiSender: { [weak self] frame in
                    guard let self else { return }
                    try await self.sendOverWifi(frame)
                },
                wifiCloser: { [weak self] in
                    await self?.closeWifiTransport()
                },
                localHostHintProvider: {
                    PairingFeature.localLanIPv4Address()
                }
            )
        }
    }

    func applyPreferencesSnapshot(_ snapshot: BridgePreferencesSnapshot) async {
        await transportManager.setCooldownDuration(seconds: snapshot.wifiCooldownSeconds)
        await transportManager.setBleOnlyBatterySaver(snapshot.bleOnlyBatterySaver)
        await clipboardManager.setDirection(snapshot.clipboardDirection)
    }

    func appendActivity(_ record: BridgeActivityRecord) {
        activityLog.insert(record, at: 0)
        if activityLog.count > 80 {
            activityLog.removeLast(activityLog.count - 80)
        }
    }

    func recordWireEvent(_ text: String) {
        let entry: String
        if text.hasPrefix("[") {
            entry = text
        } else {
            entry = "[\(Self.diagTimeFormatter.string(from: Date()))] \(text)"
        }
        if wireEventLog.first == entry { return }
        wireEventLog.insert(entry, at: 0)
        if wireEventLog.count > 40 {
            wireEventLog.removeLast(wireEventLog.count - 40)
        }
    }

    var bleLinkSummary: String {
        if peripheralDiagnostics.subscribedCentralsCount > 0 || bleCentralManager?.isConnected == true {
            return "GATT Connected + CCCD Ready"
        }
        return peripheralDiagnostics.linkStateLabel
    }

    var combinedDiagnosticsEvents: [String] {
        var seen = Set<String>()
        var merged: [String] = []
        for item in wireEventLog + peripheralDiagnostics.eventLog {
            if seen.insert(item).inserted {
                merged.append(item)
            }
        }
        return Array(merged.prefix(10))
    }

    func copyDiagnosticsToClipboard() {
        let events = combinedDiagnosticsEvents.isEmpty
            ? "(none)"
            : combinedDiagnosticsEvents.joined(separator: "\n")
        let report = """
        Link-Me Connection Diagnostics (macOS)
        BLE Radio State: \(peripheralDiagnostics.radioStateLabel)
        BLE Link: \(bleLinkSummary)
        Wi-Fi mTLS: \(wifiMtlsStatus) (Pairing TCP :\(localListeningPort))
        Peer: \(peerName) • Paired=\(peerPaired) • Latency=\(latencyMs.map { "\($0)ms" } ?? "--")
        Last Wire Events:
        \(events)
        """
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(report, forType: .string)
        statusBanner = "Copied diagnostics log to clipboard"
    }

    func sendPing01() {
        let nonce = String(UUID().uuidString.prefix(8)).lowercased()
        let start = Date()
        pendingPingStartByNonce[nonce] = start
        pingTestStatus = "Sending Ping (0x01)..."
        let battery = MacBatteryMonitor.readBattery()
        let payload = PingTelemetryPayload(
            deviceId: localDeviceId,
            batteryLevel: battery.level ?? 100,
            isCharging: battery.isCharging,
            batterySaverActive: false,
            transportState: bridgeState,
            timestampMs: UInt64(start.timeIntervalSince1970 * 1000),
            pingNonce: nonce,
            isPong: false
        )
        Task { [weak self] in
            guard let self else { return }
            do {
                let data = try JSONEncoder().encode(payload)
                let frame = try await self.transportManager.makeFrame(messageType: .pingTelemetry, payload: data)
                try await self.transportManager.sendResilientFrame(frame, preferBle: true)
                self.recordWireEvent("TX 0x01 PING_TELEMETRY (\(data.count)B, nonce=\(nonce))")
                try? await Task.sleep(for: .seconds(4))
                if self.pendingPingStartByNonce.removeValue(forKey: nonce) != nil {
                    self.pingTestStatus = "No 0x01 Pong in 4s"
                    self.recordWireEvent("TIMEOUT 0x01 PING (nonce=\(nonce))")
                }
            } catch {
                self.pendingPingStartByNonce.removeValue(forKey: nonce)
                self.pingTestStatus = "Ping error: \(error.localizedDescription)"
                self.recordWireEvent("ERR 0x01 PING: \(error.localizedDescription)")
            }
        }
    }

    func teardownOnQuit() {
        telemetryLoopTask?.cancel()
        telemetryLoopTask = nil
        mirrorWindowController?.closeMirrorWindow()
        mirrorWindowController = nil
        Task { await screenMirrorManager.stopSharing() }
        bleCentralManager?.stopScanning()
        blePeripheralManager?.stop()
        wifiTlsClient?.close()
        wifiTlsClient = nil
        wifiTlsServer?.stop()
        wifiTlsServer = nil
        Task {
            await clipboardManager.setPeerReachable(false)
        }
    }

    private func startTelemetryTimer() {
        telemetryLoopTask?.cancel()
        telemetryLoopTask = Task { [weak self] in
            while !Task.isCancelled {
                if let self, self.peerPaired {
                    await self.emitLocalTelemetryOnce()
                }
                try? await Task.sleep(for: .seconds(60))
            }
        }
    }

    private func emitLocalTelemetryOnce() async {
        let reading = MacBatteryMonitor.readBattery()
        localBatteryPercent = reading.level
        localIsCharging = reading.isCharging
        guard peerPaired else { return }
        let state = await transportManager.bridgeState
        _ = try? await telemetryFeature.emitLocalTelemetry(deviceId: localDeviceId, bridgeState: state)
    }

    private func confirmPeerFromCommit(_ commit: PairCommitPayload) async -> PairAckPayload? {
        guard let result = try? await pairingFeature.handleIncomingPairCommit(commit) else {
            return nil
        }
        peerPaired = true
        peerName = result.peer.deviceName
        peerDeviceId = result.peer.deviceId
        peerCertFingerprint = result.peer.pinnedCertSha256
        peerBatteryPercent = commit.batteryLevel
        peerIsCharging = commit.isCharging
        isShowingPairingSheet = false
        pendingApproval = nil
        bleCentralManager?.startScanning()
        await clipboardManager.setPeerReachable(true)
        statusBanner = "Paired with \(result.peer.deviceName) • \(commit.batteryLevel)%"
        appendActivity(
            BridgeActivityRecord(
                category: .system,
                title: "Device Paired (PAIR_COMMIT)",
                detail: "\(result.peer.deviceName) • \(commit.batteryLevel)%",
                badgeText: "Verified"
            )
        )
        return result.ack
    }

    private func sendPairAckOverActiveLinks(_ ack: PairAckPayload) {
        guard let ackData = try? JSONEncoder().encode(ack),
              let ackFrame = try? WireFrame(
                messageType: .pairAck,
                sequenceId: UInt64(Date().timeIntervalSince1970 * 1000),
                payload: ackData
              ) else {
            return
        }
        blePeripheralManager?.sendFrame(ackFrame)
        bleCentralManager?.sendControlFrame(ackFrame)
        Task {
            try? await transportManager.sendResilientFrame(ackFrame, preferBle: true)
        }
    }

    private func postFileSavedNotification(filename: String, fileURL: URL?) {
        let content = UNMutableNotificationContent()
        content.title = "File Received from \(peerName)"
        content.body = "\(filename) saved to ~/Downloads/Link-Me"
        content.sound = .default
        if let fileURL {
            content.userInfo = ["filePath": fileURL.path]
        }
        let req = UNNotificationRequest(
            identifier: "linkme-file-\(UUID().uuidString)",
            content: content,
            trigger: nil
        )
        UNUserNotificationCenter.current().add(req)
    }

    func openFloatingMirrorWindow() {
        if mirrorWindowController == nil {
            mirrorWindowController = PhoneMirrorWindowController(viewModel: self)
        }
        mirrorWindowController?.showAndAuthenticateIfNeeded()
    }

    func startPhoneMirrorStream() {
        if !isViewingRemoteScreen {
            isViewingRemoteScreen = true
        }
        mirrorStatusText = "Requesting \(peerName) screen..."
        mirrorWindowController?.syncFromViewModel()
        let fps = bridgeState == .activeWifiStream ? 60 : 12
        let ctrl = ScreenControlPayload(
            action: "start",
            source: "android",
            fps: fps,
            quality: 65,
            wake: true,
            stealthBlack: isMirrorStealthBlack
        )
        Task {
            if let data = try? JSONEncoder().encode(ctrl),
               let frame = try? await transportManager.makeFrame(messageType: .screenControl, payload: data) {
                try? await transportManager.sendResilientFrame(frame, preferBle: true)
            }
        }
    }

    func toggleMirrorStealthBlack() {
        isMirrorStealthBlack.toggle()
        mirrorWindowController?.syncFromViewModel()
        if isViewingRemoteScreen {
            startPhoneMirrorStream()
        }
    }

    func stopPhoneMirrorStream() {
        isViewingRemoteScreen = false
        isMirrorStealthBlack = false
        remoteScreenImage = nil
        remoteMirrorFps = 0
        mirrorStatusText = "Standby"
        mirrorWindowController?.syncFromViewModel()
        let ctrl = ScreenControlPayload(
            action: "stop",
            source: "android",
            fps: 24,
            quality: 65,
            wake: false,
            stealthBlack: false
        )
        Task {
            if let data = try? JSONEncoder().encode(ctrl),
               let frame = try? await transportManager.makeFrame(messageType: .screenControl, payload: data) {
                try? await transportManager.sendResilientFrame(frame, preferBle: true)
            }
        }
    }

    func toggleViewingPhoneScreen() {
        if isViewingRemoteScreen {
            stopPhoneMirrorStream()
        } else {
            startPhoneMirrorStream()
        }
    }

    func sendRemoteInput(_ input: RemoteInputPayload) {
        Task {
            if let data = try? JSONEncoder().encode(input),
               let frame = try? await transportManager.makeFrame(messageType: .remoteInput, payload: data) {
                try? await transportManager.sendResilientFrame(frame, preferBle: true)
            }
        }
    }

    func toggleSharingMacScreen() {
        if isSharingLocalScreen {
            Task { await stopSharingMacScreen() }
        } else {
            let fps = bridgeState == .activeWifiStream ? 24 : 3
            Task { await startSharingMacScreen(fps: fps, quality: 60) }
        }
    }

    private func startSharingMacScreen(fps: Int, quality: Int) async {
        let bleOnly = bridgeState != .activeWifiStream
        isSharingLocalScreen = true
        mirrorStatusText = "Starting macOS display capture..."
        do {
            try await screenMirrorManager.startSharing(
                fps: fps,
                qualityPercent: quality,
                overBleOnly: bleOnly,
                onFrame: { [weak self] packet in
                    guard let self else { return }
                    Task {
                        if let frame = try? await self.transportManager.makeFrame(
                            messageType: .screenFrame,
                            payload: packet.encode()
                        ) {
                            try? await self.transportManager.sendResilientFrame(frame, preferBle: false)
                        }
                    }
                },
                onStatus: { [weak self] status in
                    Task { @MainActor [weak self] in
                        self?.mirrorStatusText = status
                    }
                }
            )
            appendActivity(
                BridgeActivityRecord(
                    category: .system,
                    title: "Screen Mirroring Started",
                    detail: "Streaming macOS screen to \(peerName)",
                    badgeText: "Live"
                )
            )
        } catch {
            isSharingLocalScreen = false
            mirrorStatusText = "Screen Recording permission required: \(error.localizedDescription)"
        }
    }

    private func stopSharingMacScreen() async {
        await screenMirrorManager.stopSharing()
        isSharingLocalScreen = false
        mirrorStatusText = "Standby"
    }

    private func openOrStartWifiServer() async throws -> UInt16 {
        guard let identity = localIdentity,
              let peer = (try? trustStore.allPeers())?.first else {
            return 49152
        }
        let server = WifiTlsServer(
            identity: identity,
            expectedPeerDeviceId: peer.deviceId,
            trustStore: trustStore
        )
        server.onFrameReceived = { [weak self] frame in
            Task { await self?.handleInboundFrame(frame) }
        }
        server.onStatusChanged = { [weak self] status in
            Task { @MainActor [weak self] in
                self?.wifiMtlsStatus = status
                self?.recordWireEvent("Wi-Fi mTLS: \(status)")
            }
        }
        wifiTlsServer = server
        let port = try await server.start(port: 0, advertiseBonjour: true)
        wifiMtlsStatus = "Listening :\(port)"
        recordWireEvent("Wi-Fi mTLS Listening :\(port)")
        appendActivity(
            BridgeActivityRecord(
                category: .system,
                title: "Wi-Fi mTLS 1.3 Active",
                detail: "Listening :\(port) • Cooldown \(preferences.wifiCooldownSeconds)s",
                badgeText: "TLS 1.3"
            )
        )
        return port
    }

    private func sendOverWifi(_ frame: WireFrame) async throws {
        if let client = wifiTlsClient {
            try await client.send(frame: frame)
            return
        }
        if let server = wifiTlsServer {
            if !server.hasActiveClients {
                let connected = await server.waitForClientConnection(timeoutSeconds: 1.8)
                guard connected else {
                    throw WifiTlsError.connectionClosed
                }
            }
            try await server.send(frame: frame)
            return
        }
        throw WifiTlsError.connectionClosed
    }

    private func closeWifiTransport() async {
        wifiTlsClient?.close()
        wifiTlsClient = nil
        wifiTlsServer?.stop()
        wifiTlsServer = nil
        wifiMtlsStatus = "Standby (Pairing :\(localListeningPort))"
        bridgeState = .dormantBle
        appendActivity(
            BridgeActivityRecord(
                category: .system,
                title: "Returned to Bluetooth E2EE Standby",
                detail: "Wi-Fi cooldown expired",
                badgeText: "BLE E2EE"
            )
        )
    }

    private func processIncomingPairHello(_ hello: PairHelloPayload) async {
        if let approval = try? await pairingFeature.handleIncomingPairHello(hello) {
            pendingApproval = approval
            statusBanner = "Verify 6-digit SAS: \(approval.sasCode)"
            appendActivity(
                BridgeActivityRecord(
                    category: .system,
                    title: "Pairing Request Received",
                    detail: "\(hello.deviceName) • SAS \(approval.sasCode)",
                    badgeText: "SAS Ready"
                )
            )
        }
    }

    private func handleInboundFrame(_ frame: WireFrame) async {
        let hexOpcode = String(format: "0x%02X", frame.messageType.rawValue)
        recordWireEvent("RX \(hexOpcode) (\(frame.payload.count)B)")
        switch frame.messageType {
        case .pairHello:
            if let hello = try? JSONDecoder().decode(PairHelloPayload.self, from: frame.payload) {
                await processIncomingPairHello(hello)
            }

        case .pairCommit:
            if let commit = try? JSONDecoder().decode(PairCommitPayload.self, from: frame.payload),
               let ack = await confirmPeerFromCommit(commit) {
                sendPairAckOverActiveLinks(ack)
                await emitLocalTelemetryOnce()
            }

        case .pairAck:
            if let ack = try? JSONDecoder().decode(PairAckPayload.self, from: frame.payload) {
                peerPaired = true
                peerName = ack.deviceName
                peerDeviceId = ack.deviceId
                peerCertFingerprint = ack.spkiSha256
                peerBatteryPercent = ack.batteryLevel
                peerIsCharging = ack.isCharging
                await clipboardManager.setPeerReachable(true)
            }

        case .wakeWifi:
            if let wake = try? JSONDecoder().decode(WakeWifiPayload.self, from: frame.payload),
               let identity = localIdentity,
               let peer = (try? trustStore.allPeers())?.first,
               let hostHint = wake.hostHints.first(where: { !$0.hasSuffix(".local.") }) {
                let client = WifiTlsClient(
                    identity: identity,
                    expectedPeerDeviceId: peer.deviceId,
                    trustStore: trustStore
                )
                client.onFrameReceived = { [weak self] inbound in
                    Task { await self?.handleInboundFrame(inbound) }
                }
                do {
                    try await client.connect(host: hostHint, port: wake.servicePort)
                    wifiTlsClient = client
                    bridgeState = .activeWifiStream
                    wifiMtlsStatus = "Connected to \(hostHint):\(wake.servicePort)"
                    recordWireEvent("Wi-Fi mTLS Connected \(hostHint):\(wake.servicePort)")
                } catch {
                    wifiMtlsStatus = "Unreachable (\(error.localizedDescription))"
                    recordWireEvent("Wi-Fi mTLS Failed: \(error.localizedDescription)")
                }
            }

        case .clipboardSync:
            if let payload = try? JSONDecoder().decode(ClipboardSyncPayload.self, from: frame.payload) {
                try? await clipboardManager.applyRemoteClipboard(payload)
                if let snippet = payload.text {
                    appendActivity(
                        BridgeActivityRecord(
                            category: .clipboard,
                            title: "Clipboard Synchronized",
                            detail: String(snippet.prefix(120)),
                            badgeText: "Synced",
                            clipboardText: snippet
                        )
                    )
                }
            }

        case .fileOffer:
            if let offer = try? JSONDecoder().decode(FileOfferPayload.self, from: frame.payload) {
                if !preferences.autoAcceptFiles {
                    pendingIncomingOffer = PendingIncomingOffer(id: offer.transferId, offer: offer)
                    appendActivity(
                        BridgeActivityRecord(
                            category: .files,
                            title: "File Offer Pending",
                            detail: "\(offer.filename) (\(offer.byteSize / 1024) KB)",
                            badgeText: "Pending"
                        )
                    )
                } else {
                    await acceptIncomingFileOffer(offer)
                }
            }

        case .fileResponse:
            if let resp = try? JSONDecoder().decode(FileResponsePayload.self, from: frame.payload) {
                await fileTransferManager.handleFileResponse(resp)
            }

        case .fileChunk:
            if let packet = try? FileChunkPacket.decode(from: frame.payload),
               let progress = try? await fileTransferManager.receiveChunk(packet) {
                activeTransfer = progress
                if progress.bytesTransferred >= progress.totalBytes {
                    let savedURL = try? await fileTransferManager.finalizeIncomingTransfer(packet.transferId)
                    activeTransfer = nil
                    statusBanner = "Saved \(progress.filename) to ~/Downloads/Link-Me"
                    postFileSavedNotification(filename: progress.filename, fileURL: savedURL)
                    appendActivity(
                        BridgeActivityRecord(
                            category: .files,
                            title: "File Received",
                            detail: progress.filename,
                            badgeText: "Verified",
                            fileURL: savedURL
                        )
                    )
                }
            }

        case .notificationEvent:
            if let event = try? JSONDecoder().decode(NotificationEventPayload.self, from: frame.payload) {
                try? await notificationManager.handleIncomingEvent(event, postSystemBanner: true)
                recentNotifications = await notificationManager.recentNotifications
                if event.action == "POST" {
                    appendActivity(
                        BridgeActivityRecord(
                            category: .system,
                            title: "Notification Mirrored",
                            detail: "\(event.appName) • \(event.title)",
                            badgeText: "Alert"
                        )
                    )
                }
            }

        case .pingTelemetry:
            if let telemetry = try? JSONDecoder().decode(PingTelemetryPayload.self, from: frame.payload) {
                await telemetryFeature.updatePeerTelemetry(telemetry)
                peerBatteryPercent = telemetry.batteryLevel
                peerIsCharging = telemetry.isCharging
                bridgeState = telemetry.transportState
                if let nonce = telemetry.pingNonce {
                    if telemetry.isPong {
                        if let start = pendingPingStartByNonce.removeValue(forKey: nonce) {
                            let rtt = max(1, Int(Date().timeIntervalSince(start) * 1000))
                            latencyMs = rtt
                            pingTestStatus = "RTT: \(rtt) ms (0x01 Pong)"
                            recordWireEvent("PONG 0x01 RTT=\(rtt)ms (nonce=\(nonce))")
                        }
                    } else {
                        let battery = MacBatteryMonitor.readBattery()
                        let pong = PingTelemetryPayload(
                            deviceId: localDeviceId,
                            batteryLevel: battery.level ?? 100,
                            isCharging: battery.isCharging,
                            batterySaverActive: false,
                            transportState: bridgeState,
                            timestampMs: UInt64(Date().timeIntervalSince1970 * 1000),
                            pingNonce: nonce,
                            isPong: true
                        )
                        if let data = try? JSONEncoder().encode(pong),
                           let reply = try? await transportManager.makeFrame(messageType: .pingTelemetry, payload: data) {
                            try? await transportManager.sendResilientFrame(reply, preferBle: true)
                            recordWireEvent("TX 0x01 PONG (nonce=\(nonce))")
                        }
                    }
                }
            }

        case .deviceCommand:
            if let cmd = try? JSONDecoder().decode(DeviceCommandPayload.self, from: frame.payload),
               cmd.command == "btPing" {
                let pong = DeviceCommandPayload(command: "btPong", timestampMs: cmd.timestampMs)
                if let data = try? JSONEncoder().encode(pong),
                   let pongFrame = try? WireFrame(messageType: .deviceCommand, sequenceId: frame.sequenceId &+ 10_000_000, payload: data) {
                    blePeripheralManager?.sendFrame(pongFrame)
                    bleCentralManager?.sendControlFrame(pongFrame)
                }
            }

        case .mediaControl:
            if let cmd = try? JSONDecoder().decode(MediaControlPayload.self, from: frame.payload) {
                continuityController.handleInboundMediaControl(cmd)
                appendActivity(
                    BridgeActivityRecord(
                        category: .media,
                        title: "Media Command Executed",
                        detail: cmd.action.rawValue.uppercased(),
                        badgeText: cmd.action.rawValue
                    )
                )
            }

        case .openUrl:
            if let payload = try? JSONDecoder().decode(OpenUrlPayload.self, from: frame.payload),
               let opened = try? continuityController.handleInboundOpenUrl(payload) {
                statusBanner = "Opened URL from Android"
                appendActivity(
                    BridgeActivityRecord(
                        category: .clipboard,
                        title: "URL Opened From Android",
                        detail: opened.absoluteString,
                        badgeText: "Opened"
                    )
                )
            }

        case .screenControl:
            if let control = try? JSONDecoder().decode(ScreenControlPayload.self, from: frame.payload),
               control.source == "macos" {
                if control.action == "start" {
                    await startSharingMacScreen(fps: control.fps, quality: control.quality)
                } else {
                    await stopSharingMacScreen()
                }
            }

        case .screenFrame:
            if let packet = try? ScreenFramePacket.decode(from: frame.payload),
               let img = NSImage(data: packet.jpegData) {
                remoteScreenImage = img
                remoteScreenWidth = Int(packet.width)
                remoteScreenHeight = Int(packet.height)
                remoteScreenSizeText = "\(packet.width)×\(packet.height)"
                mirrorFrameCounter += 1
                let now = Date()
                let elapsed = now.timeIntervalSince(mirrorFpsWindowStart)
                if elapsed >= 1.0 {
                    remoteMirrorFps = max(1, Int((Double(mirrorFrameCounter) / elapsed).rounded()))
                    mirrorFrameCounter = 0
                    mirrorFpsWindowStart = now
                }
                mirrorStatusText = "Live Mirror • \(packet.width)×\(packet.height)"
                mirrorWindowController?.syncFromViewModel()
            }

        default:
            break
        }
    }

    private func acceptIncomingFileOffer(_ offer: FileOfferPayload) async {
        if let response = try? await fileTransferManager.beginIncomingTransfer(offer: offer),
           let data = try? JSONEncoder().encode(response),
           let frame = try? await transportManager.makeFrame(messageType: .fileResponse, payload: data) {
            try? await transportManager.sendResilientFrame(frame, preferBle: true)
        }
    }

    func resolvePendingIncomingOffer(accepted: Bool) {
        guard let pending = pendingIncomingOffer else { return }
        pendingIncomingOffer = nil
        Task {
            if accepted {
                await acceptIncomingFileOffer(pending.offer)
                statusBanner = "Accepted \(pending.offer.filename)"
            } else {
                let decline = FileResponsePayload(
                    transferId: pending.offer.transferId,
                    accepted: false,
                    resumeChunkIndex: 0,
                    reason: "DECLINED_BY_USER"
                )
                if let data = try? JSONEncoder().encode(decline),
                   let frame = try? await transportManager.makeFrame(messageType: .fileResponse, payload: data) {
                    try? await transportManager.sendResilientFrame(frame, preferBle: true)
                }
                appendActivity(
                    BridgeActivityRecord(
                        category: .files,
                        title: "Declined File Offer",
                        detail: pending.offer.filename,
                        badgeText: "Declined"
                    )
                )
                statusBanner = "Declined \(pending.offer.filename)"
            }
        }
    }

    func toggleClipboardSync(_ enabled: Bool) {
        clipboardSyncEnabled = enabled
        Task { await clipboardManager.setEnabled(enabled) }
    }

    func toggleNotificationMirror(_ enabled: Bool) {
        notificationMirrorEnabled = enabled
        Task { await notificationManager.setEnabled(enabled) }
    }

    func syncClipboardNow() {
        Task {
            if let emitted = try? await clipboardManager.pollLocalPasteboardOnce(),
               let text = emitted.text {
                appendActivity(
                    BridgeActivityRecord(
                        category: .clipboard,
                        title: "Clipboard Sent to Android",
                        detail: String(text.prefix(120)),
                        badgeText: "Synced",
                        clipboardText: text
                    )
                )
            }
            statusBanner = "Clipboard synced"
        }
    }

    func copySnippetAgain(_ text: String) {
        let pb = NSPasteboard.general
        pb.clearContents()
        pb.setString(text, forType: .string)
        statusBanner = "Copied snippet to clipboard"
    }

    func revealInFinder(_ url: URL) {
        NSWorkspace.shared.activateFileViewerSelecting([url])
    }

    func sendMediaAction(_ action: MediaControlAction) {
        Task {
            try? await continuityController.sendMediaControl(action)
            appendActivity(
                BridgeActivityRecord(
                    category: .media,
                    title: "Media Control Sent",
                    detail: action.rawValue.uppercased(),
                    badgeText: action.rawValue
                )
            )
            statusBanner = "Media: \(action.rawValue)"
        }
    }

    func sendUrlToAndroid() {
        let raw = urlDraft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !raw.isEmpty else { return }
        Task {
            do {
                let valid = try await continuityController.sendOpenUrl(raw)
                statusBanner = "Sent URL to Android"
                appendActivity(
                    BridgeActivityRecord(
                        category: .clipboard,
                        title: "URL Beamed to Android",
                        detail: valid.absoluteString,
                        badgeText: "Opened"
                    )
                )
            } catch {
                statusBanner = "Blocked: only http/https URLs allowed"
            }
        }
    }

    func ringMyPhone() {
        Task {
            _ = try? await telemetryFeature.sendRingPhoneCommand(durationSeconds: 15)
            statusBanner = "Ringing paired phone (15s)"
            appendActivity(
                BridgeActivityRecord(
                    category: .system,
                    title: "Ring Phone Sent",
                    detail: "15-second audible alert",
                    badgeText: "Sent"
                )
            )
        }
    }

    func sendNotificationReply(sbnKey: String, replyText: String) {
        let trimmed = replyText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        Task {
            _ = try? await notificationManager.sendInlineReply(sbnKey: sbnKey, replyText: trimmed)
            statusBanner = "Reply sent to Android"
        }
    }

    func openFilePickerAndSend() {
        let panel = NSOpenPanel()
        panel.canChooseFiles = true
        panel.canChooseDirectories = false
        panel.allowsMultipleSelection = false
        if panel.runModal() == .OK, let url = panel.url {
            sendFile(url: url)
        }
    }

    func sendFile(url: URL) {
        let transferId = UUID()
        activeTransferId = transferId
        let useWifiChunks = (bridgeState == .activeWifiStream)
        let adaptiveChunkSize = useWifiChunks ? FileTransferManager.chunkSize : 2048
        Task { [weak self] in
            guard let self else { return }
            do {
                _ = try await fileTransferManager.streamOutboundFile(
                    fileURL: url,
                    transferId: transferId,
                    chunkSize: adaptiveChunkSize,
                    awaitOfferAcceptance: true,
                    onProgress: { progress in
                        await MainActor.run {
                            self.activeTransfer = progress
                        }
                    },
                    emitFrame: { frame in
                        try await self.transportManager.sendResilientFrame(frame, preferBle: false)
                    }
                )
                activeTransfer = nil
                activeTransferId = nil
                bridgeState = await transportManager.bridgeState
                statusBanner = "Sent \(url.lastPathComponent)"
                appendActivity(
                    BridgeActivityRecord(
                        category: .files,
                        title: "File Transfer Completed",
                        detail: url.lastPathComponent,
                        badgeText: "Sent",
                        fileURL: url
                    )
                )
            } catch {
                activeTransfer = nil
                activeTransferId = nil
                statusBanner = "Transfer stopped: \(error.localizedDescription)"
            }
        }
    }

    func cancelActiveTransfer() {
        guard let id = activeTransferId else { return }
        Task {
            await fileTransferManager.cancelTransfer(id)
            activeTransfer = nil
            activeTransferId = nil
        }
    }

    func refreshPairingSession() async {
        do {
            try? await pairingFeature.startTemporaryListener(
                onReadyPort: { [weak self] port in
                    Task { @MainActor [weak self] in
                        self?.localListeningPort = port
                    }
                },
                onPairHelloReceived: { [weak self] hello in
                    Task { @MainActor [weak self] in
                        await self?.processIncomingPairHello(hello)
                    }
                },
                onPairCommitReceived: { [weak self] commit in
                    guard let self else { return nil }
                    return await self.confirmPeerFromCommit(commit)
                }
            )
            let (uri, expires) = try await pairingFeature.beginPairingSession(
                tcpPort: localListeningPort,
                deviceName: preferences.normalizedAlias
            )
            pairingUriString = uri.uriString
            pairingExpiresAt = expires
            pendingApproval = nil
        } catch {
            statusBanner = "Failed to initialize pairing"
        }
    }

    func startPairingFlow(inStudioWindow: Bool = false) {
        Task {
            await refreshPairingSession()
            if inStudioWindow {
                selectedTab = .pairing
            } else {
                isShowingPairingSheet = true
            }
        }
    }

    func confirmPairing() {
        Task {
            if let peer = try? await pairingFeature.confirmAndTrust() {
                peerPaired = true
                peerName = peer.deviceName
                peerDeviceId = peer.deviceId
                peerCertFingerprint = peer.pinnedCertSha256
                bleCentralManager?.startScanning()
                await clipboardManager.setPeerReachable(true)
                if let ack = try? await pairingFeature.buildPairAck(deviceName: preferences.normalizedAlias) {
                    sendPairAckOverActiveLinks(ack)
                }
                await emitLocalTelemetryOnce()
                statusBanner = "Trusted \(peer.deviceName) in Keychain"
                appendActivity(
                    BridgeActivityRecord(
                        category: .system,
                        title: "Device Paired",
                        detail: peer.deviceName,
                        badgeText: "Verified"
                    )
                )
            }
            isShowingPairingSheet = false
            pendingApproval = nil
        }
    }

    func rejectPairing() {
        Task {
            await pairingFeature.rejectPairing()
            isShowingPairingSheet = false
            pendingApproval = nil
        }
    }

    func unpairAllDevices() {
        let peers = (try? trustStore.allPeers()) ?? []
        for peer in peers {
            try? trustStore.removePeer(deviceId: peer.deviceId)
        }
        bleCentralManager?.stopScanning()
        peerPaired = false
        peerName = "No Device Paired"
        peerDeviceId = ""
        peerCertFingerprint = ""
        latencyMs = nil
        statusBanner = "Unpaired all devices"
        appendActivity(
            BridgeActivityRecord(
                category: .system,
                title: "Devices Unpaired",
                detail: "TrustStore cleared",
                badgeText: "Reset"
            )
        )
    }

    func runLoopbackSelfTest() {
        Task {
            let started = Date()
            do {
                let mac = PairingCrypto()
                let android = PairingCrypto()
                let s1 = try mac.deriveSecrets(peerPublicKeyString: android.publicKeyBase64Url, nonce: mac.nonce)
                let s2 = try android.deriveSecrets(peerPublicKeyString: mac.publicKeyBase64Url, nonce: mac.nonce)
                guard s1.sasCode == s2.sasCode, s1.sessionKey == s2.sessionKey else {
                    loopbackDiagnosticsResult = "FAIL: SAS or session key mismatch"
                    return
                }
                let senderCipher = try BluetoothSessionCipher(sessionKey: s1.sessionKey, senderTag: "MAC0")
                let receiverCipher = try BluetoothSessionCipher(sessionKey: s2.sessionKey, senderTag: "AND0")
                let sampleFrame = try WireFrame(
                    messageType: .mediaControl,
                    sequenceId: 1,
                    payload: Data("{\"action\":\"play_pause\"}".utf8)
                )
                let sealed = try senderCipher.seal(frame: sampleFrame)
                let chunks = BluetoothPacketCodec.packetize(envelope: sealed, packetId: 7, mtu: 23)
                let reassembler = BluetoothPacketReassembler()
                var assembled: Data?
                for chunk in chunks {
                    let res = reassembler.ingest(chunk)
                    if let done = res.completeEnvelope { assembled = done }
                }
                guard let env = assembled,
                      try receiverCipher.openFrame(from: env) == sampleFrame else {
                    loopbackDiagnosticsResult = "FAIL: BLE ChaCha20-Poly1305 reassembly failed"
                    return
                }
                let elapsedMs = max(1, Int(Date().timeIntervalSince(started) * 1000))
                latencyMs = elapsedMs
                loopbackDiagnosticsResult = "PASS (\(elapsedMs)ms): X25519 SAS \(s1.sasCode) + ChaCha20-Poly1305 (\(chunks.count) BLE MTU-23 chunks) verified"
                appendActivity(
                    BridgeActivityRecord(
                        category: .system,
                        title: "Loopback Diagnostics Passed",
                        detail: "X25519 + ChaCha20 BLE E2EE verified in \(elapsedMs)ms",
                        badgeText: "PASS"
                    )
                )
            } catch {
                loopbackDiagnosticsResult = "FAIL: \(error.localizedDescription)"
            }
        }
    }
}
