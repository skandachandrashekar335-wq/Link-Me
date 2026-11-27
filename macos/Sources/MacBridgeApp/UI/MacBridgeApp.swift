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
        MenuBarExtra("MacBridge", systemImage: viewModel.menuBarSymbol) {
            MenuBarDashboardView(viewModel: viewModel)
                .onAppear {
                    let vm = viewModel
                    appDelegate.onWillTerminate = { [weak vm] in
                        vm?.teardownOnQuit()
                    }
                }
        }
        .menuBarExtraStyle(.window)
    }
}

@MainActor
final class BridgeDashboardViewModel: ObservableObject {
    @Published var bridgeState: BridgeState = .dormantBle
    @Published var peerName: String = "No Device Paired"
    @Published var peerBatteryPercent: Int = 100
    @Published var peerIsCharging: Bool = false
    @Published var clipboardSyncEnabled: Bool = true
    @Published var notificationMirrorEnabled: Bool = true
    @Published var activeTransfer: FileTransferProgress?
    @Published var recentNotifications: [MirroredNotificationItem] = []
    @Published var isShowingPairingSheet: Bool = false
    @Published var isDropTargeted: Bool = false
    @Published var pairingRemainingSeconds: Int = 300
    @Published var pairingUriString: String = ""
    @Published var pairingExpiresAt: Date = Date().addingTimeInterval(300)
    @Published var pendingApproval: PendingPairingApproval?
    @Published var statusBanner: String?

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

    private var localDeviceId: String = "11111111-1111-4111-8111-111111111111"
    private var localIdentity: DeviceIdentity?
    private var bleCentralManager: BleCentralManager?
    private var wifiTlsServer: WifiTlsServer?
    private var wifiTlsClient: WifiTlsClient?
    private var activeTransferId: UUID?
    private var isBootstrapped = false

    init() {
        bootstrap()
    }

    var menuBarSymbol: String {
        switch bridgeState {
        case .dormantBle, .cooldownIdle: return "bolt.horizontal.circle"
        case .handshakeWake: return "antenna.radiowaves.left.and.right"
        case .activeWifiStream: return "link.circle.fill"
        }
    }

    var connectionBadgeText: String {
        bridgeState.displayBadge
    }

    func bootstrap() {
        guard !isBootstrapped else { return }
        isBootstrapped = true

        if let identity = try? identityManager.loadOrCreateIdentity() {
            localIdentity = identity
            localDeviceId = identity.deviceId
        }

        let central = BleCentralManager(trustStore: trustStore)
        central.onVerifiedPeerDiscovered = { [weak self] peer in
            Task { @MainActor in
                guard let self else { return }
                self.peerName = peer.deviceName
                self.statusBanner = "BLE connected: \(peer.deviceName)"
                await self.clipboardManager.setPeerReachable(true)
            }
        }
        central.onInboundFrame = { [weak self] frame in
            Task { await self?.handleInboundFrame(frame) }
        }
        bleCentralManager = central

        if let firstPeer = (try? trustStore.allPeers())?.first {
            peerName = firstPeer.deviceName
            central.startScanning()
        }

        UNUserNotificationCenter.current().setNotificationCategories([
            MacNotificationManager.makeNotificationCategory()
        ])

        Task { [weak self] in
            guard let self else { return }
            await self.transportManager.configureTransportHooks(
                bleSender: { frame in
                    central.sendControlFrame(frame)
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

    func teardownOnQuit() {
        bleCentralManager?.stopScanning()
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
        return try await server.start(port: 0, advertiseBonjour: true)
    }

    private func sendOverWifi(_ frame: WireFrame) async throws {
        guard let client = wifiTlsClient else { return }
        try await client.send(frame: frame)
    }

    private func closeWifiTransport() async {
        wifiTlsClient?.close()
        wifiTlsClient = nil
        wifiTlsServer?.stop()
        wifiTlsServer = nil
        bridgeState = .dormantBle
    }

    private func handleInboundFrame(_ frame: WireFrame) async {
        switch frame.messageType {
        case .clipboardSync:
            if let payload = try? JSONDecoder().decode(ClipboardSyncPayload.self, from: frame.payload) {
                try? await clipboardManager.applyRemoteClipboard(payload)
            }

        case .fileOffer:
            if let offer = try? JSONDecoder().decode(FileOfferPayload.self, from: frame.payload) {
                if let response = try? await fileTransferManager.beginIncomingTransfer(offer: offer),
                   let data = try? JSONEncoder().encode(response) {
                    try? await transportManager.sendFrame(
                        msgType: .fileResponse,
                        payload: data,
                        priority: .lowLatencyControl,
                        deviceId: localDeviceId
                    )
                }
            }

        case .fileChunk:
            if let packet = try? FileChunkPacket.decode(from: frame.payload),
               let progress = try? await fileTransferManager.receiveChunk(packet) {
                activeTransfer = progress
                if progress.bytesTransferred >= progress.totalBytes {
                    _ = try? await fileTransferManager.finalizeIncomingTransfer(packet.transferId)
                    activeTransfer = nil
                    statusBanner = "Saved \(progress.filename)"
                }
            }

        case .notificationEvent:
            if let event = try? JSONDecoder().decode(NotificationEventPayload.self, from: frame.payload) {
                try? await notificationManager.handleIncomingEvent(event, postSystemBanner: true)
                recentNotifications = await notificationManager.recentNotifications
            }

        case .pingTelemetry:
            if let telemetry = try? JSONDecoder().decode(PingTelemetryPayload.self, from: frame.payload) {
                await telemetryFeature.updatePeerTelemetry(telemetry)
                peerBatteryPercent = telemetry.batteryLevel
                peerIsCharging = telemetry.isCharging
                bridgeState = telemetry.transportState
            }

        default:
            break
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
            _ = try? await clipboardManager.pollLocalPasteboardOnce()
            statusBanner = "Clipboard synced"
        }
    }

    func ringMyPhone() {
        Task {
            _ = try? await telemetryFeature.sendRingPhoneCommand(durationSeconds: 15)
            statusBanner = "Ringing paired phone (15s)"
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
                        try await self.transportManager.sendFrame(frame, preferBleIfSmall: false)
                    }
                )
                activeTransfer = nil
                activeTransferId = nil
                bridgeState = await transportManager.bridgeState
                statusBanner = "Sent \(url.lastPathComponent)"
            } catch {
                activeTransfer = nil
                activeTransferId = nil
                statusBanner = "Transfer stopped"
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

    func startPairingFlow() {
        Task {
            do {
                let (uri, expires) = try await pairingFeature.beginPairingSession(tcpPort: 49152)
                pairingUriString = uri.uriString
                pairingExpiresAt = expires
                pendingApproval = nil
                isShowingPairingSheet = true
            } catch {
                statusBanner = "Failed to initialize pairing"
            }
        }
    }

    func confirmPairing() {
        Task {
            if let peer = try? await pairingFeature.confirmAndTrust() {
                peerName = peer.deviceName
                bleCentralManager?.startScanning()
                statusBanner = "Trusted \(peer.deviceName) in Keychain"
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
}
