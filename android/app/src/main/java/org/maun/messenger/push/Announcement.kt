package org.maun.messenger.push

import org.maun.messenger.data.ChatSummary

/**
 * What a notification about unread messages says. [title] and [text] may name
 * a contact and are for the unlocked phone; [discreet] names nobody and is
 * what a locked screen shows. None of them ever contains a message.
 */
data class Announcement(val title: String, val text: String, val discreet: String, val total: Int)

/**
 * Describes the unread messages in [chats], or returns null when there are
 * none. [names] maps a contact's ID to the name the user gave them; anyone
 * else is a stranger whose message is a request.
 */
fun announce(chats: List<ChatSummary>, names: Map<String, String>): Announcement? {
    val unread = chats.filter { it.unread > 0 }
    val total = unread.sumOf { it.unread }
    if (total == 0) return null
    val discreet = count(total)
    val only = unread.singleOrNull()
    return when {
        only == null -> Announcement("Maun", "$total new messages in ${unread.size} chats", discreet, total)
        only.peer in names -> Announcement(names.getValue(only.peer), count(total), discreet, total)
        else -> Announcement("Maun", if (total == 1) "New message request" else "$total messages from someone new", discreet, total)
    }
}

private fun count(total: Int): String = if (total == 1) "New message" else "$total new messages"

/**
 * Notices when a message has arrived that was not there before, by watching
 * how many unread messages each conversation has.
 */
class UnreadTracker {
    private var known: Map<String, Int>? = null

    /**
     * Takes the current state of the conversations and says whether any of
     * them has more unread messages than last time. The first call only notes
     * where things stand: what was unread before the app started is not news.
     */
    @Synchronized
    fun hasNew(chats: List<ChatSummary>): Boolean {
        val now = chats.associate { it.peer to it.unread }
        val before = known
        known = now
        if (before == null) return false
        return now.any { (peer, unread) -> unread > (before[peer] ?: 0) }
    }
}
