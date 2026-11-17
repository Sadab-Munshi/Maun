package org.maun.messenger

import android.content.Context

/**
 * Which sounds Maun makes, kept in the same small settings file as the rest of
 * its preferences, so that they are still there after the app is started again.
 *
 * Both are asked about the device as well: silent mode, Do Not Disturb and the
 * notifications switch come first, and a choice made here cannot talk over them.
 */
object SoundSettings {
    /** The sound for a message this phone sends. */
    const val SENT_MAUN = "maun"
    const val SENT_SILENT = "silent"

    /** The sound for a message that arrives. */
    const val INCOMING_MAUN = "maun"
    const val INCOMING_SYSTEM = "system"
    const val INCOMING_SILENT = "silent"

    /** What the app was set to before there was a choice. */
    const val DEFAULT_SENT = SENT_MAUN
    const val DEFAULT_INCOMING = INCOMING_MAUN

    fun sent(context: Context): String {
        val stored = prefs(context).getString(KEY_SENT, null)
        return if (stored == SENT_SILENT) SENT_SILENT else DEFAULT_SENT
    }

    fun incoming(context: Context): String = when (prefs(context).getString(KEY_INCOMING, null)) {
        INCOMING_SYSTEM -> INCOMING_SYSTEM
        INCOMING_SILENT -> INCOMING_SILENT
        else -> DEFAULT_INCOMING
    }

    fun setSent(context: Context, choice: String) {
        prefs(context).edit().putString(KEY_SENT, if (choice == SENT_SILENT) SENT_SILENT else SENT_MAUN).apply()
    }

    fun setIncoming(context: Context, choice: String) {
        val store = when (choice) {
            INCOMING_SYSTEM -> INCOMING_SYSTEM
            INCOMING_SILENT -> INCOMING_SILENT
            else -> INCOMING_MAUN
        }
        prefs(context).edit().putString(KEY_INCOMING, store).apply()
    }

    /** The notification channel that carries the incoming sound this account chose. */
    fun incomingChannel(context: Context): String = when (incoming(context)) {
        INCOMING_SYSTEM -> Channel.SYSTEM
        INCOMING_SILENT -> Channel.SILENT
        else -> Channel.MAUN
    }

    /** The three channels a message notification can arrive on, one for each choice. */
    enum class Channel(val id: String) {
        MAUN("messages_maun"),
        SYSTEM("messages_system"),
        SILENT("messages_silent"),
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private const val FILE = "settings"
    private const val KEY_SENT = "sound_sent"
    private const val KEY_INCOMING = "sound_incoming"
}
