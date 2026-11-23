package org.maun.messenger.updates

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log

/** Receives Android's result for Maun's self-update install session. */
internal const val UPDATE_INSTALL_PREFS = "update_install"
internal const val UPDATE_INSTALL_ERROR = "last_error"

class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val preferences = context.getSharedPreferences(UPDATE_INSTALL_PREFS, Context.MODE_PRIVATE)
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> preferences.edit().remove(UPDATE_INSTALL_ERROR).apply()
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = userAction(intent)
                if (confirmation == null) {
                    fail(preferences)
                } else {
                    try {
                        confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(confirmation)
                    } catch (error: Exception) {
                        Log.w(TAG, "Android requested confirmation but it could not be opened", error)
                        fail(preferences)
                    }
                }
            }
            else -> {
                Log.w(TAG, "Android could not install Maun update; status=$status")
                fail(preferences)
            }
        }
    }

    private fun userAction(intent: Intent): Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
    } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(Intent.EXTRA_INTENT)
    }

    private fun fail(preferences: android.content.SharedPreferences) {
        preferences.edit()
            .putString(UPDATE_INSTALL_ERROR, "Android could not finish installing the update. Try again from Settings.")
            .apply()
    }

    companion object {
        const val ACTION = "org.maun.messenger.action.UPDATE_INSTALL_STATUS"
        private const val TAG = "MaunUpdate"
    }
}
