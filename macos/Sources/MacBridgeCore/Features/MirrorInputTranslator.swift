import AppKit
import CoreGraphics
import Foundation

public struct NormalizedMirrorPoint: Sendable, Equatable {
    public let x: Double
    public let y: Double

    public init(x: Double, y: Double) {
        self.x = min(1.0, max(0.0, x))
        self.y = min(1.0, max(0.0, y))
    }
}

public enum MirrorKeyEventOutcome: Sendable, Equatable {
    case remoteInput(RemoteInputPayload)
    case bufferedText(String)
    case copyClipboardThenKey
    case syncClipboardThenPaste
}

/// Pure, unit-testable translator converting macOS pointer, trackpad scroll, and keyboard
/// events into normalized Android 0x0E REMOTE_INPUT commands.
public enum MirrorInputTranslator {
    /// Normalizes an AppKit view-local point (flipped Y = top-to-bottom or standard bottom-to-top)
    /// into [0.0, 1.0] normalized phone coordinates inside `contentRect`.
    public static func normalizePoint(
        point: CGPoint,
        contentRect: CGRect,
        isFlipped: Bool = true
    ) -> NormalizedMirrorPoint? {
        guard contentRect.width > 1, contentRect.height > 1 else { return nil }
        let clampedX = min(contentRect.maxX, max(contentRect.minX, point.x))
        let clampedY = min(contentRect.maxY, max(contentRect.minY, point.y))
        let nx = Double((clampedX - contentRect.minX) / contentRect.width)
        let rawNy = Double((clampedY - contentRect.minY) / contentRect.height)
        let ny = isFlipped ? rawNy : (1.0 - rawNy)
        return NormalizedMirrorPoint(x: nx, y: ny)
    }

    /// Classifies pointer release into tap, long_press, or swipe using distance and duration thresholds.
    public static func classifyPointerRelease(
        startPoint: NormalizedMirrorPoint,
        endPoint: NormalizedMirrorPoint,
        pixelDistance: CGFloat,
        elapsedSeconds: TimeInterval
    ) -> RemoteInputPayload {
        if pixelDistance >= 6.0 {
            let ms = min(600, max(90, Int((elapsedSeconds * 1000.0).rounded())))
            return .swipe(
                x1: startPoint.x,
                y1: startPoint.y,
                x2: endPoint.x,
                y2: endPoint.y,
                durationMs: ms
            )
        }
        if elapsedSeconds >= 0.4 {
            return .longPress(x: endPoint.x, y: endPoint.y, durationMs: 550)
        }
        return .tap(x: endPoint.x, y: endPoint.y)
    }

    /// Translates accumulated macOS two-finger trackpad scroll deltas into a smooth swipe gesture
    /// anchored at `anchor` inside the mirrored phone screen.
    public static func translateScrollToSwipe(
        deltaX: CGFloat,
        deltaY: CGFloat,
        anchor: NormalizedMirrorPoint = NormalizedMirrorPoint(x: 0.5, y: 0.5),
        sensitivity: Double = 0.0022
    ) -> RemoteInputPayload? {
        let mag = hypot(Double(deltaX), Double(deltaY))
        guard mag >= 6.0 else { return nil }

        let dxNorm = min(0.32, max(-0.32, Double(deltaX) * sensitivity))
        let dyNorm = min(0.32, max(-0.32, Double(deltaY) * sensitivity))

        // Clamp start/end nicely away from extreme bezel edges so Android treats it cleanly as a scroll
        let sx = min(0.92, max(0.08, anchor.x))
        let sy = min(0.90, max(0.10, anchor.y))
        let ex = min(0.95, max(0.05, sx + dxNorm))
        let ey = min(0.95, max(0.05, sy + dyNorm))

        guard hypot(ex - sx, ey - sy) >= 0.015 else { return nil }
        return .swipe(x1: sx, y1: sy, x2: ex, y2: ey, durationMs: 160)
    }

    /// Maps macOS keyCode + characters + modifier flags into Android actions or buffered text.
    public static func translateKeyDown(
        keyCode: UInt16,
        characters: String?,
        charactersIgnoringModifiers: String?,
        modifierFlags: NSEvent.ModifierFlags
    ) -> MirrorKeyEventOutcome? {
        let hasCommand = modifierFlags.contains(.command)
        let hasControl = modifierFlags.contains(.control)
        let baseChar = (charactersIgnoringModifiers ?? characters ?? "").lowercased()

        if hasCommand {
            switch keyCode {
            case 18: // Cmd + 1
                return .remoteInput(.key("home"))
            case 19: // Cmd + 2
                return .remoteInput(.key("recents"))
            default:
                break
            }
            if baseChar == "c" {
                return .copyClipboardThenKey
            }
            if baseChar == "v" {
                return .syncClipboardThenPaste
            }
            return nil
        }

        if hasControl {
            return nil
        }

        switch keyCode {
        case 36, 76: // Return or Numpad Enter
            return .remoteInput(.key("enter"))
        case 48: // Tab
            return .remoteInput(.key("tab"))
        case 51: // Delete / Backspace
            return .remoteInput(.key("backspace"))
        case 53: // Escape
            return .remoteInput(.key("back"))
        case 126: // Arrow Up
            return .remoteInput(.key("dpad_up"))
        case 125: // Arrow Down
            return .remoteInput(.key("dpad_down"))
        case 123: // Arrow Left
            return .remoteInput(.key("dpad_left"))
        case 124: // Arrow Right
            return .remoteInput(.key("dpad_right"))
        default:
            break
        }

        if let chars = characters, !chars.isEmpty,
           !chars.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }) {
            return .bufferedText(chars)
        }
        return nil
    }
}

/// Coalesces fast macOS keystrokes over a 35ms window so typing words transmits compactly.
@MainActor
public final class MirrorTextBatcher {
    private let intervalSeconds: TimeInterval
    private let onFlush: (String) -> Void
    private var buffer: String = ""
    private var scheduledWork: DispatchWorkItem?

    public init(
        intervalMs: Int = 35,
        onFlush: @escaping (String) -> Void
    ) {
        self.intervalSeconds = Double(max(10, intervalMs)) / 1000.0
        self.onFlush = onFlush
    }

    public func enqueue(_ text: String) {
        guard !text.isEmpty else { return }
        buffer.append(text)
        guard scheduledWork == nil else { return }
        let work = DispatchWorkItem { [weak self] in
            self?.flushNow()
        }
        scheduledWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + intervalSeconds, execute: work)
    }

    public func flushNow() {
        scheduledWork?.cancel()
        scheduledWork = nil
        guard !buffer.isEmpty else { return }
        let out = buffer
        buffer = ""
        onFlush(out)
    }
}
