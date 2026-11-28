package com.linkme.androidbridge.ui

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linkme.androidbridge.service.BridgeActivityHub

/**
 * Zero-permission wake & Stealth Black Battery-Saver Activity for Phone Mirroring.
 * - Uses setTurnScreenOn + setShowWhenLocked so sleeping phones wake immediately for MediaProjection.
 * - Requests dismissKeyguard when unprotected; leaves PIN lockscreen interactive on Mac if locked.
 * - When `EXTRA_STEALTH_BLACK` is enabled, dims physical screenBrightness to 0.01f while keeping
 *   VirtualDisplay capture bright and live. Tapping restores brightness immediately.
 */
class ScreenWakeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val stealthMode = intent?.getBooleanExtra(EXTRA_STEALTH_BLACK, false) ?: false
        val keyguardMgr = getSystemService(KeyguardManager::class.java)
        if (keyguardMgr != null && !keyguardMgr.isDeviceLocked) {
            runCatching { keyguardMgr.requestDismissKeyguard(this, null) }
        }

        if (!stealthMode) {
            // Wake & unlock complete — exit cleanly without covering foreground apps
            finish()
            return
        }

        BridgeActivityHub.setStealthBlack(true)
        window.attributes = window.attributes.apply {
            screenBrightness = 0.01f
        }

        setContent {
            val stealthActive by BridgeActivityHub.stealthBlackActive.collectAsState()
            LaunchedEffect(stealthActive) {
                if (!stealthActive) {
                    finish()
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable {
                        BridgeActivityHub.setStealthBlack(false)
                        finish()
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(24.dp)
                ) {
                    Text(
                        text = "LINK-ME STEALTH MIRROR ACTIVE",
                        color = Color.White.copy(alpha = 0.14f),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Tap anywhere on phone to restore screen",
                        color = Color.White.copy(alpha = 0.10f),
                        fontSize = 11.sp
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        BridgeActivityHub.setStealthBlack(false)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_STEALTH_BLACK = "extra_stealth_black"
    }
}
