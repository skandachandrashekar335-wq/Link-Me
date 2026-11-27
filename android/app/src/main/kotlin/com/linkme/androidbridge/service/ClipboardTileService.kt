package com.linkme.androidbridge.service

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings Tile allowing one-tap "Send Clipboard to Mac" on Android 10+.
 */
class ClipboardTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            label = "Send Clipboard to Mac"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, BridgeForegroundService::class.java).apply {
            action = BridgeForegroundService.ACTION_SEND_CLIPBOARD_TO_MAC
        }
        startForegroundService(intent)
    }
}
