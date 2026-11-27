import AppKit
import Foundation

/// Feature 6 & 7: Remote Media Deck (0x0A) and URL Handoff (0x0B).
public final class MacContinuityController: @unchecked Sendable {
    private let transport: FrameTransportSink?
    private let sequenceLock = NSLock()
    private var nextSequence: UInt64 = 1

    public init(transport: FrameTransportSink? = nil) {
        self.transport = transport
    }

    private func allocateSequence() -> UInt64 {
        sequenceLock.lock()
        defer { sequenceLock.unlock() }
        let current = nextSequence
        nextSequence &+= 1
        return current
    }

    public func makeMediaControlFrame(action: MediaControlAction) throws -> WireFrame {
        let payload = MediaControlPayload(action: action)
        let data = try JSONEncoder().encode(payload)
        return try WireFrame(messageType: .mediaControl, sequenceId: allocateSequence(), payload: data)
    }

    public func makeOpenUrlFrame(rawUrl: String) throws -> (WireFrame, URL) {
        let validated = try UrlSanitizer.validateStrict(rawUrl)
        let payload = try OpenUrlPayload(url: validated.absoluteString)
        let data = try JSONEncoder().encode(payload)
        let frame = try WireFrame(messageType: .openUrl, sequenceId: allocateSequence(), payload: data)
        return (frame, validated)
    }

    public func sendMediaControl(_ action: MediaControlAction) async throws {
        guard let transport else { return }
        let frame = try makeMediaControlFrame(action: action)
        try await transport.sendFrame(frame, preferBleIfSmall: true)
    }

    @discardableResult
    public func sendOpenUrl(_ rawUrl: String) async throws -> URL {
        let (frame, validated) = try makeOpenUrlFrame(rawUrl: rawUrl)
        if let transport {
            try await transport.sendFrame(frame, preferBleIfSmall: true)
        }
        return validated
    }

    public func handleInboundMediaControl(_ payload: MediaControlPayload) {
        let script: String
        switch payload.action {
        case .volUp:
            script = "set volume output volume ((output volume of (get volume settings)) + 6) without output muted"
        case .volDown:
            script = "set volume output volume ((output volume of (get volume settings)) - 6)"
        case .mute:
            script = "set volume output muted (not (output muted of (get volume settings)))"
        case .playPause:
            script = """
            if application "Music" is running then
                tell application "Music" to playpause
            else if application "Spotify" is running then
                tell application "Spotify" to playpause
            end if
            """
        case .next:
            script = """
            if application "Music" is running then
                tell application "Music" to next track
            else if application "Spotify" is running then
                tell application "Spotify" to next track
            end if
            """
        case .prev:
            script = """
            if application "Music" is running then
                tell application "Music" to previous track
            else if application "Spotify" is running then
                tell application "Spotify" to previous track
            end if
            """
        }

        DispatchQueue.global(qos: .userInitiated).async {
            let process = Process()
            process.executableURL = URL(fileURLWithPath: "/usr/bin/osascript")
            process.arguments = ["-e", script]
            process.standardOutput = Pipe()
            process.standardError = Pipe()
            try? process.run()
            process.waitUntilExit()
        }
    }

    @MainActor
    @discardableResult
    public func handleInboundOpenUrl(_ payload: OpenUrlPayload) throws -> URL {
        let safeUrl = try payload.validateStrict()
        NSWorkspace.shared.open(safeUrl)
        return safeUrl
    }
}
