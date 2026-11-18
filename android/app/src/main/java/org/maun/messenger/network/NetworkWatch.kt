package org.maun.messenger.network

import android.content.Context
import android.net.ConnectivityManager

/**
 * Hears about the phone's default network coming back.
 *
 * Not a poll and not a held connection: Android calls this when a network is
 * usable, and the only thing done about it is telling a connection that is
 * already waiting that it may try again. A phone with no network hears
 * nothing, and a phone that never loses its network hears nothing either.
 *
 * Registered once for the life of the process, from the application. There is
 * nothing to unregister: it goes away with the process.
 */
object NetworkWatch {
    private var started = false

    fun start(context: Context, onAvailable: () -> Unit) {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        synchronized(this) {
            if (started) return
            val watcher = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) = onAvailable()
            }
            started = try {
                // Only the default network: Wi-Fi, mobile data and everything
                // else report as it, and a connection to the relay does not
                // belong to one of them.
                manager.registerDefaultNetworkCallback(watcher)
                true
            } catch (_: Exception) {
                // Not allowed to watch on this phone, or nothing to watch yet.
                // Fetching after a wake-up works as it always did.
                false
            }
        }
    }
}
