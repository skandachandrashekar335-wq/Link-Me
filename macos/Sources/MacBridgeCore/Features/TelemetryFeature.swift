import Foundation

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
