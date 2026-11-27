package com.linkme.androidbridge.notifyaddon

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class NotificationEventMessage(
    val msgType: String = "NOTIFICATION_EVENT",
    val action: String,
    val sbnKey: String,
    val appName: String,
    val packageName: String,
    val title: String,
    val body: String,
    val timestampMs: Long,
    val canReply: Boolean
)

@Serializable
data class DeviceCommandMessage(
    val msgType: String = "DEVICE_COMMAND",
    val command: String,
    val sbnKey: String? = null,
    val replyText: String? = null,
    val durationSeconds: Int? = null,
    val timestampMs: Long
)

@Serializable
data class RemoteInputIpcMessage(
    val msgType: String = "REMOTE_INPUT",
    val type: String,
    val x: Double? = null,
    val y: Double? = null,
    val x1: Double? = null,
    val y1: Double? = null,
    val x2: Double? = null,
    val y2: Double? = null,
    val durationMs: Int? = null,
    val action: String? = null,
    val text: String? = null
)

object AddonIpcContract {
    const val CORE_PACKAGE_NAME = "com.linkme.androidbridge"
    const val ADDON_PACKAGE_NAME = "com.linkme.androidbridge.notifyaddon"
    const val PERMISSION_MIRROR_IPC = "com.linkme.androidbridge.permission.MIRROR_IPC"
    const val ACTION_NOTIFICATION_EVENT_IPC = "com.linkme.androidbridge.ACTION_NOTIFICATION_EVENT_IPC"
    const val ACTION_DEVICE_COMMAND_IPC = "com.linkme.androidbridge.ACTION_DEVICE_COMMAND_IPC"
    const val ACTION_REMOTE_INPUT_IPC = "com.linkme.androidbridge.ACTION_REMOTE_INPUT_IPC"
    const val EXTRA_PAYLOAD_JSON = "payload_json"
    private const val MAX_JSON_BYTES = 256 * 1024

    private val json = Json { ignoreUnknownKeys = true }

    fun shouldIgnorePackage(packageName: String): Boolean {
        return packageName == CORE_PACKAGE_NAME || packageName == ADDON_PACKAGE_NAME
    }

    fun encodeEvent(message: NotificationEventMessage): String {
        val raw = json.encodeToString(message)
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_JSON_BYTES) {
            "Notification event IPC payload exceeds 256 KB"
        }
        return raw
    }

    fun decodeCommand(rawJson: String?): DeviceCommandMessage? {
        if (rawJson.isNullOrBlank()) return null
        if (rawJson.toByteArray(Charsets.UTF_8).size > MAX_JSON_BYTES) return null
        return runCatching { json.decodeFromString<DeviceCommandMessage>(rawJson) }.getOrNull()
    }

    fun decodeRemoteInput(rawJson: String?): RemoteInputIpcMessage? {
        if (rawJson.isNullOrBlank()) return null
        if (rawJson.toByteArray(Charsets.UTF_8).size > MAX_JSON_BYTES) return null
        return runCatching { json.decodeFromString<RemoteInputIpcMessage>(rawJson) }.getOrNull()
    }
}
