package com.linkme.androidbridge.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.linkme.androidbridge.R
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground MediaProjection service capturing Android display frames (up to 720x1600 @ 30-60 FPS over Wi-Fi)
 * with partial WakeLock + seamless pause/resume so Mac can toggle mirroring once permission is granted.
 */
class ScreenMirrorService : Service() {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val isEncoding = AtomicBoolean(false)
    private val isStreamingActive = AtomicBoolean(false)
    @Volatile private var lastSentAtMs: Long = 0L
    @Volatile private var targetFps: Int = 60
    @Volatile private var jpegQuality: Int = 65

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCaptureAndSelf()
                return START_NOT_STICKY
            }

            ACTION_PAUSE -> {
                isStreamingActive.set(false)
                releaseWakeLock()
                BridgeActivityHub.updateMirrorState {
                    it.copy(isSharingLocal = false, statusText = "Session standby (Mac can resume anytime)")
                }
                return START_NOT_STICKY
            }

            ACTION_RESUME -> {
                targetFps = (intent.getIntExtra(EXTRA_FPS, 60)).coerceIn(1, 60)
                jpegQuality = (intent.getIntExtra(EXTRA_QUALITY, 65)).coerceIn(25, 90)
                if (mediaProjection != null && virtualDisplay != null) {
                    isStreamingActive.set(true)
                    acquireWakeLock()
                    BridgeActivityHub.updateMirrorState {
                        it.copy(isSharingLocal = true, statusText = "Streaming Android @ ${targetFps}fps")
                    }
                }
                return START_NOT_STICKY
            }
        }

        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else 0
        runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), fgsType)
        }.onFailure {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        targetFps = (intent?.getIntExtra(EXTRA_FPS, 60) ?: 60).coerceIn(1, 60)
        jpegQuality = (intent?.getIntExtra(EXTRA_QUALITY, 65) ?: 65).coerceIn(25, 90)

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            stopCaptureAndSelf()
            return START_NOT_STICKY
        }

        startCapture(resultCode, resultData)
        return START_NOT_STICKY
    }

    private fun acquireWakeLock() {
        runCatching {
            if (wakeLock == null) {
                val pm = getSystemService(PowerManager::class.java) ?: return
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LinkMe:ScreenMirrorWake").apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(30 * 60 * 1000L)
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        stopCaptureResourcesOnly()
        val mgr = getSystemService(MediaProjectionManager::class.java) ?: run {
            stopCaptureAndSelf()
            return
        }
        val projection = mgr.getMediaProjection(resultCode, data) ?: run {
            stopCaptureAndSelf()
            return
        }

        val thread = HandlerThread("LinkMe-ScreenMirror").apply { start() }
        val handler = Handler(thread.looper)
        handlerThread = thread
        captureHandler = handler

        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopCaptureAndSelf()
            }
        }, handler)

        val metrics = resources.displayMetrics
        val srcW = metrics.widthPixels.coerceAtLeast(360)
        val srcH = metrics.heightPixels.coerceAtLeast(640)
        val wifiReady = BridgeActivityHub.wifiConnected.value
        val maxEdge = if (wifiReady) 720 else 540
        val scale = minOf(1f, maxEdge.toFloat() / minOf(srcW, srcH).toFloat())
        val outW = (srcW * scale).toInt().coerceAtLeast(320)
        val outH = (srcH * scale).toInt().coerceAtLeast(320)

        val reader = ImageReader.newInstance(outW, outH, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                if (!isStreamingActive.get()) {
                    return@setOnImageAvailableListener
                }
                val now = System.currentTimeMillis()
                val bleOnly = !BridgeActivityHub.wifiConnected.value
                val effFps = if (bleOnly) minOf(3, targetFps) else targetFps
                val effQuality = if (bleOnly) minOf(48, jpegQuality) else jpegQuality
                val minInterval = 1000L / effFps.coerceAtLeast(1)
                if (now - lastSentAtMs < minInterval || !isEncoding.compareAndSet(false, true)) {
                    return@setOnImageAvailableListener
                }
                lastSentAtMs = now
                try {
                    val plane = img.planes[0]
                    val buffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * outW
                    val bitmapW = outW + rowPadding / pixelStride
                    val paddedBitmap = Bitmap.createBitmap(bitmapW, outH, Bitmap.Config.ARGB_8888)
                    paddedBitmap.copyPixelsFromBuffer(buffer)
                    val cropped = if (bitmapW != outW) {
                        Bitmap.createBitmap(paddedBitmap, 0, 0, outW, outH).also {
                            paddedBitmap.recycle()
                        }
                    } else {
                        paddedBitmap
                    }
                    val baos = ByteArrayOutputStream(65_536)
                    cropped.compress(Bitmap.CompressFormat.JPEG, effQuality, baos)
                    cropped.recycle()
                    val jpegBytes = baos.toByteArray()
                    if (jpegBytes.size <= 950_000) {
                        BridgeActivityHub.onSendScreenFrameBytes?.invoke(outW, outH, now, jpegBytes)
                    }
                } finally {
                    isEncoding.set(false)
                }
            } finally {
                img.close()
            }
        }, handler)

        val vDisplay = projection.createVirtualDisplay(
            "LinkMe-ScreenMirror",
            outW,
            outH,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler
        )

        mediaProjection = projection
        imageReader = reader
        virtualDisplay = vDisplay
        isStreamingActive.set(true)
        hasActiveSession = true
        acquireWakeLock()

        BridgeActivityHub.updateMirrorState {
            it.copy(
                isSharingLocal = true,
                statusText = "Streaming Android (${outW}x$outH @ ${targetFps}fps)"
            )
        }
    }

    private fun stopCaptureResourcesOnly() {
        isStreamingActive.set(false)
        hasActiveSession = false
        releaseWakeLock()
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
        runCatching { handlerThread?.quitSafely() }
        handlerThread = null
        captureHandler = null
    }

    private fun stopCaptureAndSelf() {
        stopCaptureResourcesOnly()
        BridgeActivityHub.updateMirrorState {
            it.copy(isSharingLocal = false, statusText = "Idle")
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopCaptureResourcesOnly()
        BridgeActivityHub.updateMirrorState {
            it.copy(isSharingLocal = false)
        }
        super.onDestroy()
    }

    private fun ensureChannel() {
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Link-Me Screen Mirroring",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Link-Me Screen Mirror")
            .setContentText("Streaming screen to paired Mac over E2EE link")
            .setSmallIcon(R.drawable.ic_stat_linkme)
            .setOngoing(true)
            .build()

    companion object {
        @Volatile
        var hasActiveSession: Boolean = false

        const val CHANNEL_ID = "linkme_screen_mirror"
        const val NOTIFICATION_ID = 1004
        const val ACTION_START = "com.linkme.androidbridge.ACTION_MIRROR_START"
        const val ACTION_RESUME = "com.linkme.androidbridge.ACTION_MIRROR_RESUME"
        const val ACTION_PAUSE = "com.linkme.androidbridge.ACTION_MIRROR_PAUSE"
        const val ACTION_STOP = "com.linkme.androidbridge.ACTION_MIRROR_STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_FPS = "fps"
        const val EXTRA_QUALITY = "quality"
    }
}
