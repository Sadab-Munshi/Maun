package org.maun.messenger.push

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maun.messenger.LivePoint
import org.maun.messenger.MaunApp

/**
 * Runs only while this phone is sharing where it is, and only for the quarter
 * of an hour or the hour that was picked.
 *
 * Two things have to be true at once for a fix to be handed over: this service
 * is in the foreground with a notification the user can see and stop, and the
 * share has not run out. Nothing of it survives its own minute: when the time
 * is up the service stops itself, the notification goes, and the card in the
 * conversation says the share has ended.
 */
class LocationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private var ticking: Job? = null

    private var peer: String = ""
    private var share: String = ""
    private var key: String = ""
    private var until: Long = 0L
    private var who: String = ""

    /** The last fix handed over, so that a phone sitting still sends nothing. */
    private var lastSentAt = 0L
    private var lastSent: LivePoint? = null

    private val listener = LocationListener { location -> onFix(location) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Started again by Android after it was killed: the share itself is on
        // the phone's storage, so ask for it rather than guessing from an
        // intent that is not there.
        if (intent == null) {
            messenger().resumeLiveShares()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent.action == ACTION_STOP) {
            // Tapped "Stop" in the shade: say so everywhere, then go.
            endShare(intent.getStringExtra(EXTRA_SHARE) ?: share)
            return START_NOT_STICKY
        }
        peer = intent?.getStringExtra(EXTRA_PEER) ?: return giveUp()
        share = intent.getStringExtra(EXTRA_SHARE) ?: return giveUp()
        key = intent.getStringExtra(EXTRA_KEY) ?: return giveUp()
        until = intent.getLongExtra(EXTRA_UNTIL, 0L)
        who = intent.getStringExtra(EXTRA_WHO) ?: "They"
        if (until <= System.currentTimeMillis()) return giveUp()

        // Asked before the service takes the notification: from Android 14 on,
        // a service of the location kind may not run without the permission.
        if (!mayUseLocation()) return giveUp()
        val notification = Notifications(this).sharingLocation(who, left(), stopIntent(this, share, peer))
        try {
            startForeground(Notifications.ID_LIVE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (_: Exception) {
            // Android would not let it run. Better to stop than to share silently.
            return giveUp()
        }

        listen()
        ticking?.cancel()
        ticking = scope.launch {
            while (System.currentTimeMillis() < until) {
                delay(TICK_MS)
                if (System.currentTimeMillis() >= until) break
                Notifications(this@LocationService).sharingLocation(who, left(), stopIntent(this@LocationService, share, peer))
                    .let { refresh(it) }
            }
            endShare(share)
        }
        // A safety share is worth being brought back after Android has put the
        // app away; the time it was promised for is what ends it, not a kill.
        return START_STICKY
    }

    override fun onDestroy() {
        ticking?.cancel()
        handler.removeCallbacksAndMessages(null)
        stopListening()
        super.onDestroy()
    }

    /**
     * Asks for fixes from every provider this phone has, and takes the newest
     * one at once. The permission is checked in [mayUseLocation] before the
     * service takes its notification, and again here for the provider calls;
     * lint does not follow either.
     */
    @SuppressLint("MissingPermission")
    private fun listen() {
        val manager = getSystemService(LocationManager::class.java) ?: return
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (!manager.isProviderEnabled(provider)) continue
            try {
                manager.requestLocationUpdates(provider, INTERVAL_MS, MIN_METRES, listener)
            } catch (_: IllegalArgumentException) {
                // No such provider on this phone.
            } catch (_: SecurityException) {
                // The permission went away between the check and the asking.
                giveUp()
                return
            }
            // A phone that just woke may already know where it is.
            val known = try {
                manager.getLastKnownLocation(provider)
            } catch (_: SecurityException) {
                null
            }
            if (known != null && lastSent == null) onFix(known)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopListening() {
        val manager = getSystemService(LocationManager::class.java) ?: return
        try {
            manager.removeUpdates(listener)
        } catch (_: SecurityException) {
            // Nothing was asked for, or the permission went away with the share.
        }
    }

    /**
     * One fix. It is handed over at most every [SEND_EVERY_MS], and only if
     * the phone has moved or the last one is a while old, so that a phone on a
     * table says nothing new rather than the same thing twice.
     */
    private fun onFix(location: Location) {
        val point = LivePoint(location.latitude, location.longitude, location.accuracy.toDouble(), System.currentTimeMillis())
        val previous = lastSent
        val moved = previous == null || distance(previous, point) >= MIN_MOVE_METRES
        val now = System.currentTimeMillis()
        if (!moved && now - lastSentAt < HEARTBEAT_MS) return
        if (now - lastSentAt < SEND_EVERY_MS && previous != null) return
        lastSent = point
        lastSentAt = now
        scope.launch {
            val sent = messenger().liveFix(share, key, point)
            // The relay says the share is over: so is the service.
            if (!sent) handler.post { endShare(share) }
        }
    }

    private fun distance(from: LivePoint, to: LivePoint): Double {
        val result = FloatArray(1)
        Location.distanceBetween(from.lat, from.lon, to.lat, to.lon, result)
        return result[0].toDouble()
    }

    // The permission is checked on the line above the call; lint does not follow that.
    @SuppressLint("MissingPermission")
    private fun refresh(notification: android.app.Notification) {
        val manager = getSystemService(android.app.NotificationManager::class.java) ?: return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        manager.notify(Notifications.ID_LIVE, notification)
    }

    /** "14 min", "1 min", "under a minute": what the notification says is left. */
    private fun left(): String {
        val millis = until - System.currentTimeMillis()
        val minutes = (millis + 59_999L) / 60_000L
        return when {
            minutes <= 0L -> "Under a minute"
            minutes == 1L -> "1 minute"
            else -> "$minutes minutes"
        }
    }

    private fun mayUseLocation(): Boolean = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    /**
     * The share is over, whoever ended it. Told once: the relay, the other
     * phone, the notification and the card all learn it from the same place.
     */
    private fun endShare(which: String) {
        stopListening()
        ticking?.cancel()
        Notifications(this).clearSharingLocation()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (which.isNotEmpty() && peer.isNotEmpty()) messenger().finishLiveShare(peer, which)
        stopSelf()
    }

    private fun giveUp(): Int {
        Notifications(this).clearSharingLocation()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (share.isNotEmpty() && peer.isNotEmpty()) messenger().finishLiveShare(peer, share)
        stopSelf()
        return START_NOT_STICKY
    }

    private fun messenger() = (application as MaunApp).messenger

    companion object {
        private const val ACTION_START = "org.maun.messenger.LIVE_START"
        const val ACTION_STOP = "org.maun.messenger.LIVE_STOP"
        private const val EXTRA_PEER = "peer"
        private const val EXTRA_SHARE = "share"
        private const val EXTRA_KEY = "key"
        private const val EXTRA_UNTIL = "until"
        private const val EXTRA_WHO = "who"

        /** How often a phone with a fix is asked about. */
        private const val INTERVAL_MS = 10_000L

        /** Closer than this, and it is not worth waking the radio for. */
        private const val MIN_METRES = 10f

        /** Two points are at least this far apart in time, well inside what the relay asks. */
        private const val SEND_EVERY_MS = 15_000L

        /** Sitting still, a phone still says where it is this often. */
        private const val HEARTBEAT_MS = 4 * 60_000L

        /** Moved this far, and it is worth a new point even before [SEND_EVERY_MS]. */
        private const val MIN_MOVE_METRES = 10.0

        private const val TICK_MS = 15_000L

        fun stopIntent(context: Context, share: String, peer: String): Intent =
            Intent(context, LocationService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_SHARE, share)

        /** Starts sharing [share] with [peer] until [until]. Does nothing without the permission. */
        fun start(context: Context, peer: String, share: String, key: String, until: Long, who: String) {
            val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!fine && !coarse) return
            val intent = Intent(context, LocationService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PEER, peer)
                .putExtra(EXTRA_SHARE, share)
                .putExtra(EXTRA_KEY, key)
                .putExtra(EXTRA_UNTIL, until)
                .putExtra(EXTRA_WHO, who)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Exception) {
                // Android would not let a service start from here. The card
                // still says what the share is; it stops when its time is up.
            }
        }

        /** Ends the service for [share] without telling anybody: the caller does that. */
        fun stop(context: Context, share: String) {
            context.stopService(Intent(context, LocationService::class.java).putExtra(EXTRA_SHARE, share))
        }
    }
}
