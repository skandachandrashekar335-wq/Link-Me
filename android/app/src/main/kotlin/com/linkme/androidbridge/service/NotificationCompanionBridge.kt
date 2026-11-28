package com.linkme.androidbridge.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.linkme.androidbridge.core.protocol.DeviceCommandPayload
import com.linkme.androidbridge.core.protocol.NotificationEventPayload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class NotificationAddonStatus {
    NOT_INSTALLED,
    INSTALLED_NEEDS_PERMISSION,
    ACTIVE
}

/**
 * Modular notification bridge that keeps the primary APK 100% Play-Protect clean
 * (zero BIND_NOTIFICATION_LISTENER_SERVICE in core manifest) while seamlessly accepting
 * events from AndroidBridge-NotifyAddon over signature-verified IPC or an ADB-granted listener.
 */
object NotificationCompanionBridge {
    const val CORE_PACKAGE_NAME = "com.linkme.androidbridge"
    const val ADDON_PACKAGE_NAME = "com.linkme.androidbridge.notifyaddon"
    const val PERMISSION_MIRROR_IPC = "com.linkme.androidbridge.permission.MIRROR_IPC"
    const val ACTION_NOTIFICATION_EVENT_IPC = "com.linkme.androidbridge.ACTION_NOTIFICATION_EVENT_IPC"
    const val ACTION_DEVICE_COMMAND_IPC = "com.linkme.androidbridge.ACTION_DEVICE_COMMAND_IPC"
    const val EXTRA_PAYLOAD_JSON = "payload_json"
    private const val MAX_IPC_JSON_BYTES = 256 * 1024

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    var onNotificationEvent: ((NotificationEventPayload) -> Unit)? = null

    private val _recentFeed = MutableStateFlow<List<NotificationEventPayload>>(emptyList())
    val recentFeed: StateFlow<List<NotificationEventPayload>> = _recentFeed.asStateFlow()

    fun inspectAddonStatus(context: Context): NotificationAddonStatus {
        val enabledPackages = runCatching {
            NotificationManagerCompat.getEnabledListenerPackages(context)
        }.getOrDefault(emptySet())

        return if (BridgeNotificationListener.activeInstance != null || context.packageName in enabledPackages) {
            NotificationAddonStatus.ACTIVE
        } else {
            NotificationAddonStatus.INSTALLED_NEEDS_PERMISSION
        }
    }

    fun dispatchIncomingEvent(event: NotificationEventPayload) {
        if (event.packageName == contextPackageOrCore(event.packageName)) {
            return
        }
        val current = _recentFeed.value.toMutableList()
        current.removeAll { it.sbnKey == event.sbnKey }
        if (event.action == "POST") {
            current.add(0, event)
            if (current.size > 20) {
                current.subList(20, current.size).clear()
            }
        }
        _recentFeed.value = current
        onNotificationEvent?.invoke(event)
    }

    fun dispatchDeviceCommand(context: Context, command: DeviceCommandPayload): Boolean {
        return BridgeNotificationListener.activeInstance?.handleDeviceCommand(command) ?: false
    }

    private fun contextPackageOrCore(pkg: String): String {
        return if (pkg == CORE_PACKAGE_NAME) CORE_PACKAGE_NAME else ""
    }

    fun encodeNotificationEventJson(payload: NotificationEventPayload): String {
        val encoded = json.encodeToString(payload)
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_IPC_JSON_BYTES) {
            "Notification IPC payload exceeds 256 KB"
        }
        return encoded
    }

    fun decodeNotificationEventJson(rawJson: String?): NotificationEventPayload? {
        if (rawJson.isNullOrBlank()) return null
        if (rawJson.toByteArray(Charsets.UTF_8).size > MAX_IPC_JSON_BYTES) return null
        return runCatching { json.decodeFromString<NotificationEventPayload>(rawJson) }.getOrNull()
    }

    fun encodeDeviceCommandJson(command: DeviceCommandPayload): String {
        val encoded = json.encodeToString(command)
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_IPC_JSON_BYTES) {
            "Device command IPC payload exceeds 256 KB"
        }
        return encoded
    }

    fun decodeDeviceCommandJson(rawJson: String?): DeviceCommandPayload? {
        if (rawJson.isNullOrBlank()) return null
        if (rawJson.toByteArray(Charsets.UTF_8).size > MAX_IPC_JSON_BYTES) return null
        return runCatching { json.decodeFromString<DeviceCommandPayload>(rawJson) }.getOrNull()
    }
}

/**
 * Manifest receiver gated by signature-level permission MIRROR_IPC.
 * Accepts sanitized notification events from the optional AndroidBridge-NotifyAddon APK.
 */
class NotificationCompanionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != NotificationCompanionBridge.ACTION_NOTIFICATION_EVENT_IPC) return
        val raw = intent.getStringExtra(NotificationCompanionBridge.EXTRA_PAYLOAD_JSON)
        val event = NotificationCompanionBridge.decodeNotificationEventJson(raw) ?: return
        NotificationCompanionBridge.dispatchIncomingEvent(event)
    }
}
