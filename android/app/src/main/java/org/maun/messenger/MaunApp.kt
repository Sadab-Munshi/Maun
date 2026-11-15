package org.maun.messenger

import android.app.Application

class MaunApp : Application() {
    /** Created on first use, so that starting the process does no work by itself. */
    val messenger: Messenger by lazy { Messenger(this) }
}
