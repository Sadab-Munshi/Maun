package org.maun.messenger

import android.app.Application
import org.maun.messenger.push.Background
import org.maun.messenger.push.FetchWorker
import org.maun.messenger.push.Notifications
import org.maun.messenger.network.NetworkWatch

class MaunApp : Application() {
    // Created on first use, so that starting the process does no work by itself,
    // and only once: a notification arriving and a screen opening at the same
    // moment must find the same Messenger.
    private val built = lazy { Messenger(this) }

    val messenger: Messenger
        get() = built.value

    /**
     * Frees the decoded message sounds when the last screen of the app closes.
     * The next sound loads them again; nothing else is kept by them.
     */
    fun releaseSounds() {
        if (built.isInitialized()) built.value.sounds.release()
    }

    override fun onCreate() {
        super.onCreate()
        Notifications(this).createChannels()
        Background.enforceDefaultOffOnUpgrade(this)
        // In case a wake-up goes missing, or there is nothing on this phone to send one.
        FetchWorker.scheduleHourly(this)
        // A network that comes back ends any wait that was measured without one.
        // Only heard, never polled, and only acted on if the app is already up.
        NetworkWatch.start(this) { if (built.isInitialized()) built.value.onNetworkAvailable() }
    }
}
