import AppKit
import MacBridgeCore
import SwiftUI
import UserNotifications

@MainActor
final class MacBridgeAppDelegate: NSObject, NSApplicationDelegate {
    var onWillTerminate: (() -> Void)?

    func applicationWillTerminate(_ notification: Notification) {
        onWillTerminate?()
    }
}

@main
struct MacBridgeApp: App {
    @NSApplicationDelegateAdaptor(MacBridgeAppDelegate.self) private var appDelegate
    @StateObject private var viewModel = BridgeDashboardViewModel()

    var body: some Scene {
        MenuBarExtra {
            MenuBarDashboardView(viewModel: viewModel)
                .onAppear {
                    let vm = viewModel
                    appDelegate.onWillTerminate = { [weak vm] in
                        vm?.teardownOnQuit()
                    }
                }
        } label: {
            Image(nsImage: MenuBarVectorIcon.makeTemplateImage(isActive: viewModel.isBluetoothConnected || viewModel.bridgeState == .activeWifiStream))
        }
        .menuBarExtraStyle(.window)

        Window("Link-Me Studio", id: "studio") {
            BridgeStudioWindow(viewModel: viewModel)
        }
        .defaultSize(width: 1040, height: 700)
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
    @Published var peerBatteryPercent: Int = 84
    @Published var peerIsCharging: Bool = true
    @Published var peerPaired: Bool = false
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
    @Published var localCertFingerprint: String = ""
    @Published var localListeningPort: UInt16 = 49152
    @Published var loopbackDiagnosticsResult: String?

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

    private var localDeviceId: String = "11111111-1111-4111-8111-111111111111"
    private var localIdentity: DeviceIdentity?
    private var bleCentralManager: BleCentralManager?
    private var blePeripheralManager: BlePeripheralManager?
    private var wifiTlsServer: WifiTlsServer?
    private var wifiTlsClient: WifiTlsClient?
    private var activeTransferId: UUID?
    private var isBootstrapped = false

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
                self.peripheralDiagnostics = diag
                self.isBluetoothConnected = (diag.subscribedCentralsCount > 0) || (self.bleCentralManager?.isConnected == true)
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
            try? await self.pairingFeature.startTemporaryListener(
                onReadyPort: { [weak self] port in
                    Task { @MainActor in
                        self?.localListeningPort = port
                    }
                },
                onPairHelloReceived: { [weak self] hello in
                    Task { @MainActor [weak self] in
                        await self?.processIncomingPairHello(hello)
                    }
                }
            )
            await self.refreshPairingSession()

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

    func teardownOnQuit() {
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
        wifiTlsServer = server
        appendActivity(
            BridgeActivityRecord(
                category: .system,
                title: "Wi-Fi mTLS 1.3 Active",
                detail: "Cooldown \(preferences.wifiCooldownSeconds)s",
                badgeText: "TLS 1.3"
            )
        )
        return try await server.start(port: 0, advertiseBonjour: true)
    }

    private func sendOverWifi(_ frame: WireFrame) async throws {
        guard let client = wifiTlsClient else {
            throw WifiTlsError.connectionClosed
        }
        try await client.send(frame: frame)
    }

    private func closeWifiTransport() async {
        wifiTlsClient?.close()
        wifiTlsClient = nil
        wifiTlsServer?.stop()
        wifiTlsServer = nil
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
        switch frame.messageType {
        case .pairHello:
            if let hello = try? JSONDecoder().decode(PairHelloPayload.self, from: frame.payload) {
                await processIncomingPairHello(hello)
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
                if (try? await client.connect(host: hostHint, port: wake.servicePort)) != nil {
                    wifiTlsClient = client
                    bridgeState = .activeWifiStream
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

        case .fileChunk:
            if let packet = try? FileChunkPacket.decode(from: frame.payload),
               let progress = try? await fileTransferManager.receiveChunk(packet) {
                activeTransfer = progress
                if progress.bytesTransferred >= progress.totalBytes {
                    let savedURL = try? await fileTransferManager.finalizeIncomingTransfer(packet.transferId)
                    activeTransfer = nil
                    statusBanner = "Saved \(progress.filename)"
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
        Task { [weak self] in
            guard let self else { return }
            do {
                _ = try await fileTransferManager.streamOutboundFile(
                    fileURL: url,
                    transferId: transferId,
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
