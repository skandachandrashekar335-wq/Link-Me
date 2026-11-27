import Foundation

public enum BatteryStateEvent: Sendable, Equatable {
    case bleWakeReceived
    case localActivityTriggered
    case mtlsHandshakeSucceeded
    case handshakeTimedOutOrFailed
    case inactivityThresholdReached
    case cooldownExpired
}

/// Deterministic 4-state battery state machine:
/// DORMANT_BLE -> HANDSHAKE_WAKE -> ACTIVE_WIFI_STREAM -> COOLDOWN_IDLE -> DORMANT_BLE.
public struct BatteryStateMachine: Sendable, Equatable {
    public private(set) var state: BridgeState = .dormantBle

    public init(initialState: BridgeState = .dormantBle) {
        self.state = initialState
    }

    @discardableResult
    public mutating func handle(_ event: BatteryStateEvent) -> BridgeState {
        switch (state, event) {
        case (.dormantBle, .bleWakeReceived),
             (.dormantBle, .localActivityTriggered):
            state = .handshakeWake

        case (.handshakeWake, .mtlsHandshakeSucceeded):
            state = .activeWifiStream

        case (.handshakeWake, .handshakeTimedOutOrFailed):
            state = .dormantBle

        case (.activeWifiStream, .inactivityThresholdReached):
            state = .cooldownIdle

        case (.cooldownIdle, .localActivityTriggered),
             (.cooldownIdle, .bleWakeReceived):
            state = .activeWifiStream

        case (.cooldownIdle, .cooldownExpired):
            state = .dormantBle

        default:
            break
        }
        return state
    }
}
