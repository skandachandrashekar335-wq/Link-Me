import Foundation
import Observation

public enum BridgeAccentTheme: String, Codable, CaseIterable, Sendable {
    case liquidBlue
    case crimson
    case mint
    case amber
    case alabaster

    public var displayName: String {
        switch self {
        case .liquidBlue: return "Electric Blue"
        case .crimson: return "Crimson"
        case .mint: return "Mint"
        case .amber: return "Amber"
        case .alabaster: return "Alabaster"
        }
    }
}

public enum ClipboardDirection: String, Codable, CaseIterable, Sendable {
    case bidirectional
    case macToAndroid
    case androidToMac
    case disabled

    public var displayName: String {
        switch self {
        case .bidirectional: return "Bidirectional"
        case .macToAndroid: return "Mac → Android"
        case .androidToMac: return "Android → Mac"
        case .disabled: return "Disabled"
        }
    }

    public func allowsMacOutbound() -> Bool {
        self == .bidirectional || self == .macToAndroid
    }

    public func allowsMacInbound() -> Bool {
        self == .bidirectional || self == .androidToMac
    }
}

public struct BridgeRgba: Sendable, Equatable {
    public let r: Double
    public let g: Double
    public let b: Double
    public let a: Double

    public init(hex: UInt32, alpha: Double = 1.0) {
        self.r = Double((hex >> 16) & 0xFF) / 255.0
        self.g = Double((hex >> 8) & 0xFF) / 255.0
        self.b = Double(hex & 0xFF) / 255.0
        self.a = alpha
    }
}

public struct BridgePaletteSpec: Sendable, Equatable {
    public let surfaceLowest: BridgeRgba
    public let surface: BridgeRgba
    public let surfaceLow: BridgeRgba
    public let surfaceContainer: BridgeRgba
    public let surfaceHigh: BridgeRgba
    public let surfaceHighest: BridgeRgba
    public let primaryAccent: BridgeRgba
    public let secondaryAccent: BridgeRgba
    public let activeCardLight: BridgeRgba
    public let activeCardInk: BridgeRgba
    public let outlineVariant: BridgeRgba
    public let onSurface: BridgeRgba
    public let secondaryText: BridgeRgba
    public let isLight: Bool

