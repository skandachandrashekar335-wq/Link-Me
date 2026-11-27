package com.linkme.androidbridge.notifyaddon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives reply/dismiss commands and 0x0E REMOTE_INPUT cursor gestures forwarded from core AndroidBridge.
 * Protected by signature-level permission com.linkme.androidbridge.permission.MIRROR_IPC.
 */
class AddonCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val raw = intent.getStringExtra(AddonIpcContract.EXTRA_PAYLOAD_JSON)
        when (intent.action) {
            AddonIpcContract.ACTION_DEVICE_COMMAND_IPC -> {
                val command = AddonIpcContract.decodeCommand(raw) ?: return
                AddonNotificationListenerService.activeInstance?.handleCommand(command)
            }

            AddonIpcContract.ACTION_REMOTE_INPUT_IPC -> {
                val remoteInput = AddonIpcContract.decodeRemoteInput(raw) ?: return
                RemoteControlAccessibilityService.dispatchRemoteInput(remoteInput)
            }
        }
    }
}
