package com.linkme.androidbridge.notifyaddon

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Pure JVM-testable coordinate mapper clamping normalized [0.0, 1.0] mirror viewport coordinates
 * onto display pixel dimensions.
 */
object AddonRemoteInputMapper {
    fun toPixel(normalized: Double, maxDimensionPx: Int): Float {
        if (maxDimensionPx <= 1) return 0f
        val clamped = normalized.coerceIn(0.0, 1.0)
        return (clamped * maxDimensionPx.toDouble()).toFloat().coerceIn(0f, (maxDimensionPx - 1).toFloat())
    }
}

/**
 * Modular AccessibilityService hosted inside AndroidBridge-NotifyAddon so the primary APK stays
 * Play-Protect clean (zero BIND_ACCESSIBILITY_SERVICE in core AndroidBridge manifest).
 * Receives 0x0E REMOTE_INPUT commands exclusively via signature-verified MIRROR_IPC broadcasts.
 */
class RemoteControlAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    private fun screenDimensions(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = getSystemService(WindowManager::class.java)?.currentWindowMetrics?.bounds
            val w = bounds?.width()?.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            val h = bounds?.height()?.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
            w to h
        } else {
            val dm = resources.displayMetrics
            dm.widthPixels to dm.heightPixels
        }
    }

    fun executeRemoteInput(payload: RemoteInputIpcMessage) {
        mainHandler.post {
            val (screenW, screenH) = screenDimensions()
            when (payload.type.lowercase()) {
                "tap" -> {
                    val px = AddonRemoteInputMapper.toPixel(payload.x ?: return@post, screenW)
                    val py = AddonRemoteInputMapper.toPixel(payload.y ?: return@post, screenH)
                    performStroke(px, py, px, py, durationMs = 45L)
                }

                "long_press" -> {
                    val px = AddonRemoteInputMapper.toPixel(payload.x ?: return@post, screenW)
                    val py = AddonRemoteInputMapper.toPixel(payload.y ?: return@post, screenH)
                    val dur = (payload.durationMs ?: 550).toLong().coerceIn(300L, 2500L)
                    performStroke(px, py, px, py, durationMs = dur)
                }

                "swipe" -> {
                    val px1 = AddonRemoteInputMapper.toPixel(payload.x1 ?: return@post, screenW)
                    val py1 = AddonRemoteInputMapper.toPixel(payload.y1 ?: return@post, screenH)
                    val px2 = AddonRemoteInputMapper.toPixel(payload.x2 ?: return@post, screenW)
                    val py2 = AddonRemoteInputMapper.toPixel(payload.y2 ?: return@post, screenH)
                    val dur = (payload.durationMs ?: 220).toLong().coerceIn(50L, 1200L)
                    performStroke(px1, py1, px2, py2, durationMs = dur)
                }

                "key" -> {
                    when (payload.action?.lowercase()) {
                        "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                        "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                        "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                        "text" -> injectText(payload.text.orEmpty())
                    }
                }
            }
        }
    }

    private fun performStroke(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        val path = Path().apply {
            moveTo(x1, y1)
            if (x1 != x2 || y1 != y2) {
                lineTo(x2, y2)
            }
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    private fun injectText(text: String) {
        if (text.isEmpty()) return
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        val currentText = focused.text?.toString().orEmpty()
        val combined = if (currentText.isEmpty()) text else "$currentText$text"
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, combined)
        }
        focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    companion object {
        @Volatile
        private var instance: RemoteControlAccessibilityService? = null

        fun isConnected(): Boolean = instance != null

        fun dispatchRemoteInput(payload: RemoteInputIpcMessage): Boolean {
            val active = instance ?: return false
            active.executeRemoteInput(payload)
            return true
        }
    }
}
