package org.maun.messenger.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import androidx.core.content.edit
import org.maun.messenger.MaunApp
import org.unifiedpush.android.connector.UnifiedPush

/** A push app installed on this phone that can wake Maun. */
data class PushApp(val packageName: String, val label: String)

/**
 * The push apps on this phone (UnifiedPush "distributors") and which of them
 * wakes Maun.
 */
object PushApps {

    fun installed(context: Context): List<PushApp> =
        try {
            UnifiedPush.getDistributors(context).map { PushApp(it, labelOf(context, it)) }
        } catch (_: Exception) {
            emptyList()
        }

    /** The push app that wakes Maun, or null. */
    fun current(context: Context): PushApp? =
        try {
            UnifiedPush.getSavedDistributor(context)?.let { PushApp(it, labelOf(context, it)) }
        } catch (_: Exception) {
            null
        }

    /**
     * Registers with the push app in use, as UnifiedPush asks apps to do every
     * time they start. If none was chosen and exactly one is installed, that
     * one is used. Returns whether a push app is in use; its answer arrives
     * later, at [WakeService].
     */
    fun refresh(context: Context): Boolean =
        try {
            var chosen = UnifiedPush.getSavedDistributor(context)
            if (chosen == null) {
                chosen = UnifiedPush.getDistributors(context).singleOrNull()
                if (chosen != null) UnifiedPush.saveDistributor(context, chosen)
            }
            if (chosen != null) register(context)
            chosen != null
        } catch (_: Exception) {
            false
        }

    /** The user picked [packageName] out of several. */
    fun choose(context: Context, packageName: String): Boolean =
        try {
            UnifiedPush.saveDistributor(context, packageName)
            register(context)
            true
        } catch (_: Exception) {
            false
        }

    /**
     * The relay's signing key has changed, which happens when this phone gets
     * a new ID. An address given out for the old key is of no use any more.
     */
    fun startOver(context: Context) {
        try {
            if (UnifiedPush.getSavedDistributor(context) == null) return
            UnifiedPush.unregister(context, INSTANCE)
            register(context)
        } catch (_: Exception) {
        }
    }

    // The relay's key goes along once it is known. Some push services hand
    // out an address only to an app that names who will be calling.
    private fun register(context: Context) {
        UnifiedPush.register(context, INSTANCE, null, Background.relayKey(context))
    }

    private const val INSTANCE = "default"

    private fun labelOf(context: Context, packageName: String): String =
        try {
            val packages = context.packageManager
            packages.getApplicationLabel(packages.getApplicationInfo(packageName, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }
}

/** How Maun gets messages while it is not on screen, and the user's choices about it. */
object Background {
    private const val SETTINGS = "settings"
    private const val KEY_STAY_CONNECTED = "stay_connected"
    private const val KEY_RELAY_KEY = "relay_push_key"

    /** The key the relay signs wake-ups with, as push apps take it. Null until the first login tells it. */
    fun relayKey(context: Context): String? =
        context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).getString(KEY_RELAY_KEY, null)

    /**
     * The relay said which key it signs this phone's wake-ups with. The push
     * app is told, again from scratch if it knew a different one.
     */
    fun onRelayKey(context: Context, key: String) {
        val known = relayKey(context)
        if (known == key) return
        context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).edit { putString(KEY_RELAY_KEY, key) }
        if (known == null) PushApps.refresh(context) else PushApps.startOver(context)
    }

    fun stayConnected(context: Context): Boolean =
        context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).getBoolean(KEY_STAY_CONNECTED, false)

    fun setStayConnected(context: Context, on: Boolean) {
        context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).edit { putBoolean(KEY_STAY_CONNECTED, on) }
        if (on) ConnectedService.start(context) else ConnectedService.stop(context)
    }

    /** Whether Android has been told not to pause Maun while the phone rests. */
    fun unrestricted(context: Context): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Puts background delivery back in place. Called when the app comes on
     * screen, when the phone has restarted, and after an update.
     */
    fun resume(context: Context) {
        val app = context.applicationContext as MaunApp
        if (!PushApps.refresh(app)) app.messenger.onPushAddress(null)
        if (stayConnected(app)) ConnectedService.start(app)
    }
}

/** Runs after the phone has restarted or Maun was updated, when nothing of Maun is running. */
class StartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Background.resume(context)
        // Whatever arrived while the phone was off.
        FetchWorker.schedule(context)
    }
}
