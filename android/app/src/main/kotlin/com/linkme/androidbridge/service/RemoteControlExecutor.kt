package com.linkme.androidbridge.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.WindowManager
import com.linkme.androidbridge.core.protocol.RemoteInputPayload
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors
import kotlin.math.roundToInt

enum class CursorControlStatus {
    SHIZUKU_ACTIVE,
    SHIZUKU_PERMISSION_REQUIRED,
    SHIZUKU_NOT_RUNNING
}

/**
 * Pure JVM-testable builder converting 0x0E REMOTE_INPUT payloads into safe Android `input` commands
 * executed via Shizuku without declaring any AccessibilityService in AndroidManifest.xml.
 */
object ShizukuInputCommandBuilder {
    fun buildShellCommand(
        payload: RemoteInputPayload,
        screenWidthPx: Int = 1080,
        screenHeightPx: Int = 2400
    ): String? {
        val w = screenWidthPx.coerceAtLeast(2)
        val h = screenHeightPx.coerceAtLeast(2)
        return when (payload.type.lowercase()) {
            "tap" -> {
                val x = RemoteInputCoordinateMapper.toPixel(payload.x ?: return null, w).roundToInt()
                val y = RemoteInputCoordinateMapper.toPixel(payload.y ?: return null, h).roundToInt()
                "input tap $x $y"
            }

            "long_press" -> {
                val x = RemoteInputCoordinateMapper.toPixel(payload.x ?: return null, w).roundToInt()
                val y = RemoteInputCoordinateMapper.toPixel(payload.y ?: return null, h).roundToInt()
                val dur = (payload.durationMs ?: 550).coerceIn(300, 2500)
                "input swipe $x $y $x $y $dur"
            }

            "swipe" -> {
                val x1 = RemoteInputCoordinateMapper.toPixel(payload.x1 ?: return null, w).roundToInt()
                val y1 = RemoteInputCoordinateMapper.toPixel(payload.y1 ?: return null, h).roundToInt()
                val x2 = RemoteInputCoordinateMapper.toPixel(payload.x2 ?: return null, w).roundToInt()
                val y2 = RemoteInputCoordinateMapper.toPixel(payload.y2 ?: return null, h).roundToInt()
                val dur = (payload.durationMs ?: 220).coerceIn(50, 1500)
                "input swipe $x1 $y1 $x2 $y2 $dur"
            }

            "back", "home", "recents", "enter", "backspace", "key" -> {
                val act = (payload.action ?: payload.type).lowercase()
                val keycode = when (act) {
                    "back" -> 4
                    "home" -> 3
                    "recents" -> 187
                    "enter", "return" -> 66
                    "backspace", "delete", "del" -> 67
                    "escape", "esc" -> 111
                    "tab" -> 61
                    "space" -> 62
                    "dpad_up", "up" -> 19
                    "dpad_down", "down" -> 20
                    "dpad_left", "left" -> 21
                    "dpad_right", "right" -> 22
                    else -> return null
                }
                "input keyevent $keycode"
            }

            "text" -> {
                val raw = payload.text?.takeIf { it.isNotEmpty() } ?: return null
                val sanitized = sanitizeForInputText(raw)
                if (sanitized.isEmpty()) return null
                val singleQuoted = sanitized.replace("'", "'\\''")
                "input text '$singleQuoted'"
            }

            else -> null
        }
    }

    internal fun sanitizeForInputText(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            when {
                ch == ' ' -> sb.append("%s")
                // Android shell `input text` only resolves printable US-ASCII via KeyCharacterMap.
                ch.code < 0x20 || ch.code > 0x7E -> Unit
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}

/**
 * Zero-manifest-permission remote cursor & keyboard executor backed by Shizuku (`dev.rikka.shizuku`).
 */
object RemoteControlExecutor {
    const val SHIZUKU_PERMISSION_REQUEST_CODE = 2101
    private const val SHIZUKU_MANAGER_PACKAGE = "moe.shizuku.privileged.api"
    private val execPool = Executors.newSingleThreadExecutor()

    fun inspectStatus(): CursorControlStatus {
        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!running) return CursorControlStatus.SHIZUKU_NOT_RUNNING
        val granted = runCatching {
            !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        return if (granted) {
            CursorControlStatus.SHIZUKU_ACTIVE
        } else {
            CursorControlStatus.SHIZUKU_PERMISSION_REQUIRED
        }
    }

    fun requestEnableOrOpenShizuku(context: Context): Boolean {
        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (running) {
            val ok = runCatching {
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
                }
                true
            }.getOrDefault(false)
            if (ok) return true
        }
        val launchIntent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_MANAGER_PACKAGE)
            ?: Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(launchIntent)
            true
        }.getOrDefault(false)
    }

    private fun screenDimensions(context: Context): Pair<Int, Int> {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val b = context.getSystemService(WindowManager::class.java)?.currentWindowMetrics?.bounds
                val w = b?.width()?.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
                val h = b?.height()?.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels
                w to h
            } else {
                val dm = context.resources.displayMetrics
                dm.widthPixels to dm.heightPixels
            }
        }.getOrElse { 1080 to 2400 }
    }

    fun executeRemoteInput(context: Context, payload: RemoteInputPayload): CursorControlStatus {
        val status = inspectStatus()
        if (status != CursorControlStatus.SHIZUKU_ACTIVE) {
            return status
        }
        val (w, h) = screenDimensions(context)
        val shellCmd = ShizukuInputCommandBuilder.buildShellCommand(payload, w, h) ?: return status
        execPool.submit {
            runCatching {
                val newProcMethod = Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                ).apply { isAccessible = true }
                val proc = newProcMethod.invoke(null, arrayOf("sh", "-c", shellCmd), null, null) as Process
                val exit = proc.waitFor()
                if (exit != 0) {
                    val errText = runCatching { proc.errorStream.bufferedReader().readText().trim() }.getOrDefault("")
                    BridgeActivityHub.appendWireEvent("SHIZUKU_EXEC_FAIL(exit=$exit${if (errText.isNotEmpty()) ",err=$errText" else ""})")
                }
            }.onFailure { err ->
                BridgeActivityHub.appendWireEvent("SHIZUKU_EXEC_ERR(${err.message ?: "unknown"})")
            }
        }
        return CursorControlStatus.SHIZUKU_ACTIVE
    }
}
