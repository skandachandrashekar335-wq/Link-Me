package com.linkme.androidbridge.notifyaddon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives reply / dismiss commands forwarded from the core AndroidBridge app.
 * Protected by signature-level permission com.linkme.androidbridge.permission.MIRROR_IPC.
 */
class AddonCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AddonIpcContract.ACTION_DEVICE_COMMAND_IPC) return
        val raw = intent.getStringExtra(AddonIpcContract.EXTRA_PAYLOAD_JSON)
        val command = AddonIpcContract.decodeCommand(raw) ?: return
        AddonNotificationListenerService.activeInstance?.handleCommand(command)
    }
}
