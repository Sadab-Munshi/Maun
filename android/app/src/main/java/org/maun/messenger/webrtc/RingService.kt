package org.maun.messenger.webrtc

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import org.maun.messenger.MaunApp
import org.maun.messenger.push.Notifications

/**
 * Runs only while a call is coming in and Maun is not on screen, never while
 * nothing is happening.
 *
 * It starts the moment the relay's wake-up says "a call" ([prepare]), before
 * the call itself has been fetched: from then on Android keeps the app awake.
 * When the call is there it rings ([ring]): its notification carries the
 * phone's ringtone and a vibration, which Android repeats until the call is
 * answered, declined or given up, and opens the call over the lock screen. It
 * stops as soon as the ringing does, or after a few seconds if no call
 * turned up after all.
 */
class RingService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val giveUp = Runnable { if (!ringing) stop() }
    private var ringing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notifications = Notifications(this)
        val ring = intent?.action == ACTION_RING
        val notification = if (ring) {
            notifications.incomingCall(
                name = intent?.getStringExtra(EXTRA_NAME) ?: "Maun",
                video = intent?.getBooleanExtra(EXTRA_VIDEO, false) == true,
            )
        } else {
            notifications.callComing()
        }
        try {
            startForeground(Notifications.ID_RINGING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
        } catch (_: Exception) {
            // Android would not let the service stay. The notification rings without it.
            if (ring) notifications.showIncomingCall(notification)
            stopSelf()
            return START_NOT_STICKY
        }
        if (ring) {
            ringing = true
            handler.removeCallbacks(giveUp)
            // Started, then the call was over before this ran.
            if (!callStillRinging()) stop()
        } else if (!ringing) {
            handler.removeCallbacks(giveUp)
            handler.postDelayed(giveUp, PREPARE_MILLIS)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(giveUp)
        super.onDestroy()
    }

    /**
     * Whether the call this start belongs to is still ringing. From the app's
     * own state, unless a test has said otherwise: the question is asked of a
     * function so that the answer can be given without a whole app behind it.
     */
    internal var callStillRinging: () -> Boolean = {
        val call = (application as MaunApp).messenger.calls.state.value
        call != null && call.incoming && call.phase == CallUi.Phase.Ringing
    }

    private fun stop() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        internal const val ACTION_RING = "org.maun.messenger.RING"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_VIDEO = "video"

        /** How long to wait for the call a wake-up announced, before concluding there is none. */
        private const val PREPARE_MILLIS = 20_000L

        /** A wake-up says a call is coming: stay awake for it. False when Android does not allow it right now. */
        fun prepare(context: Context): Boolean = launch(context, Intent(context, RingService::class.java))

        /** The call is here: ring. False when Android does not allow the service right now. */
        fun ring(context: Context, name: String, video: Boolean): Boolean = launch(
            context,
            Intent(context, RingService::class.java).setAction(ACTION_RING).putExtra(EXTRA_NAME, name).putExtra(EXTRA_VIDEO, video),
        )

        private fun launch(context: Context, intent: Intent): Boolean = try {
            ContextCompat.startForegroundService(context, intent)
            true
        } catch (_: Exception) {
            false
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, RingService::class.java)) }
        }
    }
}
