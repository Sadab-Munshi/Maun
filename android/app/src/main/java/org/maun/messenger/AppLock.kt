package org.maun.messenger

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

/**
 * Two things the user can switch on to keep other people who hold the phone
 * out of Maun: a lock that asks for the fingerprint, face or screen lock each
 * time the app comes on screen, and keeping the app out of screenshots.
 *
 * The lock is a door, not a second layer of encryption: the messages are
 * encrypted on disk either way, with a key only this phone's secure hardware
 * holds. Android itself checks the fingerprint or the screen lock; Maun only
 * learns whether it matched.
 */
object AppLock {
    private const val SETTINGS = "settings"
    private const val KEY_LOCK = "app_lock"
    private const val KEY_SCREENSHOTS = "block_screenshots"

    /** True while the lock screen stands in front of everything else. */
    var locked by mutableStateOf(false)
        private set

    /** True while Android is asking for the fingerprint or screen lock. */
    var asking = false
        private set

    /** Whether this process has decided, once, if it starts out locked. */
    private var started = false

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_LOCK, false)

    fun blocksScreenshots(context: Context): Boolean = prefs(context).getBoolean(KEY_SCREENSHOTS, false)

    /** The lock rests on the phone's own screen lock. Without one there is nothing to ask for. */
    fun available(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    /**
     * Someone who removed the phone's screen lock after turning this on is
     * let in rather than locked out for good.
     */
    private fun wanted(context: Context): Boolean = enabled(context) && available(context)

    /** The app's window was created: lock if this is a fresh start, and set up the window. */
    fun onCreate(activity: Activity) {
        if (!started) {
            started = true
            locked = wanted(activity)
        }
        applyTo(activity)
    }

    /** The app left the screen. Turning the phone around does not count. */
    fun onStop(activity: Activity) {
        if (!activity.isChangingConfigurations) locked = wanted(activity)
    }

    /** Asks for the fingerprint or screen lock, and opens the app if it matches. */
    fun unlock(activity: Activity) {
        if (!locked) return
        if (!wanted(activity)) {
            locked = false
            return
        }
        ask(activity, "Unlock Maun") { matched -> if (matched) locked = false }
    }

    /**
     * Turns the lock on or off. Either way the person has to prove it is
     * their phone first. [onDone] is called when the question is settled.
     */
    fun setEnabled(activity: Activity, on: Boolean, onDone: () -> Unit) {
        if (on && !available(activity)) {
            onDone()
            return
        }
        ask(activity, if (on) "Turn on app lock" else "Turn off app lock") { matched ->
            if (matched) {
                prefs(activity).edit { putBoolean(KEY_LOCK, on) }
                locked = false
                applyTo(activity)
            }
            onDone()
        }
    }

    fun setBlocksScreenshots(activity: Activity, on: Boolean) {
        prefs(activity).edit { putBoolean(KEY_SCREENSHOTS, on) }
        applyTo(activity)
    }

    /**
     * Tells Android how much of Maun's window may be captured. With the lock
     * on, the picture in the list of recent apps is hidden, since it would
     * show the conversation that was open. Before Android 13 that can only be
     * done by forbidding screenshots altogether.
     */
    private fun applyTo(activity: Activity) {
        val lock = enabled(activity)
        val recentsCanBeHidden = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        if (blocksScreenshots(activity) || (lock && !recentsCanBeHidden)) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.setRecentsScreenshotEnabled(!lock)
        }
    }

    /** True where turning the lock on also forbids screenshots. */
    val lockBlocksScreenshots: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU

    private fun ask(activity: Activity, title: String, onResult: (Boolean) -> Unit) {
        if (asking) return
        asking = true
        val settle = { matched: Boolean ->
            asking = false
            onResult(matched)
        }
        try {
            val builder = BiometricPrompt.Builder(activity).setTitle(title)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                )
            } else {
                // Android 10 has only this way of offering the PIN or pattern as well.
                @Suppress("DEPRECATION")
                builder.setDeviceCredentialAllowed(true)
            }
            builder.build().authenticate(
                CancellationSignal(),
                activity.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = settle(true)

                    // Cancelled, timed out, or too many wrong attempts. A single
                    // wrong finger does not end up here; the question stays open.
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = settle(false)
                },
            )
        } catch (_: RuntimeException) {
            // A phone whose biometric service is broken or missing.
            settle(false)
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
}
