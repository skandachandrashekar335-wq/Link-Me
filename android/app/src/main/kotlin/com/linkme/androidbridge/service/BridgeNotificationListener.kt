package com.linkme.androidbridge.service

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.linkme.androidbridge.core.protocol.DeviceCommandPayload
import com.linkme.androidbridge.core.protocol.NotificationEventPayload
import java.util.concurrent.ConcurrentHashMap

/**
 * Abstracts Android Notification.Action + RemoteInput so reply routing can be unit-tested without Android framework mocks.
 */
interface ReplyActionExecutor {
    fun executeReply(replyText: String): Boolean
}

class AndroidRemoteInputExecutor(
    private val service: NotificationListenerService,
    private val action: Notification.Action
) : ReplyActionExecutor {
    override fun executeReply(replyText: String): Boolean {
        val remoteInput = action.remoteInputs?.firstOrNull() ?: return false
        val intent = Intent()
        val bundle = Bundle().apply {
            putCharSequence(remoteInput.resultKey, replyText)
        }
        RemoteInput.addResultsToIntent(arrayOf(remoteInput), intent, bundle)
        return try {
            action.actionIntent.send(service, 0, intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}

/**
 * Feature 4: Captures posted/removed notifications, filters ongoing/own/muted apps,
 * and dispatches RemoteInput inline replies received from macOS.
 */
class BridgeNotificationListener : NotificationListenerService() {
    private val router = NotificationMirrorRouter(ownPackageName = "com.linkme.androidbridge")

    override fun onListenerConnected() {
        super.onListenerConnected()
        activeInstance = this
    }

    override fun onListenerDisconnected() {
        activeInstance = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        if ((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) return

        val extras = notification.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val appName = runCatching {
            val info = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(sbn.packageName.substringAfterLast('.'))

        val replyAction = notification.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
        val executor = replyAction?.let { AndroidRemoteInputExecutor(this, it) }

        val event = router.onPosted(
            sbnKey = sbn.key,
            packageName = sbn.packageName,
            appName = appName,
            title = title,
            body = body,
            timestampMs = sbn.postTime,
            replyExecutor = executor
        ) ?: return
        NotificationCompanionBridge.dispatchIncomingEvent(event)
        onNotificationEvent?.invoke(event)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val key = sbn?.key ?: return
        val pkg = sbn.packageName
        val event = router.onRemoved(sbnKey = key, packageName = pkg) ?: return
        NotificationCompanionBridge.dispatchIncomingEvent(event)
        onNotificationEvent?.invoke(event)
    }

    fun handleDeviceCommand(command: DeviceCommandPayload): Boolean {
        if (command.command == "dismissNotification" && command.sbnKey != null) {
            cancelNotification(command.sbnKey)
            return true
        }
        return router.handleDeviceCommand(command)
    }

    companion object {
        @Volatile
        var activeInstance: BridgeNotificationListener? = null

        @Volatile
        var onNotificationEvent: ((NotificationEventPayload) -> Unit)? = null
    }
}

/**
 * Pure Kotlin notification filtering and RemoteInput reply router for unit testing & service delegation.
 */
class NotificationMirrorRouter(
    private val ownPackageName: String,
    private val mutedPackages: MutableSet<String> = mutableSetOf()
) {
    private val replyExecutors = ConcurrentHashMap<String, ReplyActionExecutor>()

    fun mutePackage(packageName: String) {
        mutedPackages.add(packageName)
    }

    fun onPosted(
        sbnKey: String,
        packageName: String,
        appName: String,
        title: String,
        body: String,
        timestampMs: Long,
        replyExecutor: ReplyActionExecutor?
    ): NotificationEventPayload? {
        if (packageName == ownPackageName || packageName in mutedPackages) {
            return null
        }
        if (replyExecutor != null) {
            replyExecutors[sbnKey] = replyExecutor
        }
        return NotificationEventPayload(
            action = "POST",
            sbnKey = sbnKey,
            appName = appName,
            packageName = packageName,
            title = title,
            body = body,
            timestampMs = timestampMs,
            canReply = replyExecutor != null
        )
    }

    fun onRemoved(sbnKey: String, packageName: String, timestampMs: Long = System.currentTimeMillis()): NotificationEventPayload? {
        replyExecutors.remove(sbnKey)
        if (packageName == ownPackageName || packageName in mutedPackages) {
            return null
        }
        return NotificationEventPayload(
            action = "DISMISS",
            sbnKey = sbnKey,
            appName = packageName.substringAfterLast('.'),
            packageName = packageName,
            title = "",
            body = "",
            timestampMs = timestampMs,
            canReply = false
        )
    }

    fun handleDeviceCommand(command: DeviceCommandPayload): Boolean {
        if (command.command != "replyToNotification") return false
        val key = command.sbnKey ?: return false
        val text = command.replyText ?: return false
        val executor = replyExecutors[key] ?: return false
        return executor.executeReply(text)
    }
}
