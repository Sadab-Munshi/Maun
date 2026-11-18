package org.maun.messenger.push

import org.maun.messenger.data.ChatSummary
import org.maun.messenger.ui.previewOf

/**
 * What a notification about unread messages says. [title], [text] and [lines]
 * are for the unlocked phone and may name contacts and, where the user has
 * not turned that off, quote the newest message. [discreet] names nobody,
 * quotes nothing, and is what a locked screen shows. [peer] is the one
 * conversation it is about, to open when it is tapped; null when there are
 * several.
 */
data class Announcement(
    val title: String,
    val text: String,
    val discreet: String,
    val total: Int,
    val lines: List<String> = emptyList(),
    val peer: String? = null,
    /** The newest message of that one conversation, to open at it. */
    val message: String? = null,
)

/**
 * Describes the unread messages in [chats], or returns null when there are
 * none. [names] maps a contact's ID to the name the user gave them; anyone
 * else is a stranger whose message is a request, and is never quoted.
 * [showText] puts the newest message of each conversation into the
 * notification, the way messengers usually do.
 */
fun announce(chats: List<ChatSummary>, names: Map<String, String>, showText: Boolean = false): Announcement? {
    val unread = chats.filter { it.unread > 0 }
    val total = unread.sumOf { it.unread }
    if (total == 0) return null
    val discreet = count(total)
    val only = unread.singleOrNull()
    return when {
        only == null -> Announcement(
            title = "Maun",
            text = "$total new messages in ${unread.size} chats",
            discreet = discreet,
            total = total,
            lines = unread.take(MAX_LINES).map { chat ->
                val name = names[chat.peer]
                when {
                    name == null -> "Someone new: ${count(chat.unread).lowercase()}"
                    showText -> "$name: ${previewOf(chat)}"
                    else -> "$name: ${count(chat.unread).lowercase()}"
                }
            },
        )
        only.peer in names -> {
            val name = names.getValue(only.peer)
            val message = only.id.takeIf { it.isNotEmpty() }
            if (showText) {
                Announcement(
                    if (total == 1) name else "$name ($total new)",
                    previewOf(only),
                    discreet,
                    total,
                    peer = only.peer,
                    message = message,
                )
            } else {
                Announcement(name, count(total), discreet, total, peer = only.peer, message = message)
            }
        }
        else -> Announcement(
            "Maun",
            if (total == 1) "New message request" else "$total messages from someone new",
            discreet,
            total,
            peer = only.peer,
            message = only.id.takeIf { it.isNotEmpty() },
        )
    }
}

private const val MAX_LINES = 6

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
