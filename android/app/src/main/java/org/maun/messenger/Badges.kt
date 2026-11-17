package org.maun.messenger

import org.json.JSONObject

/**
 * Which accounts carry the tick that Maun's administrators give out.
 *
 * The relay is the only place this is decided and the only place it is stored;
 * this phone keeps the last answer it was given so that a chat list opened
 * without a connection shows the same ticks it showed when it was last online.
 * A tick that has been taken away disappears as soon as the relay says so.
 */
object Badges {
    /** Where the answers are kept, next to the rest of this phone's state. */
    const val KEY = "verified_badges"

    /** How many IDs one lookup may carry; the relay refuses more. */
    const val LOOKUP_MAX = 64

    /** Reads what was stored. Anything unreadable is treated as nothing known. */
    fun parse(stored: String?): Map<String, Boolean> {
        if (stored.isNullOrBlank()) return emptyMap()
        val json = try {
            JSONObject(stored)
        } catch (_: Exception) {
            return emptyMap()
        }
        val found = HashMap<String, Boolean>()
        for (id in json.keys()) {
            if (json.optBoolean(id, false)) found[id] = true
        }
        return found
    }

    /** Writes the answers out, keeping only the accounts that have the tick. */
    fun encode(badges: Map<String, Boolean>): String {
        val json = JSONObject()
        for ((id, verified) in badges) {
            if (verified) json.put(id, true)
        }
        return json.toString()
    }

    /**
     * Folds a fresh answer for [checked] into what was known, dropping anyone
     * who is no longer a contact and anyone whose tick has been taken away.
     */
    fun merge(
        known: Map<String, Boolean>,
        checked: Map<String, Boolean>,
        stillContacts: Collection<String>,
    ): Map<String, Boolean> {
        val kept = HashMap<String, Boolean>()
        for (id in stillContacts) {
            if (checked[id] == true || (id !in checked && known[id] == true)) kept[id] = true
        }
        return kept
    }
}
