package com.linkme.androidbridge.core.transport

import com.linkme.androidbridge.core.protocol.BatteryStateEvent
import com.linkme.androidbridge.core.protocol.BatteryStateMachine
import com.linkme.androidbridge.core.protocol.BridgeState
import com.linkme.androidbridge.core.protocol.ReplayGuard
import com.linkme.androidbridge.core.protocol.WakeWifiPayload
import com.linkme.androidbridge.core.protocol.WireFrame
import com.linkme.androidbridge.core.protocol.WireMessageType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

enum class TransportChannel {
    BLE_STANDBY,
    WIFI_TURBO
}

enum class TransferPriority {
    LOW_LATENCY_CONTROL,
    HIGH_THROUGHPUT
}

interface FrameTransportSink {
    fun sendFrame(frame: WireFrame, preferBleIfSmall: Boolean = false)
}

/**
 * Unified dual-transport orchestrator with 4-state battery machine and resettable 15s COOLDOWN_IDLE timer.
 */
class TransportManager(
    private val cooldownDurationMs: Long = 15_000L,
    private val bleSender: ((WireFrame) -> Unit)? = null,
    private val wifiOpener: (() -> Int)? = null,
    private val wifiSender: ((WireFrame) -> Unit)? = null,
    private val wifiCloser: (() -> Unit)? = null,
    private val outboundSink: ((WireFrame, TransportChannel) -> Unit)? = null
) : FrameTransportSink {
    private val stateMachine = BatteryStateMachine()
    private val nextSeq = AtomicLong(1L)
    private val activeStreams = AtomicInteger(0)
    private val replayGuard = ReplayGuard()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var cooldownFuture: ScheduledFuture<*>? = null

    @Volatile
    var isWifiSocketOpen: Boolean = false
        private set

    val bridgeState: BridgeState
        @Synchronized get() = stateMachine.state

    @Synchronized
    fun transition(event: BatteryStateEvent): BridgeState = stateMachine.handle(event)

    fun allocateSequenceId(): Long = nextSeq.getAndIncrement()

    fun makeFrame(type: WireMessageType, payload: ByteArray): WireFrame =
        WireFrame(messageType = type, sequenceId = allocateSequenceId(), payload = payload)

    fun sendFrame(
        msgType: WireMessageType,
        payload: ByteArray,
        priority: TransferPriority,
        deviceId: String = "22222222-2222-4222-8222-222222222222"
    ) {
        val frame = makeFrame(msgType, payload)
        routeFrame(frame, priority, deviceId)
    }

    override fun sendFrame(frame: WireFrame, preferBleIfSmall: Boolean) {
        val priority = if (preferBleIfSmall && frame.payload.size <= BLE_THRESHOLD_BYTES) {
            TransferPriority.LOW_LATENCY_CONTROL
        } else {
            TransferPriority.HIGH_THROUGHPUT
        }
        routeFrame(frame, priority, "22222222-2222-4222-8222-222222222222")
    }

    @Synchronized
    private fun routeFrame(frame: WireFrame, priority: TransferPriority, deviceId: String) {
        if (frame.payload.size <= BLE_THRESHOLD_BYTES && priority == TransferPriority.LOW_LATENCY_CONTROL) {
            bleSender?.invoke(frame)
            outboundSink?.invoke(frame, TransportChannel.BLE_STANDBY)
            return
        }

        cooldownFuture?.cancel(false)
        cooldownFuture = null

        if (!isWifiSocketOpen) {
            transition(BatteryStateEvent.LOCAL_ACTIVITY_TRIGGERED)
            val port = wifiOpener?.invoke() ?: 49152
            val wakePayload = WakeWifiPayload(
                msgType = "WAKE_WIFI",
                deviceId = deviceId,
                servicePort = port,
                hostHints = listOf("_andromac._tcp.local."),
                bleTokenHex = "0000000000000000",
                reason = "high_throughput",
                timestampMs = System.currentTimeMillis()
            )
            val wakeFrame = makeFrame(
                WireMessageType.WAKE_WIFI,
                Json.encodeToString(wakePayload).toByteArray(Charsets.UTF_8)
            )
            bleSender?.invoke(wakeFrame)
            outboundSink?.invoke(wakeFrame, TransportChannel.BLE_STANDBY)

            transition(BatteryStateEvent.MTLS_HANDSHAKE_SUCCEEDED)
            isWifiSocketOpen = true
        } else if (stateMachine.state == BridgeState.COOLDOWN_IDLE) {
            transition(BatteryStateEvent.LOCAL_ACTIVITY_TRIGGERED)
        }

        activeStreams.incrementAndGet()
        try {
            wifiSender?.invoke(frame)
            outboundSink?.invoke(frame, TransportChannel.WIFI_TURBO)
        } finally {
            if (activeStreams.decrementAndGet() <= 0) {
                activeStreams.set(0)
                scheduleCooldownIfIdle()
            }
        }
    }

    @Synchronized
    private fun scheduleCooldownIfIdle() {
        if (activeStreams.get() > 0 || !isWifiSocketOpen) return
        if (stateMachine.state == BridgeState.ACTIVE_WIFI_STREAM) {
            transition(BatteryStateEvent.INACTIVITY_THRESHOLD_REACHED)
        }
        cooldownFuture?.cancel(false)
        cooldownFuture = scheduler.schedule(
            {
                synchronized(this) {
                    if (activeStreams.get() == 0 && isWifiSocketOpen) {
                        wifiCloser?.invoke()
                        isWifiSocketOpen = false
                        transition(BatteryStateEvent.COOLDOWN_EXPIRED)
                    }
                }
            },
            cooldownDurationMs,
            TimeUnit.MILLISECONDS
        )
    }

    fun acceptInboundFrame(rawBytes: ByteArray): WireFrame {
        val frame = WireFrame.decode(rawBytes)
        replayGuard.validateAndAccept(frame.sequenceId)
        return frame
    }

    fun shutdown() {
        cooldownFuture?.cancel(false)
        scheduler.shutdownNow()
    }

    companion object {
        const val BLE_THRESHOLD_BYTES = 400
    }
}
