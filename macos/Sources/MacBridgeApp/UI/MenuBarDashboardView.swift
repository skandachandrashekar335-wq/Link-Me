import MacBridgeCore
import SwiftUI
import UniformTypeIdentifiers

struct MenuBarDashboardView: View {
    @ObservedObject var viewModel: BridgeDashboardViewModel

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            headerSection
            Divider()
            quickActionsGrid
            dropZoneCard
            if let transfer = viewModel.activeTransfer {
                activeTransferCard(transfer)
            }
            togglesSection
            notificationsSection
            footerSection
        }
        .padding(14)
        .frame(width: 340)
        .sheet(isPresented: $viewModel.isShowingPairingSheet) {
            PairingView(viewModel: viewModel)
        }
    }

    private var headerSection: some View {
        HStack(spacing: 10) {
            Image(systemName: viewModel.menuBarSymbol)
                .font(.title2)
                .foregroundStyle(Color.accentColor)

            VStack(alignment: .leading, spacing: 3) {
                Text(viewModel.peerName)
                    .font(.headline)
                Text(viewModel.connectionBadgeText)
                    .font(.caption.weight(.medium))
                    .padding(.horizontal, 8)
                    .padding(.vertical, 2)
                    .background(
                        Capsule().fill(
                            viewModel.bridgeState == .activeWifiStream
                                ? Color.green.opacity(0.18)
                                : Color.secondary.opacity(0.15)
                        )
                    )
            }

            Spacer()

            HStack(spacing: 4) {
                if viewModel.peerIsCharging {
                    Image(systemName: "bolt.fill")
                        .foregroundStyle(.yellow)
                }
                Image(systemName: batteryIconName(for: viewModel.peerBatteryPercent))
                Text("\(viewModel.peerBatteryPercent)%")
                    .font(.subheadline.monospacedDigit())
            }
        }
    }

    private var quickActionsGrid: some View {
        Grid(horizontalSpacing: 8, verticalSpacing: 8) {
            GridRow {
                Button {
                    viewModel.openFilePickerAndSend()
                } label: {
                    Label("Send File", systemImage: "arrow.up.doc")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)

                Button {
                    viewModel.syncClipboardNow()
                } label: {
                    Label("Sync Clipboard", systemImage: "doc.on.clipboard")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
            }
            GridRow {
                Button {
                    viewModel.ringMyPhone()
                } label: {
                    Label("Ring My Phone", systemImage: "bell.and.waves.left.and.right")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)

                Button {
                    viewModel.startPairingFlow()
                } label: {
                    Label("Pair New Device", systemImage: "qrcode")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
            }
        }
    }

    private var dropZoneCard: some View {
        RoundedRectangle(cornerRadius: 10)
            .strokeBorder(
                viewModel.isDropTargeted ? Color.accentColor : Color.secondary.opacity(0.35),
                style: StrokeStyle(lineWidth: 1.5, dash: [5, 4])
            )
            .background(
                RoundedRectangle(cornerRadius: 10)
                    .fill(viewModel.isDropTargeted ? Color.accentColor.opacity(0.08) : Color.clear)
            )
            .frame(height: 44)
            .overlay {
                Label("Drop a file here to AirDrop to Android", systemImage: "tray.and.arrow.up")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
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
                    .font(.caption.weight(.semibold))
                    .lineLimit(1)
                Spacer()
                Button("Cancel") {
                    viewModel.cancelActiveTransfer()
                }
                .font(.caption)
                .buttonStyle(.borderless)
            }
            ProgressView(value: transfer.fractionCompleted)
            HStack {
                Text("\(transfer.bytesTransferred / 1024) / \(transfer.totalBytes / 1024) KB")
                Spacer()
                Text(String(format: "%.1f MB/s", transfer.bytesPerSecond / 1_048_576))
            }
            .font(.caption2.monospacedDigit())
            .foregroundStyle(.secondary)
        }
        .padding(10)
        .background(RoundedRectangle(cornerRadius: 8).fill(Color.secondary.opacity(0.1)))
    }

    private var togglesSection: some View {
        VStack(spacing: 6) {
            Toggle(
                "Clipboard Sync",
                isOn: Binding(
                    get: { viewModel.clipboardSyncEnabled },
                    set: { viewModel.toggleClipboardSync($0) }
                )
            )
            Toggle(
                "Notification Mirroring",
                isOn: Binding(
                    get: { viewModel.notificationMirrorEnabled },
                    set: { viewModel.toggleNotificationMirror($0) }
                )
            )
        }
        .toggleStyle(.switch)
        .font(.subheadline)
    }

    private var notificationsSection: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Recent Mirrored Notifications")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
            if viewModel.recentNotifications.isEmpty {
                Text("No mirrored notifications yet")
                    .font(.caption)
                    .foregroundStyle(.tertiary)
            } else {
                ForEach(viewModel.recentNotifications.prefix(3)) { item in
                    VStack(alignment: .leading, spacing: 2) {
                        Text("\(item.appName) · \(item.title)")
                            .font(.caption.weight(.medium))
                            .lineLimit(1)
                        Text(item.body)
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                }
            }
        }
    }

    private var footerSection: some View {
        HStack {
            Text(viewModel.statusBanner ?? "mTLS 1.3 · Keychain Pinned")
                .font(.caption2)
                .foregroundStyle(.secondary)
                .lineLimit(1)
            Spacer()
            Button("Quit") {
                NSApplication.shared.terminate(nil)
            }
            .buttonStyle(.plain)
            .font(.caption)
        }
    }

    private func batteryIconName(for level: Int) -> String {
        switch level {
        case 88...100: return "battery.100"
        case 63..<88: return "battery.75"
        case 38..<63: return "battery.50"
        case 13..<38: return "battery.25"
        default: return "battery.0"
        }
    }
}
