package org.maun.messenger.push

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import java.util.UUID

/**
 * How long a message takes to get from the relay to the phone's screen, kept
 * as a handful of timestamps and nothing else.
 *
 * A wake-up begins one of these: an event with a random eight-character name,
 * which every step of the way then mentions, so that the lines belonging to one
 * message can be put together in `adb logcat -s MaunDelivery` — or in a bug
 * report — without anything about the message, its sender, or the phone being
 * written down. The steps are: the wake-up arriving, the fetch that followed it
 * starting and finishing, and the notification being shown.
 *
 * It is a diagnosis aid, not a record: only the newest event is kept, and only
 * its name and start.
 */
object DeliveryTrace {
    private const val TAG = "MaunDelivery"
    private const val FILE = "settings"
    private const val KEY_EVENT = "delivery_event"
    private const val KEY_AT = "delivery_event_at"

    /** The names of the points along the way. They say what happened, and nothing about what. */
    const val WAKE = "wake"
    const val CALL = "call"
    const val FETCH_START = "fetch-start"
    const val FETCH_DONE = "fetch-done"
    const val FETCH_AGAIN = "fetch-again"

    /** The tries are over and something is still waiting: one fetch is left for when there is a network. */
    const val FETCH_WAITING = "fetch-waiting-for-network"
    const val SHOWN = "shown"

    /**
     * A wake-up has arrived, and it was for [kind] ([WAKE] for a message,
     * [CALL] for a call that is ringing). Returns the name of the event, which
     * the steps that follow are logged under.
     */
    fun began(context: Context, kind: String): String {
        val id = UUID.randomUUID().toString().take(8)
        val now = System.currentTimeMillis()
        prefs(context).edit {
            putString(KEY_EVENT, id)
            putLong(KEY_AT, now)
        }
        Log.i(TAG, "event=$id step=$kind at=$now")
        return id
    }

    /** A point along the way, in the event that is being followed. */
    fun stepped(context: Context, step: String, detail: String = "") {
        val store = prefs(context)
        val id = store.getString(KEY_EVENT, null) ?: return
        val since = store.getLong(KEY_AT, 0L)
        val now = System.currentTimeMillis()
        val after = if (since > 0) now - since else 0
        val extra = if (detail.isEmpty()) "" else " $detail"
        Log.i(TAG, "event=$id step=$step at=$now afterWake=${after}ms$extra")
    }

    /** The newest event, for whoever is looking into a delay. Null when nothing has been woken yet. */
    fun last(context: Context): String? = prefs(context).getString(KEY_EVENT, null)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