    public static func forTheme(_ theme: BridgeAccentTheme) -> BridgePaletteSpec {
        switch theme {
        case .liquidBlue:
            return BridgePaletteSpec(
                surfaceLowest: BridgeRgba(hex: 0x0D0E11),
                surface: BridgeRgba(hex: 0x101114),
                surfaceLow: BridgeRgba(hex: 0x17191D),
                surfaceContainer: BridgeRgba(hex: 0x202329),
                surfaceHigh: BridgeRgba(hex: 0x292A2D),
                surfaceHighest: BridgeRgba(hex: 0x343538),
                primaryAccent: BridgeRgba(hex: 0x0A84FF),
                secondaryAccent: BridgeRgba(hex: 0x30D158),
                activeCardLight: BridgeRgba(hex: 0xAAC7FF),
                activeCardInk: BridgeRgba(hex: 0x001B3E),
                outlineVariant: BridgeRgba(hex: 0xFFFFFF, alpha: 0.10),
                onSurface: BridgeRgba(hex: 0xF5F5F7),
                secondaryText: BridgeRgba(hex: 0xA1A1AA),
                isLight: false
            )
        case .crimson:
            return BridgePaletteSpec(
                surfaceLowest: BridgeRgba(hex: 0x0D0E10),
                surface: BridgeRgba(hex: 0x121315),
                surfaceLow: BridgeRgba(hex: 0x1B1C1E),
                surfaceContainer: BridgeRgba(hex: 0x1F2022),
                surfaceHigh: BridgeRgba(hex: 0x292A2C),
                surfaceHighest: BridgeRgba(hex: 0x343537),
                primaryAccent: BridgeRgba(hex: 0xE11D48),
                secondaryAccent: BridgeRgba(hex: 0x74D8BD),
                activeCardLight: BridgeRgba(hex: 0xE4E2DE),
                activeCardInk: BridgeRgba(hex: 0x1B1C1A),
                outlineVariant: BridgeRgba(hex: 0x5C3F40),
                onSurface: BridgeRgba(hex: 0xE3E2E4),
                secondaryText: BridgeRgba(hex: 0xC8C6C3),
                isLight: false
            )
        case .mint:
            return BridgePaletteSpec(
                surfaceLowest: BridgeRgba(hex: 0x0D0E10),
                surface: BridgeRgba(hex: 0x121315),
                surfaceLow: BridgeRgba(hex: 0x171A1D),
                surfaceContainer: BridgeRgba(hex: 0x1D2226),
                surfaceHigh: BridgeRgba(hex: 0x252C31),
                surfaceHighest: BridgeRgba(hex: 0x2F373D),
                primaryAccent: BridgeRgba(hex: 0x10B981),
                secondaryAccent: BridgeRgba(hex: 0x38BDF8),
                activeCardLight: BridgeRgba(hex: 0xE4E2DE),
                activeCardInk: BridgeRgba(hex: 0x1B1C1A),
                outlineVariant: BridgeRgba(hex: 0x2A4D46),
                onSurface: BridgeRgba(hex: 0xE3E2E4),
                secondaryText: BridgeRgba(hex: 0xB9C6C9),
                isLight: false
            )
        case .amber:
            return BridgePaletteSpec(
                surfaceLowest: BridgeRgba(hex: 0x0E0D0B),
                surface: BridgeRgba(hex: 0x141210),
                surfaceLow: BridgeRgba(hex: 0x1C1916),
                surfaceContainer: BridgeRgba(hex: 0x24201C),
                surfaceHigh: BridgeRgba(hex: 0x2E2924),
                surfaceHighest: BridgeRgba(hex: 0x3A332D),
                primaryAccent: BridgeRgba(hex: 0xF59E0B),
                secondaryAccent: BridgeRgba(hex: 0xF5F3EF),
                activeCardLight: BridgeRgba(hex: 0xF5F3EF),
                activeCardInk: BridgeRgba(hex: 0x141210),
                outlineVariant: BridgeRgba(hex: 0x5B4728),
                onSurface: BridgeRgba(hex: 0xF5F3EF),
                secondaryText: BridgeRgba(hex: 0xC8BEB0),
                isLight: false
            )
        case .alabaster:
            return BridgePaletteSpec(
                surfaceLowest: BridgeRgba(hex: 0xEAE6DF),
                surface: BridgeRgba(hex: 0xF5F3EF),
                surfaceLow: BridgeRgba(hex: 0xFFFFFF),
                surfaceContainer: BridgeRgba(hex: 0xFFFFFF),
                surfaceHigh: BridgeRgba(hex: 0xE5E1D8),
                surfaceHighest: BridgeRgba(hex: 0xD8D3C8),
                primaryAccent: BridgeRgba(hex: 0xE11D48),
                secondaryAccent: BridgeRgba(hex: 0x0F766E),
                activeCardLight: BridgeRgba(hex: 0x111214),
                activeCardInk: BridgeRgba(hex: 0xF5F3EF),
                outlineVariant: BridgeRgba(hex: 0xC9C3B8),
                onSurface: BridgeRgba(hex: 0x111214),
                secondaryText: BridgeRgba(hex: 0x4B4E52),
                isLight: true
            )
        }
    }
}

/// Persisted customization and runtime transport configuration store backed by UserDefaults.
@MainActor
@Observable
public final class BridgePreferences {
    public nonisolated static let minCooldownSeconds = 5
    public nonisolated static let maxCooldownSeconds = 60

    @ObservationIgnored
    private let defaults: UserDefaults
    @ObservationIgnored
    private let keyPrefix: String
    @ObservationIgnored
    public var onPreferencesChanged: (@Sendable (BridgePreferencesSnapshot) -> Void)?

    public var accentTheme: BridgeAccentTheme {
        didSet { persistAndNotify() }
    }

    public var compactMode: Bool {
        didSet { persistAndNotify() }
    }

    public var showTelemetryFooter: Bool {
        didSet { persistAndNotify() }
    }

    public var wifiCooldownSeconds: Int {
        didSet {
            let clamped = Self.clampCooldown(wifiCooldownSeconds)
            if clamped != wifiCooldownSeconds {
                wifiCooldownSeconds = clamped
                return
            }
            persistAndNotify()
        }
    }

