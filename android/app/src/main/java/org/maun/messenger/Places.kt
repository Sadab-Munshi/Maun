package org.maun.messenger

import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.provider.Settings

/**
 * Whether this phone's own location switch is on.
 *
 * Permission and the switch are two different things, and a share started
 * with the switch off is a card that never fills in. An app cannot turn the
 * switch on — Android keeps that for the system, and a normal app that tried
 * would need WRITE_SECURE_SETTINGS, which only a system app may hold. So Maun
 * notices, says so, and opens the screen where the phone's owner can.
 */
fun locationSwitchedOn(context: Context): Boolean {
    val manager = context.getSystemService(LocationManager::class.java) ?: return false
    return try {
        manager.isLocationEnabled
    } catch (_: Exception) {
        false
    }
}

/** Opens the phone's own location settings, so that the switch can be found. */
fun openLocationSettings(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: Exception) {
        // No settings app will answer; the dialog has already said what to do.
    }
}
