package org.maun.messenger.webrtc

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import org.maun.messenger.MaunApp
import org.maun.messenger.push.Notifications

/**
 * Stays up for as long as a call rings on this phone while Maun is not on
 * screen. Its notification is the ringing: Android plays the ringtone and
 * vibrates for it, over and over, whatever becomes of the app's own process;
 * and with this service running, the process is still there to hear that the
 * caller gave up, or to take the call when Answer is tapped.
 */
class RingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notifications = Notifications(this)
        val notification = notifications.incomingCall(
            name = intent?.getStringExtra(EXTRA_NAME) ?: "Maun",
            video = intent?.getBooleanExtra(EXTRA_VIDEO, false) == true,
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Ringing lasts under a minute; this kind of service may run for three.
                startForeground(Notifications.ID_RINGING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
            } else {
                startForeground(Notifications.ID_RINGING, notification)
            }
        } catch (_: Exception) {
            // Android would not let the service stay. The notification rings without it.
            notifications.showIncomingCall(notification)
            stopSelf()
            return START_NOT_STICKY
        }
        // Started, then the call was over before this ran.
        val call = (application as MaunApp).messenger.calls.state.value
        if (call == null || !call.incoming || call.phase != CallUi.Phase.Ringing) stop()
        return START_NOT_STICKY
    }

    /** Android's limit for this kind of service has been reached. Ringing ends long before. */
    override fun onTimeout(startId: Int) = stop()

    private fun stop() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val EXTRA_NAME = "name"
        private const val EXTRA_VIDEO = "video"

        /** Starts ringing. False when Android does not allow the service right now. */
        fun start(context: Context, name: String, video: Boolean): Boolean = try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RingService::class.java).putExtra(EXTRA_NAME, name).putExtra(EXTRA_VIDEO, video),
            )
            true
        } catch (_: Exception) {
            false
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, RingService::class.java)) }
        }
    }
}