    public var bleOnlyBatterySaver: Bool {
        didSet { persistAndNotify() }
    }

    public var autoAcceptFiles: Bool {
        didSet { persistAndNotify() }
    }

    public var clipboardDirection: ClipboardDirection {
        didSet { persistAndNotify() }
    }

    public var deviceAlias: String {
        didSet { persistAndNotify() }
    }

    public var paletteSpec: BridgePaletteSpec {
        BridgePaletteSpec.forTheme(accentTheme)
    }

    public var snapshot: BridgePreferencesSnapshot {
        BridgePreferencesSnapshot(
            accentTheme: accentTheme,
            compactMode: compactMode,
            showTelemetryFooter: showTelemetryFooter,
            wifiCooldownSeconds: wifiCooldownSeconds,
            bleOnlyBatterySaver: bleOnlyBatterySaver,
            autoAcceptFiles: autoAcceptFiles,
            clipboardDirection: clipboardDirection,
            deviceAlias: normalizedAlias
        )
    }

    public var normalizedAlias: String {
        let trimmed = deviceAlias.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "MacBridge" : String(trimmed.prefix(48))
    }

    public init(defaults: UserDefaults = .standard, keyPrefix: String = "linkme.prefs.") {
        self.defaults = defaults
        self.keyPrefix = keyPrefix

        if let rawTheme = defaults.string(forKey: keyPrefix + "accentTheme"),
           let parsedTheme = BridgeAccentTheme(rawValue: rawTheme) {
            self.accentTheme = parsedTheme
        } else {
            self.accentTheme = .liquidBlue
        }

        self.compactMode = defaults.object(forKey: keyPrefix + "compactMode") as? Bool ?? false
        self.showTelemetryFooter = defaults.object(forKey: keyPrefix + "showTelemetryFooter") as? Bool ?? true

        let storedCooldown = defaults.object(forKey: keyPrefix + "wifiCooldownSeconds") as? Int ?? 15
        self.wifiCooldownSeconds = Self.clampCooldown(storedCooldown)

        self.bleOnlyBatterySaver = defaults.object(forKey: keyPrefix + "bleOnlyBatterySaver") as? Bool ?? false
        self.autoAcceptFiles = defaults.object(forKey: keyPrefix + "autoAcceptFiles") as? Bool ?? true

        if let rawDir = defaults.string(forKey: keyPrefix + "clipboardDirection"),
           let parsedDir = ClipboardDirection(rawValue: rawDir) {
            self.clipboardDirection = parsedDir
        } else {
            self.clipboardDirection = .bidirectional
        }

        let defaultAlias = Host.current().localizedName ?? "MacBridge"
        let storedAlias = defaults.string(forKey: keyPrefix + "deviceAlias")
        self.deviceAlias = (storedAlias?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false)
            ? storedAlias!
            : defaultAlias
    }

    public nonisolated static func clampCooldown(_ seconds: Int) -> Int {
        min(maxCooldownSeconds, max(minCooldownSeconds, seconds))
    }

    private func persistAndNotify() {
        defaults.set(accentTheme.rawValue, forKey: keyPrefix + "accentTheme")
        defaults.set(compactMode, forKey: keyPrefix + "compactMode")
        defaults.set(showTelemetryFooter, forKey: keyPrefix + "showTelemetryFooter")
        defaults.set(wifiCooldownSeconds, forKey: keyPrefix + "wifiCooldownSeconds")
        defaults.set(bleOnlyBatterySaver, forKey: keyPrefix + "bleOnlyBatterySaver")
        defaults.set(autoAcceptFiles, forKey: keyPrefix + "autoAcceptFiles")
        defaults.set(clipboardDirection.rawValue, forKey: keyPrefix + "clipboardDirection")
        defaults.set(normalizedAlias, forKey: keyPrefix + "deviceAlias")
        onPreferencesChanged?(snapshot)
    }
}

public struct BridgePreferencesSnapshot: Sendable, Equatable {
    public let accentTheme: BridgeAccentTheme
    public let compactMode: Bool
    public let showTelemetryFooter: Bool
    public let wifiCooldownSeconds: Int
    public let bleOnlyBatterySaver: Bool
    public let autoAcceptFiles: Bool
    public let clipboardDirection: ClipboardDirection
    public let deviceAlias: String
}
