package com.linkme.androidbridge.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * Lightweight Share Sheet receiver forwarding ACTION_SEND / ACTION_SEND_MULTIPLE URIs
 * into MainActivity without blocking the UI thread.
 */
class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = extractUris(intent)
        val forwardIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_SHARED_FILES
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_GRANT_READ_URI_PERMISSION
            putParcelableArrayListExtra(EXTRA_SHARED_URIS, ArrayList(uris))
        }
        startActivity(forwardIntent)
        finish()
    }

    private fun extractUris(intent: Intent?): List<Uri> {
        return when (intent?.action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                listOfNotNull(uri)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                list.orEmpty()
            }
            else -> emptyList()
        }
    }

    companion object {
        const val ACTION_SHARED_FILES = "com.linkme.androidbridge.ACTION_SHARED_FILES"
        const val EXTRA_SHARED_URIS = "com.linkme.androidbridge.EXTRA_SHARED_URIS"
    }
}
