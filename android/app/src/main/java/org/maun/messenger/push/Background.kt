package org.maun.messenger.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import androidx.core.content.edit
import org.maun.messenger.MaunApp
import org.unifiedpush.android.connector.UnifiedPush

/**
 * Something on this phone that can wake Maun: a push app, or the push service
 * of Google Play ([google]).
 */
data class PushApp(val packageName: String, val label: String, val google: Boolean = false)

/**
 * What can wake Maun on this phone (UnifiedPush "distributors") and which of
 * them does.
 *
 * Google's push service is the one WhatsApp and most other apps use. It needs
 * nothing installed and nothing of Maun running. It appears in these lists
 * under Maun's own package name, because the part that talks to it is built
 * into Maun. It is used when no push app is installed and the user has not
 * turned it off.
 */
object PushApps {

    /** What the user can choose from. Leaves Google's service out once the user has turned it off. */
    fun installed(context: Context): List<PushApp> =
        try {
            val googleOff = Background.googleOff(context)
            UnifiedPush.getDistributors(context)
                .filter { !(googleOff && it == context.packageName) }
                .map { describe(context, it) }
        } catch (_: Exception) {
            emptyList()
        }

    /** Whether this phone has Google's push service, turned off or not. */
    fun googleAvailable(context: Context): Boolean =
        try {
            context.packageName in UnifiedPush.getDistributors(context)
        } catch (_: Exception) {
            false
        }

    /** The push app that wakes Maun, or null. */
    fun current(context: Context): PushApp? =
        try {
            UnifiedPush.getSavedDistributor(context)?.let { describe(context, it) }
        } catch (_: Exception) {
            null
        }

    /**
     * Registers with what wakes Maun, as UnifiedPush asks apps to do every
     * time they start. If nothing was chosen yet, a push app is used when
     * exactly one is installed, and Google's service when there is no push
     * app. With several push apps the user is asked. Returns whether
     * something is in use; its answer arrives later, at [WakeService].
     */
    fun refresh(context: Context): Boolean =
        try {
            var chosen = UnifiedPush.getSavedDistributor(context)
            if (chosen == null) {
                val all = installed(context)
                val apps = all.filter { !it.google }
                chosen = (apps.singleOrNull() ?: all.singleOrNull())?.packageName
                if (chosen != null) UnifiedPush.saveDistributor(context, chosen)
            }
            if (chosen != null) register(context)
            chosen != null
        } catch (_: Exception) {
            false
        }

    /** The user does not want Google's push service to wake Maun. */
    fun stopGoogle(context: Context) {
        Background.setGoogleOff(context, true)
        try {
            // Gives the address back and forgets the choice.
            if (UnifiedPush.getSavedDistributor(context) == context.packageName) UnifiedPush.removeDistributor(context)
        } catch (_: Exception) {
        }
    }

    /** The user wants Google's push service to wake Maun after all. */
    fun useGoogle(context: Context): Boolean {
        Background.setGoogleOff(context, false)
        return choose(context, context.packageName)
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
            val chosen = UnifiedPush.getSavedDistributor(context) ?: return
            // Unregistering the only address also forgets which push service
            // was chosen, so that is put back before asking for a new one.
            UnifiedPush.unregister(context, INSTANCE)
            UnifiedPush.saveDistributor(context, chosen)
            register(context)
        } catch (_: Exception) {
        }
    }

    // The relay's key goes along once it is known. Some push services hand
    // out an address only to an app that names who will be calling. Google's
    // is one of them, so asking it before the key is known would only produce
    // an error; [Background.onRelayKey] asks again when the key arrives.
    private fun register(context: Context) {
        val key = Background.relayKey(context)
        if (key == null && UnifiedPush.getSavedDistributor(context) == context.packageName) return
        UnifiedPush.register(context, INSTANCE, null, key)
    }

    private const val INSTANCE = "default"

    private fun describe(context: Context, packageName: String): PushApp =
        if (packageName == context.packageName) {
            PushApp(packageName, GOOGLE, google = true)
        } else {
            PushApp(packageName, labelOf(context, packageName))
        }

    private const val GOOGLE = "Google's push service"

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
    private const val LEGACY_STAY_CONNECTED = "stay_connected"
    private const val KEY_STAY_CONNECTED = "stay_connected_v2"
    private const val KEY_RELAY_KEY = "relay_push_key"
    private const val KEY_GOOGLE_OFF = "google_push_off"

    /** The user turned Google's push service off for Maun. */
    fun googleOff(context: Context): Boolean =
        context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).getBoolean(KEY_GOOGLE_OFF, false)

    fun setGoogleOff(context: Context, off: Boolean) {
        context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).edit { putBoolean(KEY_GOOGLE_OFF, off) }
    }

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
        context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).edit {
            remove(LEGACY_STAY_CONNECTED)
            putBoolean(KEY_STAY_CONNECTED, on)
        }
        if (on) ConnectedService.start(context) else ConnectedService.stop(context)
    }

    /**
     * Clears any legacy pre-0.14 "stay_connected" setting on upgrade and stops
     * any running foreground connection unless the user explicitly enabled it.
     */
    fun enforceDefaultOffOnUpgrade(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
        if (prefs.contains(LEGACY_STAY_CONNECTED)) {
            prefs.edit { remove(LEGACY_STAY_CONNECTED) }
        }
        if (!stayConnected(app)) {
            ConnectedService.stop(app)
        }
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
        enforceDefaultOffOnUpgrade(app)
        // Discovery can fail while the distributor or Android is starting. Only an explicit
        // unregister or user opt-out withdraws an established subscription.
        PushApps.refresh(app)
        if (stayConnected(app)) ConnectedService.start(app) else ConnectedService.stop(app)
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
