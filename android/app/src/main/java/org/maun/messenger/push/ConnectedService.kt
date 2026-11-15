package org.maun.messenger.push

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.maun.messenger.MaunApp

/**
 * "Stay connected": keeps Maun's connection to the relay open while the app is
 * closed, for phones without a push app. Android allows that only with a
 * permanent notification, which is what makes this a foreground service.
 *
 * The service does nothing itself. While it runs, [org.maun.messenger.Messenger]
 * keeps the connection it would otherwise drop.
 */
class ConnectedService : Service() {

    private val messenger
        get() = (application as MaunApp).messenger

    private var holding = false

    override fun onBind(intent: Intent?): IBinder? = null

    // Called for every start request, and each one must be answered by going
    // (or staying) in the foreground, or Android stops the app.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(
                this,
                Notifications.ID_CONNECTED,
                Notifications(this).connected(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
                } else {
                    0
                },
            )
        } catch (_: Exception) {
            // Android did not allow it just now. The next time the app is opened tries again.
            stopSelf()
            return START_NOT_STICKY
        }
        if (!holding) {
            holding = true
            messenger.onStayConnected(true)
        }
        // If Android kills the app to free memory, it brings the service back when it can.
        return START_STICKY
    }

    override fun onDestroy() {
        if (holding) messenger.onStayConnected(false)
        super.onDestroy()
    }

    companion object {
        /** Returns false when Android refuses, which it may while the app is not on screen. */
        fun start(context: Context): Boolean =
            try {
                ContextCompat.startForegroundService(context, Intent(context, ConnectedService::class.java))
                true
            } catch (_: Exception) {
                false
            }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectedService::class.java))
        }
    }
}
