import Foundation
import UserNotifications

public struct MirroredNotificationItem: Identifiable, Sendable, Equatable {
    public let id: String
    public let appName: String
    public let packageName: String
    public let title: String
    public let body: String
    public let timestampMs: UInt64
    public let canReply: Bool

    public init(from event: NotificationEventPayload) {
        self.id = event.sbnKey
        self.appName = event.appName
        self.packageName = event.packageName
        self.title = event.title
        self.body = event.body
        self.timestampMs = event.timestampMs
        self.canReply = event.canReply
    }
}

/// Feature 4: Native macOS UNUserNotificationCenter presentation & inline reply/dismiss command forwarding.
public actor MacNotificationManager {
    public static let categoryIdentifier = "LINKME_MIRROR_CATEGORY"
    public static let replyActionIdentifier = "LINKME_REPLY_ACTION"
    public static let dismissActionIdentifier = "LINKME_DISMISS_ACTION"

    private let transport: FrameTransportSink?
    private var nextSeq: UInt64 = 1
    public private(set) var recentNotifications: [MirroredNotificationItem] = []
    public private(set) var isEnabled: Bool = true

    public init(transport: FrameTransportSink? = nil) {
        self.transport = transport
    }

    public func setEnabled(_ enabled: Bool) {
        isEnabled = enabled
    }

    public static func makeNotificationCategory() -> UNNotificationCategory {
        let replyAction = UNTextInputNotificationAction(
            identifier: replyActionIdentifier,
            title: "Reply",
            options: [],
            textInputButtonTitle: "Send",
            textInputPlaceholder: "Reply via Android..."
        )
        let dismissAction = UNNotificationAction(
            identifier: dismissActionIdentifier,
            title: "Dismiss",
            options: [.destructive]
        )
        return UNNotificationCategory(
            identifier: categoryIdentifier,
            actions: [replyAction, dismissAction],
            intentIdentifiers: [],
            options: [.customDismissAction]
        )
    }

    public func handleIncomingEvent(_ event: NotificationEventPayload, postSystemBanner: Bool = true) async throws {
        guard isEnabled else { return }
        if event.action == "DISMISS" {
            recentNotifications.removeAll { $0.id == event.sbnKey }
            if postSystemBanner {
                UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [event.sbnKey])
            }
            return
        }

        let item = MirroredNotificationItem(from: event)
        recentNotifications.removeAll { $0.id == item.id }
        recentNotifications.insert(item, at: 0)
        if recentNotifications.count > 20 {
            recentNotifications.removeLast(recentNotifications.count - 20)
        }

        if postSystemBanner {
            let content = UNMutableNotificationContent()
            content.title = "\(event.appName) · \(event.title)"
            content.body = event.body
            content.sound = .default
            content.categoryIdentifier = Self.categoryIdentifier
            let request = UNNotificationRequest(identifier: event.sbnKey, content: content, trigger: nil)
            try await UNUserNotificationCenter.current().add(request)
        }
    }

    public func sendInlineReply(sbnKey: String, replyText: String, now: Date = Date()) async throws -> DeviceCommandPayload {
        let command = DeviceCommandPayload(
            command: "replyToNotification",
            sbnKey: sbnKey,
            replyText: replyText,
            timestampMs: UInt64(now.timeIntervalSince1970 * 1000)
        )
        try await emitCommand(command)
        return command
    }

    public func sendDismiss(sbnKey: String, now: Date = Date()) async throws -> DeviceCommandPayload {
        recentNotifications.removeAll { $0.id == sbnKey }
        let command = DeviceCommandPayload(
            command: "dismissNotification",
            sbnKey: sbnKey,
            timestampMs: UInt64(now.timeIntervalSince1970 * 1000)
        )
        try await emitCommand(command)
        return command
    }

    private func emitCommand(_ command: DeviceCommandPayload) async throws {
        guard let transport else { return }
        let json = try JSONEncoder().encode(command)
        nextSeq &+= 1
        let frame = try WireFrame(messageType: .deviceCommand, sequenceId: nextSeq, payload: json)
        try await transport.sendFrame(frame, preferBleIfSmall: false)
    }
}
