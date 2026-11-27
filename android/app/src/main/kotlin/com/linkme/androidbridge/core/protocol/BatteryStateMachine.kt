package com.linkme.androidbridge.core.protocol

enum class BatteryStateEvent {
    BLE_WAKE_RECEIVED,
    LOCAL_ACTIVITY_TRIGGERED,
    MTLS_HANDSHAKE_SUCCEEDED,
    HANDSHAKE_TIMED_OUT_OR_FAILED,
    INACTIVITY_THRESHOLD_REACHED,
    COOLDOWN_EXPIRED
}

/**
 * Deterministic 4-state battery state machine:
 * DORMANT_BLE -> HANDSHAKE_WAKE -> ACTIVE_WIFI_STREAM -> COOLDOWN_IDLE -> DORMANT_BLE.
 */
class BatteryStateMachine(
    initialState: BridgeState = BridgeState.DORMANT_BLE
) {
    var state: BridgeState = initialState
        private set

    fun handle(event: BatteryStateEvent): BridgeState {
        state = when (state to event) {
            BridgeState.DORMANT_BLE to BatteryStateEvent.BLE_WAKE_RECEIVED,
            BridgeState.DORMANT_BLE to BatteryStateEvent.LOCAL_ACTIVITY_TRIGGERED -> BridgeState.HANDSHAKE_WAKE

            BridgeState.HANDSHAKE_WAKE to BatteryStateEvent.MTLS_HANDSHAKE_SUCCEEDED -> BridgeState.ACTIVE_WIFI_STREAM

            BridgeState.HANDSHAKE_WAKE to BatteryStateEvent.HANDSHAKE_TIMED_OUT_OR_FAILED -> BridgeState.DORMANT_BLE

            BridgeState.ACTIVE_WIFI_STREAM to BatteryStateEvent.INACTIVITY_THRESHOLD_REACHED -> BridgeState.COOLDOWN_IDLE

            BridgeState.COOLDOWN_IDLE to BatteryStateEvent.LOCAL_ACTIVITY_TRIGGERED,
            BridgeState.COOLDOWN_IDLE to BatteryStateEvent.BLE_WAKE_RECEIVED -> BridgeState.ACTIVE_WIFI_STREAM

            BridgeState.COOLDOWN_IDLE to BatteryStateEvent.COOLDOWN_EXPIRED -> BridgeState.DORMANT_BLE

            else -> state
        }
        return state
    }
}
