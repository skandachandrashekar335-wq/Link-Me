package com.linkme.androidbridge.notifyaddon

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap

/**
 * Isolated NotificationListenerService housed inside AndroidBridge-NotifyAddon APK.
 * Forwards sanitized notification metadata to the Play-Protect-clean core APK
 * strictly over signature-verified IPC (com.linkme.androidbridge.permission.MIRROR_IPC).
 */
class AddonNotificationListenerService : NotificationListenerService() {
    private val replyActions = ConcurrentHashMap<String, Notification.Action>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        activeInstance = this
    }

    override fun onListenerDisconnected() {
        activeInstance = null
        replyActions.clear()
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        if ((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) return
        if (AddonIpcContract.shouldIgnorePackage(sbn.packageName)) return

        val extras = notification.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val appName = runCatching {
            val info = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(sbn.packageName.substringAfterLast('.'))

        val replyAction = notification.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
        if (replyAction != null) {
            replyActions[sbn.key] = replyAction
        }

        val event = NotificationEventMessage(
            action = "POST",
            sbnKey = sbn.key,
            appName = appName,
            packageName = sbn.packageName,
            title = title,
            body = body,
            timestampMs = sbn.postTime,
            canReply = replyAction != null
        )
        broadcastEventToCore(event)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val key = sbn?.key ?: return
        replyActions.remove(key)
        if (AddonIpcContract.shouldIgnorePackage(sbn.packageName)) return

        val event = NotificationEventMessage(
            action = "DISMISS",
            sbnKey = key,
            appName = sbn.packageName.substringAfterLast('.'),
            packageName = sbn.packageName,
            title = "",
            body = "",
            timestampMs = System.currentTimeMillis(),
            canReply = false
        )
        broadcastEventToCore(event)
    }

    fun handleCommand(command: DeviceCommandMessage): Boolean {
        return when (command.command) {
            "dismissNotification" -> {
                val key = command.sbnKey ?: return false
                cancelNotification(key)
                true
            }

            "replyToNotification" -> {
                val key = command.sbnKey ?: return false
                val replyText = command.replyText ?: return false
                val action = replyActions[key] ?: return false
                val remoteInput = action.remoteInputs?.firstOrNull() ?: return false
                val intent = Intent()
                val bundle = Bundle().apply {
                    putCharSequence(remoteInput.resultKey, replyText)
                }
                RemoteInput.addResultsToIntent(arrayOf(remoteInput), intent, bundle)
                runCatching {
                    action.actionIntent.send(this, 0, intent)
                    true
                }.getOrDefault(false)
            }

            else -> false
        }
    }

    private fun broadcastEventToCore(event: NotificationEventMessage) {
        val encoded = runCatching { AddonIpcContract.encodeEvent(event) }.getOrNull() ?: return
        val intent = Intent(AddonIpcContract.ACTION_NOTIFICATION_EVENT_IPC).apply {
            setPackage(AddonIpcContract.CORE_PACKAGE_NAME)
            putExtra(AddonIpcContract.EXTRA_PAYLOAD_JSON, encoded)
        }
        sendBroadcast(intent, AddonIpcContract.PERMISSION_MIRROR_IPC)
    }

    companion object {
        @Volatile
        var activeInstance: AddonNotificationListenerService? = null
    }
}
