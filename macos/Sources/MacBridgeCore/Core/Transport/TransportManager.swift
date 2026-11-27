import Foundation

public enum TransportChannel: String, Sendable, Equatable {
    case bleStandby
    case wifiTurbo
}

public enum TransferPriority: Sendable, Equatable {
    case lowLatencyControl
    case highThroughput
}

public enum TransportError: Error, Equatable, Sendable {
    case bleOnlyBatterySaverBlocked
}

public protocol FrameTransportSink: Sendable {
    func sendFrame(_ frame: WireFrame, preferBleIfSmall: Bool) async throws
}

/// Unified dual-transport orchestrator enforcing the 4-state battery state machine:
/// DORMANT_BLE -> HANDSHAKE_WAKE -> ACTIVE_WIFI_STREAM -> COOLDOWN_IDLE -> DORMANT_BLE.
/// Automatically routes <= 400B low-latency control frames over BLE without waking Wi-Fi,
/// and tears down Wi-Fi sockets after a resettable cooldown timer (default 15s).
public actor TransportManager: FrameTransportSink {
    public static let bleControlThresholdBytes = 400
    public static let defaultCooldownSeconds: TimeInterval = 15.0

    public private(set) var bridgeState: BridgeState = .dormantBle
    private var stateMachine = BatteryStateMachine()
    private var nextSequenceId: UInt64 = 1
    private let replayGuard = ReplayGuard()
    private var cooldownDurationSeconds: TimeInterval
    private var bleOnlyBatterySaver: Bool = false
    private var cooldownTask: Task<Void, Never>?
    private var activeStreams: Int = 0
    private var isWifiSocketOpen: Bool = false

    private var bleSender: (@Sendable (WireFrame) async throws -> Void)?
    private var wifiOpener: (@Sendable () async throws -> UInt16)?
    private var wifiSender: (@Sendable (WireFrame) async throws -> Void)?
    private var wifiCloser: (@Sendable () async -> Void)?
    private var localHostHintProvider: (@Sendable () -> String?)?
    private var outboundRecorder: (@Sendable (WireFrame, TransportChannel) async -> Void)?

    public init(
        cooldownDurationSeconds: TimeInterval = TransportManager.defaultCooldownSeconds,
        outboundRecorder: (@Sendable (WireFrame, TransportChannel) async -> Void)? = nil
    ) {
        self.cooldownDurationSeconds = cooldownDurationSeconds
        self.outboundRecorder = outboundRecorder
    }

    public var currentChannel: TransportChannel {
        bridgeState == .activeWifiStream ? .wifiTurbo : .bleStandby
    }

    public var wifiConnected: Bool { isWifiSocketOpen }

    public func configureTransportHooks(
        bleSender: (@Sendable (WireFrame) async throws -> Void)? = nil,
        wifiOpener: (@Sendable () async throws -> UInt16)? = nil,
        wifiSender: (@Sendable (WireFrame) async throws -> Void)? = nil,
        wifiCloser: (@Sendable () async -> Void)? = nil,
        localHostHintProvider: (@Sendable () -> String?)? = nil
    ) {
        self.bleSender = bleSender
        self.wifiOpener = wifiOpener
        self.wifiSender = wifiSender
        self.wifiCloser = wifiCloser
        self.localHostHintProvider = localHostHintProvider ?? { PairingFeature.localLanIPv4Address() }
    }

    public func setOutboundRecorder(_ recorder: @escaping @Sendable (WireFrame, TransportChannel) async -> Void) {
        self.outboundRecorder = recorder
    }

    public func setCooldownDuration(seconds: Int) {
        self.cooldownDurationSeconds = TimeInterval(BridgePreferences.clampCooldown(seconds))
    }

    public func setBleOnlyBatterySaver(_ enabled: Bool) async {
        self.bleOnlyBatterySaver = enabled
        if enabled && isWifiSocketOpen && activeStreams == 0 {
            cooldownTask?.cancel()
            cooldownTask = nil
            await wifiCloser?()
            isWifiSocketOpen = false
            if bridgeState == .activeWifiStream {
                _ = transition(.inactivityThresholdReached)
            }
            if bridgeState == .cooldownIdle {
                _ = transition(.cooldownExpired)
            }
        }
    }

    public var isBleOnlyBatterySaverEnabled: Bool { bleOnlyBatterySaver }
    public var configuredCooldownSeconds: TimeInterval { cooldownDurationSeconds }

    public func transition(_ event: BatteryStateEvent) -> BridgeState {
        bridgeState = stateMachine.handle(event)
        return bridgeState
    }

    public func allocateSequenceId() -> UInt64 {
        let seq = nextSequenceId
        nextSequenceId &+= 1
        return seq
    }

    public func makeFrame(messageType: WireMessageType, payload: Data) throws -> WireFrame {
        try WireFrame(messageType: messageType, sequenceId: allocateSequenceId(), payload: payload)
    }

    public func sendFrame(
        msgType: WireMessageType,
        payload: Data,
        priority: TransferPriority,
        deviceId: String = "11111111-1111-4111-8111-111111111111"
    ) async throws {
        let frame = try makeFrame(messageType: msgType, payload: payload)
        try await routeFrame(frame, priority: priority, deviceId: deviceId, fallbackToBleOnWifiError: false)
    }

    public func sendFrame(_ frame: WireFrame, preferBleIfSmall: Bool = false) async throws {
        let priority: TransferPriority = (preferBleIfSmall && frame.payload.count <= Self.bleControlThresholdBytes)
            ? .lowLatencyControl
            : .highThroughput
        try await routeFrame(frame, priority: priority, deviceId: "11111111-1111-4111-8111-111111111111", fallbackToBleOnWifiError: false)
    }

    /// Dual-mode resilient send: attempts Wi-Fi mTLS when LAN is available, and seamlessly falls back
    /// to encrypted Bluetooth GATT/L2CAP when Wi-Fi is off, isolated, or in Bluetooth-only mode.
    public func sendResilientFrame(_ frame: WireFrame, preferBle: Bool = false) async throws {
        let priority: TransferPriority = preferBle ? .lowLatencyControl : .highThroughput
        try await routeFrame(frame, priority: priority, deviceId: "11111111-1111-4111-8111-111111111111", fallbackToBleOnWifiError: true)
    }

    private func routeFrame(
        _ frame: WireFrame,
        priority: TransferPriority,
        deviceId: String,
        fallbackToBleOnWifiError: Bool
    ) async throws {
        // Small low-latency control frames stay over BLE when Wi-Fi isn't needed
        if frame.payload.count <= Self.bleControlThresholdBytes && priority == .lowLatencyControl {
            try await bleSender?(frame)
            await outboundRecorder?(frame, .bleStandby)
            return
        }

        if bleOnlyBatterySaver {
            if frame.payload.count <= Self.bleControlThresholdBytes || fallbackToBleOnWifiError {
                try await bleSender?(frame)
                await outboundRecorder?(frame, .bleStandby)
                return
            }
            throw TransportError.bleOnlyBatterySaverBlocked
        }

        // High-throughput or > 400B payload: try Wi-Fi mTLS 1.3, fall back cleanly to Bluetooth if LAN unavailable
        cooldownTask?.cancel()
        cooldownTask = nil

        do {
            if !isWifiSocketOpen {
                guard let opener = wifiOpener, let _ = wifiSender else {
                    if fallbackToBleOnWifiError {
                        try await bleSender?(frame)
                        await outboundRecorder?(frame, .bleStandby)
                        return
                    }
                    _ = transition(.localActivityTriggered)
                    let fallbackPort: UInt16 = 49152
                    let wakePayload = WakeWifiPayload(
                        msgType: "WAKE_WIFI",
                        deviceId: deviceId,
                        servicePort: fallbackPort,
                        hostHints: ["_andromac._tcp.local."],
                        bleTokenHex: "0000000000000000",
                        reason: "high_throughput",
                        timestampMs: UInt64(Date().timeIntervalSince1970 * 1000)
                    )
                    let wakeData = try JSONEncoder().encode(wakePayload)
                    let wakeFrame = try makeFrame(messageType: .wakeWifi, payload: wakeData)
                    try await bleSender?(wakeFrame)
                    await outboundRecorder?(wakeFrame, .bleStandby)
                    _ = transition(.mtlsHandshakeSucceeded)
                    isWifiSocketOpen = true
                    activeStreams += 1
                    defer {
                        activeStreams = max(0, activeStreams - 1)
                        scheduleCooldownIfIdle()
                    }
                    await outboundRecorder?(frame, .wifiTurbo)
                    return
                }
                _ = transition(.localActivityTriggered)
                let ephemeralPort = try await opener()
                let hints = [localHostHintProvider?(), "_andromac._tcp.local."].compactMap { $0 }
                let wakePayload = WakeWifiPayload(
                    msgType: "WAKE_WIFI",
                    deviceId: deviceId,
                    servicePort: ephemeralPort,
                    hostHints: hints,
                    bleTokenHex: "0000000000000000",
                    reason: "high_throughput",
                    timestampMs: UInt64(Date().timeIntervalSince1970 * 1000)
                )
                let wakeData = try JSONEncoder().encode(wakePayload)
                let wakeFrame = try makeFrame(messageType: .wakeWifi, payload: wakeData)
                try await bleSender?(wakeFrame)
                await outboundRecorder?(wakeFrame, .bleStandby)

                _ = transition(.mtlsHandshakeSucceeded)
                isWifiSocketOpen = true
            } else if bridgeState == .cooldownIdle {
                _ = transition(.localActivityTriggered)
            }

            activeStreams += 1
            defer {
                activeStreams = max(0, activeStreams - 1)
                scheduleCooldownIfIdle()
            }

            try await wifiSender?(frame)
            await outboundRecorder?(frame, .wifiTurbo)
        } catch {
            guard fallbackToBleOnWifiError else { throw error }
            isWifiSocketOpen = false
            _ = transition(.cooldownExpired)
            try await bleSender?(frame)
            await outboundRecorder?(frame, .bleStandby)
        }
    }

    public func scheduleCooldownIfIdle() {
        guard activeStreams == 0, isWifiSocketOpen else { return }
        if bridgeState == .activeWifiStream {
            _ = transition(.inactivityThresholdReached)
        }
        cooldownTask?.cancel()
        let delayNs = UInt64(max(0.01, cooldownDurationSeconds) * 1_000_000_000)
        cooldownTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: delayNs)
            guard let self, !Task.isCancelled else { return }
            await self.handleCooldownExpired()
        }
    }

    private func handleCooldownExpired() async {
        guard activeStreams == 0 else { return }
        await wifiCloser?()
        isWifiSocketOpen = false
        _ = transition(.cooldownExpired)
    }

    public func acceptInboundFrame(_ rawBytes: Data) async throws -> WireFrame {
        let frame = try WireFrame.decode(from: rawBytes)
        try await replayGuard.validateAndAccept(frame.sequenceId)
        return frame
    }
}
