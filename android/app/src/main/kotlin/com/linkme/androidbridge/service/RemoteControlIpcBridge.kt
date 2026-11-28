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
    private const val MAX_JSON_BYTES = 256 * 1024

    private val json = Json { ignoreUnknownKeys = true }

    fun inspectCursorStatus(context: Context): CursorControlAddonStatus {
        return when (RemoteControlExecutor.inspectStatus()) {
            CursorControlStatus.SHIZUKU_ACTIVE -> CursorControlAddonStatus.ACTIVE
            CursorControlStatus.SHIZUKU_PERMISSION_REQUIRED -> CursorControlAddonStatus.ACCESSIBILITY_DISABLED
            CursorControlStatus.SHIZUKU_NOT_RUNNING -> CursorControlAddonStatus.COMPANION_NOT_INSTALLED
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
        return when (RemoteControlExecutor.executeRemoteInput(context, payload)) {
            CursorControlStatus.SHIZUKU_ACTIVE -> CursorControlAddonStatus.ACTIVE
            CursorControlStatus.SHIZUKU_PERMISSION_REQUIRED -> CursorControlAddonStatus.ACCESSIBILITY_DISABLED
            CursorControlStatus.SHIZUKU_NOT_RUNNING -> CursorControlAddonStatus.COMPANION_NOT_INSTALLED
        }
    }
}
