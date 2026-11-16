package org.maun.messenger.webrtc

import android.Manifest
import android.app.Service
import android.content.pm.PackageManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import org.maun.messenger.MaunApp
import org.maun.messenger.push.Notifications

/**
 * Keeps a call going while Maun is not on screen, with the notification
 * Android requires for that: who the call is with stays off it. Started when
 * a call is made or taken, which is always while the app is in front.
 */
class CallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val messenger = (application as MaunApp).messenger
        if (intent?.action == ACTION_HANG_UP) {
            messenger.calls.hangUp()
            // Asked to decline from the notification: there was never a call to keep going.
            if (!messenger.calls.busy) stopSelf()
            return START_NOT_STICKY
        }
        val notification = Notifications(this).inCall(hangUp(this))
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // A video call also keeps the camera when Maun is not in front, if the camera may be used at all.
                val camera = intent?.getBooleanExtra(EXTRA_VIDEO, false) == true &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    if (camera) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
                startForeground(Notifications.ID_CALL, notification, types)
            } else {
                startForeground(Notifications.ID_CALL, notification)
            }
        } catch (_: Exception) {
            // Android would not let it start from where the app is. The call goes on for as long as the app is in front.
            stopSelf()
        }
        // Started, then the call ended before this ran.
        if (!messenger.calls.busy) stopSelf()
        return START_NOT_STICKY
    }

    companion object {
        private const val ACTION_HANG_UP = "org.maun.messenger.HANG_UP"
        private const val EXTRA_VIDEO = "video"

        fun hangUp(context: Context): Intent = Intent(context, CallService::class.java).setAction(ACTION_HANG_UP)

        fun start(context: Context, video: Boolean = false) {
            // Android only lets a service use the microphone when the app may.
            val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (!allowed) return
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, CallService::class.java).putExtra(EXTRA_VIDEO, video))
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, CallService::class.java)) }
        }
    }
}
