package com.rama.riya

import android.content.Intent
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * A tile in the notification shade's Quick Settings row, next to Wi-Fi and Flashlight.
 * It shows at a glance whether Riya's Accessibility is ON (needed for most phone control) or OFF
 * (needed while a bank / payment app is open), and tapping it jumps straight to the Accessibility
 * switch, collapsing the shade first, so switching Riya off before a payment and back on after is
 * one tap plus one real toggle, instead of hunting through Settings each time.
 *
 * Android does not let any app flip another app's permission switch from code, on purpose: that
 * exact ability is what banking-fraud apps would want, so this tile can only take you to the real
 * switch, never press it for you.
 */
class RiyaTile : TileService() {

    private fun accessibilityOn(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return enabled.contains(packageName)
    }

    private fun refresh() {
        val t = qsTile ?: return
        val on = accessibilityOn()
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "Riya"
        t.subtitle = if (on) "On \u2014 tap to switch off for payments" else "Off \u2014 tap to switch back on"
        t.updateTile()
    }

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val i = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            if (android.os.Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(
                android.app.PendingIntent.getActivity(this, 773, i, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            ) else { @Suppress("DEPRECATION") startActivityAndCollapse(i) }
        } catch (e: Exception) { }
        refresh()
    }
}
