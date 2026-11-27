import MacBridgeCore
import SwiftUI
import UniformTypeIdentifiers

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

    var body: some View {
        VStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 12) {
                headerSection
                beamDropzoneCard
                if let transfer = viewModel.activeTransfer {
                    activeTransferCard(transfer)
                }
                featureSwitchesSection
                quickControlsStrip
                notificationsSection
            }
            .padding(14)

            bottomTelemetryBar
        }
        .frame(width: 360)
        .background(StitchTokens.surface)
        .foregroundStyle(StitchTokens.onSurface)
        .sheet(isPresented: $viewModel.isShowingPairingSheet) {
            PairingView(viewModel: viewModel)
        }
    }

    // 1. Header: 40x40 circular battery progress ring (#74D8BD) + bolt + status pill
    private var headerSection: some View {
        HStack(spacing: 12) {
            ZStack {
                Circle()
                    .stroke(StitchTokens.surfaceHigh, lineWidth: 3.5)
                Circle()
                    .trim(from: 0, to: CGFloat(max(0, min(100, viewModel.peerBatteryPercent))) / 100.0)
                    .stroke(
                        StitchTokens.tertiaryMint,
                        style: StrokeStyle(lineWidth: 3.5, lineCap: .round)
                    )
                    .rotationEffect(.degrees(-90))
                Image(systemName: viewModel.peerIsCharging ? "bolt.fill" : "battery.100")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(StitchTokens.tertiaryMint)
            }
            .frame(width: 40, height: 40)

            VStack(alignment: .leading, spacing: 3) {
                Text(viewModel.peerName)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(StitchTokens.onSurface)
                    .lineLimit(1)

                let chargeTag = viewModel.peerIsCharging ? "CHARGING" : "BATTERY"
                let pairTag = viewModel.peerPaired ? "PAIRED" : "UNPAIRED"
                Text("\(viewModel.peerBatteryPercent)% \(chargeTag) • \(pairTag)")
                    .font(.system(size: 10, weight: .semibold, design: .monospaced))
                    .tracking(0.6)
                    .foregroundStyle(StitchTokens.secondaryText)
            }

            Spacer()

            let isTurbo = viewModel.bridgeState == .activeWifiStream
            HStack(spacing: 5) {
                Circle()
                    .fill(isTurbo ? StitchTokens.tertiaryMint : StitchTokens.primaryCrimson)
                    .frame(width: 6, height: 6)
                Text(isTurbo ? "WI-FI TURBO 5GHZ" : "BLE STANDBY")
                    .font(.system(size: 10, weight: .bold, design: .monospaced))
                    .tracking(0.6)
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 5)
            .background(Capsule().fill(StitchTokens.surfaceHigh))
            .overlay(
                Capsule().stroke(isTurbo ? StitchTokens.tertiaryMint : StitchTokens.outlineVariant, lineWidth: 1)
            )
            .foregroundStyle(isTurbo ? StitchTokens.tertiaryMint : StitchTokens.onSurface)
        }
        .padding(10)
        .background(
            RoundedRectangle(cornerRadius: 10)
                .fill(StitchTokens.surfaceLow)
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(StitchTokens.outlineVariant, lineWidth: 1))
        )
    }

    // 2. Dropzone / Beam Card (#0D0E10, #292A2C upload container, dashed #5C3F40)
    private var beamDropzoneCard: some View {
        Button {
            viewModel.openFilePickerAndSend()
        } label: {
            VStack(spacing: 6) {
                Image(systemName: "arrow.up.doc.on.clipboard")
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(viewModel.isDropTargeted ? StitchTokens.primaryCrimson : StitchTokens.tertiaryMint)
                    .frame(width: 34, height: 34)
                    .background(RoundedRectangle(cornerRadius: 8).fill(StitchTokens.surfaceHigh))
                Text("Drop files to beam to Android")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(StitchTokens.onSurface)
                Text("MAX 4.0 GB • DIRECT P2P")
                    .font(.system(size: 10, weight: .medium, design: .monospaced))
                    .tracking(0.6)
                    .foregroundStyle(StitchTokens.secondaryText)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .background(
                RoundedRectangle(cornerRadius: 10)
                    .fill(viewModel.isDropTargeted ? StitchTokens.primaryCrimson.opacity(0.12) : StitchTokens.surfaceLowest)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 10)
                    .strokeBorder(
                        viewModel.isDropTargeted ? StitchTokens.primaryCrimson : StitchTokens.outlineVariant,
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
                    .foregroundStyle(StitchTokens.onSurface)
                    .lineLimit(1)
                Spacer()
                Button("CANCEL") {
                    viewModel.cancelActiveTransfer()
                }
                .font(.system(size: 10, weight: .bold, design: .monospaced))
                .foregroundStyle(StitchTokens.primaryCrimson)
                .buttonStyle(.plain)
            }
            ProgressView(value: transfer.fractionCompleted)
                .tint(StitchTokens.primaryCrimson)
            HStack {
                Text("\(transfer.bytesTransferred / 1024) / \(transfer.totalBytes / 1024) KB")
                Spacer()
                Text(String(format: "%.1f MB/s", transfer.bytesPerSecond / 1_048_576))
            }
            .font(.system(size: 10, design: .monospaced))
            .foregroundStyle(StitchTokens.secondaryText)
        }
        .padding(10)
        .background(
            RoundedRectangle(cornerRadius: 10)
                .fill(StitchTokens.surfaceContainer)
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(StitchTokens.outlineVariant, lineWidth: 1))
        )
    }

    // 3. Two #1F2022 Feature Switch Cards
    private var featureSwitchesSection: some View {
        VStack(spacing: 8) {
            stitchToggleCard(
                title: "Universal Clipboard Sync",
                subtitle: "macOS ↔ Android",
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
                    .foregroundStyle(StitchTokens.onSurface)
                Text(subtitle)
                    .font(.system(size: 10, design: .monospaced))
                    .foregroundStyle(StitchTokens.secondaryText)
            }
            Spacer()
            Toggle("", isOn: isOn)
                .labelsHidden()
                .toggleStyle(.switch)
                .tint(StitchTokens.primaryCrimson)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(
            RoundedRectangle(cornerRadius: 10)
                .fill(StitchTokens.surfaceContainer)
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(StitchTokens.outlineVariant, lineWidth: 1))
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
            .tint(StitchTokens.surfaceHighest)

            Button {
                viewModel.ringMyPhone()
            } label: {
                Label("Ring", systemImage: "bell.and.waves.left.and.right")
                    .font(.system(size: 11, weight: .medium))
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .tint(StitchTokens.surfaceHighest)

            Button {
                viewModel.startPairingFlow()
            } label: {
                Label("Pair QR", systemImage: "qrcode")
                    .font(.system(size: 11, weight: .medium))
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .tint(StitchTokens.primaryCrimson)
        }
    }

    // 4. Recent Notifications Feed (#E4E2DE replyable vs #1F2022 non-replyable)
    private var notificationsSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("RECENT NOTIFICATIONS")
                .font(.system(size: 10, weight: .bold, design: .monospaced))
                .foregroundStyle(StitchTokens.secondaryText)

            if viewModel.recentNotifications.isEmpty {
                Text("No mirrored notifications yet")
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(StitchTokens.secondaryText.opacity(0.75))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(10)
                    .background(
                        RoundedRectangle(cornerRadius: 10)
                            .fill(StitchTokens.surfaceContainer)
                            .overlay(RoundedRectangle(cornerRadius: 10).stroke(StitchTokens.outlineVariant, lineWidth: 1))
                    )
            } else {
                ForEach(viewModel.recentNotifications.prefix(2)) { item in
                    if item.canReply {
                        ReplyableNotificationCard(item: item) { replyText in
                            viewModel.sendNotificationReply(sbnKey: item.id, replyText: replyText)
                        }
                    } else {
                        HStack(alignment: .top, spacing: 8) {
                            Image(systemName: "bell.fill")
                                .font(.system(size: 12))
                                .foregroundStyle(StitchTokens.tertiaryMint)
                                .padding(.top, 2)
                            VStack(alignment: .leading, spacing: 3) {
                                Text("\(item.appName.uppercased()) • \(item.title)")
                                    .font(.system(size: 10, weight: .bold, design: .monospaced))
                                    .tracking(0.6)
                                    .foregroundStyle(StitchTokens.secondaryText)
                                    .lineLimit(1)
                                Text(item.body)
                                    .font(.system(size: 12))
                                    .foregroundStyle(StitchTokens.onSurface)
                                    .lineLimit(2)
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(10)
                        .background(
                            RoundedRectangle(cornerRadius: 10)
                                .fill(StitchTokens.surfaceContainer)
                                .overlay(RoundedRectangle(cornerRadius: 10).stroke(StitchTokens.outlineVariant, lineWidth: 1))
                        )
                    }
                }
            }
        }
    }

    // 5. Bottom Telemetry Bar (#0D0E10)
    private var bottomTelemetryBar: some View {
        HStack {
            let latText = viewModel.latencyMs.map { "\($0)MS" } ?? "-- MS"
            Text("LATENCY: \(latText)")
                .font(.system(size: 10, weight: .semibold, design: .monospaced))
                .tracking(0.6)
                .foregroundStyle(StitchTokens.tertiaryMint)

            Spacer()

            Text("E2EE: TLS 1.3 PINNED")
                .font(.system(size: 10, weight: .semibold, design: .monospaced))
                .tracking(0.6)
                .foregroundStyle(StitchTokens.secondaryText)

            Button {
                NSApplication.shared.terminate(nil)
            } label: {
                Image(systemName: "power")
                    .font(.system(size: 10, weight: .bold))
                    .foregroundStyle(StitchTokens.secondaryText)
            }
            .buttonStyle(.plain)
            .padding(.leading, 8)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 9)
        .background(StitchTokens.surfaceLowest)
        .overlay(alignment: .top) {
            Rectangle()
                .fill(StitchTokens.outlineVariant)
                .frame(height: 1)
        }
    }
}

private struct ReplyableNotificationCard: View {
    let item: MirroredNotificationItem
    let onSendReply: (String) -> Void
    @StateObject private var replyState = InlineReplyBoxModel()

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("\(item.appName.uppercased()) • \(item.title)")
                .font(.system(size: 10, weight: .bold, design: .monospaced))
                .foregroundStyle(StitchTokens.activeCardInk.opacity(0.75))
                .lineLimit(1)

            Text(item.body)
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(StitchTokens.activeCardInk)
                .lineLimit(2)

            HStack(spacing: 6) {
                TextField("Reply inline...", text: $replyState.text)
                    .textFieldStyle(.plain)
                    .font(.system(size: 11))
                    .foregroundStyle(StitchTokens.onSurface)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 6)
                    .background(RoundedRectangle(cornerRadius: 6).fill(StitchTokens.surfaceLowest))

                Button("Send") {
                    onSendReply(replyState.text)
                    replyState.text = ""
                }
                .font(.system(size: 11, weight: .semibold))
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .background(RoundedRectangle(cornerRadius: 6).fill(StitchTokens.primaryCrimson))
                .foregroundStyle(Color.white)
                .buttonStyle(.plain)
            }
        }
        .padding(11)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 10).fill(StitchTokens.activeCardLight))
    }
}

@MainActor
private final class InlineReplyBoxModel: ObservableObject {
    @Published var text: String = ""
}
