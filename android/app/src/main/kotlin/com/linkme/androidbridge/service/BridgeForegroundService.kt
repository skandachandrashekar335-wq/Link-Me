package com.linkme.androidbridge.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.linkme.androidbridge.core.protocol.BatteryStateMachine
import com.linkme.androidbridge.core.protocol.BridgeState
import com.linkme.androidbridge.core.transport.BlePeripheralManager

/**
 * connectedDevice ForegroundService compliant with Android 15/16 permission checks
 * and PowerManager Battery Saver suspension.
 */
class BridgeForegroundService : Service() {
    private val stateMachine = BatteryStateMachine()
    private var blePeripheralManager: BlePeripheralManager? = null
    private var powerSaveReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        if (!hasRequiredTransportPermissions()) {
            stopSelf()
            return
        }

        val notification = buildNotification(stateMachine.state)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            }
        )

        val peripheral = BlePeripheralManager(this, beacon = null)
        blePeripheralManager = peripheral
        val powerMgr = getSystemService(PowerManager::class.java)
        peripheral.setBatterySaverActive(powerMgr?.isPowerSaveMode == true)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) {
                    val isSaver = context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
                    blePeripheralManager?.setBatterySaverActive(isSaver)
                }
            }
        }
        powerSaveReceiver = receiver
        registerReceiver(receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SEND_CLIPBOARD_TO_MAC) {
            onManualClipboardSyncRequested?.invoke()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        powerSaveReceiver?.let { runCatching { unregisterReceiver(it) } }
        blePeripheralManager?.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun hasRequiredTransportPermissions(): Boolean {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_ADVERTISE
            perms += Manifest.permission.BLUETOOTH_CONNECT
            perms += Manifest.permission.BLUETOOTH_SCAN
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        return perms.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Link-Me Continuity Bridge",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(state: BridgeState): Notification {
        val syncIntent = Intent(this, BridgeForegroundService::class.java).apply {
            action = ACTION_SEND_CLIPBOARD_TO_MAC
        }
        val syncPendingIntent = PendingIntent.getService(
            this,
            1,
            syncIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Link-Me Bridge Active")
            .setContentText("State: ${state.name}")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .addAction(0, "Send Clipboard to Mac", syncPendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "linkme_bridge_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_SEND_CLIPBOARD_TO_MAC = "com.linkme.androidbridge.ACTION_SEND_CLIPBOARD_TO_MAC"

        @Volatile
        var onManualClipboardSyncRequested: (() -> Unit)? = null
    }
}
