import Foundation
import IOKit.ps

public struct LocalBatteryReading: Sendable, Equatable {
    public let level: Int?
    public let isCharging: Bool
    public let hasBattery: Bool

    public init(level: Int?, isCharging: Bool, hasBattery: Bool) {
        self.level = level.map { min(100, max(0, $0)) }
        self.isCharging = isCharging
        self.hasBattery = hasBattery
    }
}

/// Reads real macOS internal battery capacity & charging status via IOKit PowerSources (never synthetic defaults).
public enum MacBatteryMonitor {
    public static func readBattery() -> LocalBatteryReading {
        guard let blob = IOPSCopyPowerSourcesInfo()?.takeRetainedValue(),
              let sources = IOPSCopyPowerSourcesList(blob)?.takeRetainedValue() as? [CFTypeRef] else {
            return LocalBatteryReading(level: nil, isCharging: true, hasBattery: false)
        }
        for source in sources {
            guard let desc = IOPSGetPowerSourceDescription(blob, source)?.takeUnretainedValue() as? [String: Any],
                  (desc[kIOPSIsPresentKey as String] as? Bool) ?? true else {
                continue
            }
            if let current = desc[kIOPSCurrentCapacityKey as String] as? Int,
               let maxCap = desc[kIOPSMaxCapacityKey as String] as? Int,
               maxCap > 0 {
                let pct = min(100, max(0, (current * 100) / maxCap))
                let isCharging = (desc[kIOPSIsChargingKey as String] as? Bool)
                    ?? ((desc[kIOPSPowerSourceStateKey as String] as? String) == kIOPSACPowerValue)
                return LocalBatteryReading(level: pct, isCharging: isCharging, hasBattery: true)
            }
        }
        return LocalBatteryReading(level: nil, isCharging: true, hasBattery: false)
    }
}

/// Feature 5: Peer battery telemetry tracking & "Find My Phone" Ring command dispatch.
public actor TelemetryFeature {
    private let transport: FrameTransportSink?
    private var nextSeq: UInt64 = 1

    public private(set) var latestPeerTelemetry: PingTelemetryPayload?

    public init(transport: FrameTransportSink? = nil) {
        self.transport = transport
    }

    public func updatePeerTelemetry(_ telemetry: PingTelemetryPayload) {
        latestPeerTelemetry = telemetry
    }

    public func emitLocalTelemetry(deviceId: String, bridgeState: BridgeState) async throws -> PingTelemetryPayload? {
        let reading = MacBatteryMonitor.readBattery()
        guard let realLevel = reading.level else { return nil }
        let payload = PingTelemetryPayload(
            deviceId: deviceId,
            batteryLevel: realLevel,
            isCharging: reading.isCharging,
            batterySaverActive: ProcessInfo.processInfo.isLowPowerModeEnabled,
            transportState: bridgeState,
            timestampMs: UInt64(Date().timeIntervalSince1970 * 1000)
        )
        if let transport {
            let data = try JSONEncoder().encode(payload)
            nextSeq &+= 1
            let frame = try WireFrame(messageType: .pingTelemetry, sequenceId: nextSeq, payload: data)
            try await transport.sendFrame(frame, preferBleIfSmall: true)
        }
        return payload
    }

    public func sendRingPhoneCommand(durationSeconds: Int = 15, now: Date = Date()) async throws -> DeviceCommandPayload {
        let command = DeviceCommandPayload(
            command: "ringPhone",
            durationSeconds: durationSeconds,
            timestampMs: UInt64(now.timeIntervalSince1970 * 1000)
        )
        if let transport {
            let json = try JSONEncoder().encode(command)
            nextSeq &+= 1
            let frame = try WireFrame(messageType: .deviceCommand, sequenceId: nextSeq, payload: json)
            try await transport.sendFrame(frame, preferBleIfSmall: true)
        }
        return command
    }
}
