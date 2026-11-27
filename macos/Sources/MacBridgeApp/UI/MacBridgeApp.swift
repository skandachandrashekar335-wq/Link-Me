import AppKit
import MacBridgeCore
import SwiftUI
import UniformTypeIdentifiers

@main
struct MacBridgeApp: App {
    @StateObject private var viewModel = BridgeDashboardViewModel()

    var body: some Scene {
        MenuBarExtra("MacBridge", systemImage: viewModel.menuBarSymbol) {
            MenuBarDashboardView(viewModel: viewModel)
        }
        .menuBarExtraStyle(.window)
    }
}

@MainActor
final class BridgeDashboardViewModel: ObservableObject {
    @Published var bridgeState: BridgeState = .dormantBle
    @Published var peerName: String = "Pixel 9 Pro"
    @Published var peerBatteryPercent: Int = 84
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
        deviceId: "11111111-1111-4111-8111-111111111111",
        transport: transportManager
    )
    let fileTransferManager = FileTransferManager()
    lazy var notificationManager = MacNotificationManager(transport: transportManager)
    lazy var telemetryFeature = TelemetryFeature(transport: transportManager)

    private var activeTransferId: UUID?

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
            _ = try? await pairingFeature.confirmAndTrust()
            isShowingPairingSheet = false
            pendingApproval = nil
            statusBanner = "Peer trusted in Keychain"
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
