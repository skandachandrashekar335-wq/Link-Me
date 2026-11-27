package com.linkme.androidbridge.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.linkme.androidbridge.core.protocol.BridgeState
import com.linkme.androidbridge.core.protocol.DeviceCommandPayload
import com.linkme.androidbridge.core.protocol.PingTelemetryPayload
import kotlin.math.abs

/**
 * Feature 5: Emits PING_TELEMETRY on >= 2% battery delta or charging state change (never polling),
 * and handles "Find My Phone" Ring commands with alarm audio + vibration.
 */
class TelemetryFeature(
    private val deviceId: String
) {
    private var lastReportedLevel: Int = -1
    private var lastChargingState: Boolean? = null
    private var activeRingtone: Ringtone? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun shouldEmitUpdate(batteryLevel: Int, isCharging: Boolean): Boolean {
        if (lastReportedLevel < 0 || lastChargingState == null) return true
        return abs(batteryLevel - lastReportedLevel) >= 2 || isCharging != lastChargingState
    }

    fun buildTelemetryIfSignificant(
        batteryLevel: Int,
        isCharging: Boolean,
        batterySaverActive: Boolean,
        transportState: BridgeState,
        timestampMs: Long = System.currentTimeMillis()
    ): PingTelemetryPayload? {
        if (!shouldEmitUpdate(batteryLevel, isCharging)) return null
        lastReportedLevel = batteryLevel
        lastChargingState = isCharging
        return PingTelemetryPayload(
            deviceId = deviceId,
            batteryLevel = batteryLevel.coerceIn(0, 100),
            isCharging = isCharging,
            batterySaverActive = batterySaverActive,
            transportState = transportState,
            timestampMs = timestampMs
        )
    }

    fun createBatteryReceiver(
        currentStateProvider: () -> BridgeState,
        onTelemetryReady: (PingTelemetryPayload) -> Unit
    ): BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_BATTERY_CHANGED) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            if (level < 0) return
            val pct = (level * 100) / scale
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            val powerMgr = context.getSystemService(PowerManager::class.java)
            val saver = powerMgr?.isPowerSaveMode ?: false

            buildTelemetryIfSignificant(
                batteryLevel = pct,
                isCharging = isCharging,
                batterySaverActive = saver,
                transportState = currentStateProvider()
            )?.let(onTelemetryReady)
        }
    }

    fun handleDeviceCommand(context: Context, command: DeviceCommandPayload) {
        when (command.command) {
            "ringPhone" -> startRing(context, durationSeconds = command.durationSeconds ?: 15)
            "stopRing" -> stopRing(context)
        }
    }

    fun startRing(context: Context, durationSeconds: Int = 15) {
        stopRing(context)
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val ringtone = RingtoneManager.getRingtone(context, uri)
        ringtone?.audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        ringtone?.play()
        activeRingtone = ringtone

        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
        vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 300), 0))

        mainHandler.postDelayed({ stopRing(context) }, durationSeconds * 1000L)
    }

    fun stopRing(context: Context) {
        activeRingtone?.stop()
        activeRingtone = null
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
        vibrator?.cancel()
    }
}
