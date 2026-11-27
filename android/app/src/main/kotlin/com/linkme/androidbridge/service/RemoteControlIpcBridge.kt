package com.linkme.androidbridge.service

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.linkme.androidbridge.core.protocol.RemoteInputPayload
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class CursorControlAddonStatus {
    COMPANION_NOT_INSTALLED,
    ACCESSIBILITY_DISABLED,
    ACTIVE
}

/**
 * Pure JVM-testable coordinate mapper clamping normalized [0.0, 1.0] mirror viewport coordinates
 * onto display pixel dimensions.
 */
object RemoteInputCoordinateMapper {
    fun toPixel(normalized: Double, maxDimensionPx: Int): Float {
        if (maxDimensionPx <= 1) return 0f
        val clamped = normalized.coerceIn(0.0, 1.0)
        return (clamped * maxDimensionPx.toDouble()).toFloat().coerceIn(0f, (maxDimensionPx - 1).toFloat())
    }
}

/**
 * Signature-protected IPC bridge forwarding 0x0E REMOTE_INPUT cursor gestures from core AndroidBridge
 * to the optional AndroidBridge-NotifyAddon APK so core AndroidBridge declares zero AccessibilityService.
 */
object RemoteControlIpcBridge {
    const val ACTION_REMOTE_INPUT_IPC = "com.linkme.androidbridge.ACTION_REMOTE_INPUT_IPC"
    private const val ADDON_ACCESSIBILITY_CLASS = "com.linkme.androidbridge.notifyaddon.RemoteControlAccessibilityService"
    private const val MAX_JSON_BYTES = 256 * 1024

    private val json = Json { ignoreUnknownKeys = true }

    fun inspectCursorStatus(context: Context): CursorControlAddonStatus {
        val addonInstalled = runCatching {
            context.packageManager.getPackageInfo(NotificationCompanionBridge.ADDON_PACKAGE_NAME, 0)
            true
        }.getOrDefault(false)

        if (!addonInstalled) {
            return CursorControlAddonStatus.COMPANION_NOT_INSTALLED
        }

        val expectedComponent = ComponentName(
            NotificationCompanionBridge.ADDON_PACKAGE_NAME,
            ADDON_ACCESSIBILITY_CLASS
        )
        val fullName = expectedComponent.flattenToString()
        val shortName = expectedComponent.flattenToShortString()

        val enabledViaSettings = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
                .split(':')
                .any { it.equals(fullName, ignoreCase = true) || it.equals(shortName, ignoreCase = true) }
        }.getOrDefault(false)

        val enabledViaManager = runCatching {
            val mgr = context.getSystemService(AccessibilityManager::class.java)
            mgr?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                ?.any { it.resolveInfo?.serviceInfo?.packageName == NotificationCompanionBridge.ADDON_PACKAGE_NAME }
                ?: false
        }.getOrDefault(false)

        return if (enabledViaSettings || enabledViaManager) {
            CursorControlAddonStatus.ACTIVE
        } else {
            CursorControlAddonStatus.ACCESSIBILITY_DISABLED
        }
    }

    fun encodeRemoteInputJson(payload: RemoteInputPayload): String {
        val raw = json.encodeToString(payload)
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_JSON_BYTES) {
            "Remote input IPC payload exceeds 256 KB"
        }
        return raw
    }

    fun dispatchRemoteInput(context: Context, payload: RemoteInputPayload): CursorControlAddonStatus {
        val status = inspectCursorStatus(context)
        if (status != CursorControlAddonStatus.ACTIVE) {
            return status
        }
        val payloadJson = encodeRemoteInputJson(payload)
        val intent = Intent(ACTION_REMOTE_INPUT_IPC).apply {
            setPackage(NotificationCompanionBridge.ADDON_PACKAGE_NAME)
            putExtra(NotificationCompanionBridge.EXTRA_PAYLOAD_JSON, payloadJson)
        }
        context.sendBroadcast(intent, NotificationCompanionBridge.PERMISSION_MIRROR_IPC)
        return CursorControlAddonStatus.ACTIVE
    }
}
