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
 * Pure JVM-testable coordinate & text helper for Accessibility cursor, gesture, and keyboard execution.
 */
object AddonRemoteInputMapper {
    fun toPixel(normalized: Double, maxDimensionPx: Int): Float {
        if (maxDimensionPx <= 1) return 0f
        val clamped = normalized.coerceIn(0.0, 1.0)
        return (clamped * maxDimensionPx.toDouble()).toFloat().coerceIn(0f, (maxDimensionPx - 1).toFloat())
    }

    fun applyBackspace(current: String): String {
        return if (current.isEmpty()) "" else current.dropLast(1)
    }

    fun applyAppendText(current: String, addition: String): String {
        return if (current.isEmpty()) addition else "$current$addition"
    }
}

/**
 * Modular AccessibilityService hosted strictly inside AndroidBridge-NotifyAddon so the primary APK stays
 * Play-Protect clean. Receives 0x0E REMOTE_INPUT frames exclusively over signature-verified MIRROR_IPC.
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

                "text" -> injectText(payload.text.orEmpty())

                "key" -> handleKeyAction(payload.action?.lowercase(), payload.text.orEmpty(), screenW, screenH)
            }
        }
    }

    private fun handleKeyAction(action: String?, text: String, screenW: Int, screenH: Int) {
        when (action) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "text" -> injectText(text)
            "backspace" -> performBackspace()
            "enter" -> performEnterAction()
            "paste" -> performPaste()
            "dpad_up" -> performDirectionalSwipe(0.5, 0.55, 0.5, 0.35, screenW, screenH)
            "dpad_down" -> performDirectionalSwipe(0.5, 0.45, 0.5, 0.65, screenW, screenH)
            "dpad_left" -> performDirectionalSwipe(0.6, 0.5, 0.35, 0.5, screenW, screenH)
            "dpad_right" -> performDirectionalSwipe(0.4, 0.5, 0.65, 0.5, screenW, screenH)
        }
    }

    private fun performDirectionalSwipe(x1: Double, y1: Double, x2: Double, y2: Double, w: Int, h: Int) {
        performStroke(
            AddonRemoteInputMapper.toPixel(x1, w),
            AddonRemoteInputMapper.toPixel(y1, h),
            AddonRemoteInputMapper.toPixel(x2, w),
            AddonRemoteInputMapper.toPixel(y2, h),
            durationMs = 140L
        )
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

    private fun findFocusedEditableNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val direct = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (direct != null && direct.isEditable) {
            return direct
        }
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isEditable && node.isVisibleToUser) {
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.addLast(it) }
            }
        }
        return direct
    }

    private fun injectText(addition: String) {
        if (addition.isEmpty()) return
        val focused = findFocusedEditableNode() ?: return
        val currentText = focused.text?.toString().orEmpty()
        val combined = AddonRemoteInputMapper.applyAppendText(currentText, addition)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, combined)
        }
        focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun performBackspace() {
        val focused = findFocusedEditableNode() ?: return
        val currentText = focused.text?.toString().orEmpty()
        val updated = AddonRemoteInputMapper.applyBackspace(currentText)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated)
        }
        focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun performEnterAction() {
        val focused = findFocusedEditableNode() ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (focused.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) {
                return
            }
        }
        focused.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun performPaste() {
        val focused = findFocusedEditableNode() ?: return
        focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
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
