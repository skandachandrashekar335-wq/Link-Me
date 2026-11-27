package com.linkme.androidbridge.service

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import com.linkme.androidbridge.core.protocol.MediaControlAction
import com.linkme.androidbridge.core.protocol.MediaControlPayload
import com.linkme.androidbridge.core.protocol.OpenUrlPayload
import com.linkme.androidbridge.core.protocol.UrlSanitizer
import com.linkme.androidbridge.core.protocol.WireFrame
import com.linkme.androidbridge.core.protocol.WireMessageType
import com.linkme.androidbridge.core.transport.FrameTransportSink
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicLong

/**
 * Handles remote Media Control (0x0A) and URL Handoff (0x0B) on Android.
 */
class ContinuityFeature(
    private val transport: FrameTransportSink? = null
) {
    private val sequenceCounter = AtomicLong(1L)
    private val json = Json { ignoreUnknownKeys = true }

    fun makeMediaControlFrame(action: MediaControlAction): WireFrame {
        val payload = MediaControlPayload(action = action.wireName)
        val bytes = json.encodeToString(payload).toByteArray(Charsets.UTF_8)
        return WireFrame(
            messageType = WireMessageType.MEDIA_CONTROL,
            sequenceId = sequenceCounter.getAndIncrement(),
            payload = bytes
        )
    }

    fun makeOpenUrlFrame(rawUrl: String): Pair<WireFrame, String> {
        val safeUrl = UrlSanitizer.validateStrict(rawUrl)
        val payload = OpenUrlPayload(url = safeUrl)
        val bytes = json.encodeToString(payload).toByteArray(Charsets.UTF_8)
        val frame = WireFrame(
            messageType = WireMessageType.OPEN_URL,
            sequenceId = sequenceCounter.getAndIncrement(),
            payload = bytes
        )
        return frame to safeUrl
    }

    fun sendMediaControl(action: MediaControlAction) {
        val frame = makeMediaControlFrame(action)
        transport?.sendFrame(frame, preferBleIfSmall = true)
    }

    fun sendOpenUrl(rawUrl: String): String {
        val (frame, safeUrl) = makeOpenUrlFrame(rawUrl)
        transport?.sendFrame(frame, preferBleIfSmall = true)
        return safeUrl
    }

    fun handleInboundMediaControl(context: Context, payload: MediaControlPayload): Boolean {
        val action = payload.resolveAction() ?: return false
        val audioManager = context.getSystemService(AudioManager::class.java) ?: return false

        when (action) {
            MediaControlAction.PLAY_PAUSE -> dispatchMediaKey(audioManager, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            MediaControlAction.NEXT -> dispatchMediaKey(audioManager, KeyEvent.KEYCODE_MEDIA_NEXT)
            MediaControlAction.PREV -> dispatchMediaKey(audioManager, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            MediaControlAction.VOL_UP ->
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            MediaControlAction.VOL_DOWN ->
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            MediaControlAction.MUTE ->
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
        }
        return true
    }

    fun handleInboundOpenUrl(context: Context, payload: OpenUrlPayload): String {
        val safeUrl = payload.validateStrict()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
        return safeUrl
    }

    private fun dispatchMediaKey(audioManager: AudioManager, keyCode: Int) {
        val eventTime = SystemClock.uptimeMillis()
        audioManager.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0))
        audioManager.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0))
    }
}
