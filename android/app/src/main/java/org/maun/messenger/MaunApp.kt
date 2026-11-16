package org.maun.messenger

import android.app.Application
import org.maun.messenger.push.Background
import org.maun.messenger.push.FetchWorker
import org.maun.messenger.push.Notifications

class MaunApp : Application() {
    /** Created on first use, so that starting the process does no work by itself. */
    val messenger: Messenger by lazy { Messenger(this) }

    override fun onCreate() {
        super.onCreate()
        Notifications(this).createChannels()
        Background.enforceDefaultOffOnUpgrade(this)
        // In case a wake-up goes missing, or there is nothing on this phone to send one.
        FetchWorker.scheduleHourly(this)
    }
}
