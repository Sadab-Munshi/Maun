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
            if (showText) {
                Announcement(if (total == 1) name else "$name ($total new)", previewOf(only), discreet, total, peer = only.peer)
            } else {
                Announcement(name, count(total), discreet, total, peer = only.peer)
            }
        }
        else -> Announcement(
            "Maun",
            if (total == 1) "New message request" else "$total messages from someone new",
            discreet,
            total,
            peer = only.peer,
        )
    }
}

private const val MAX_LINES = 6

private fun count(total: Int): String = if (total == 1) "New message" else "$total new messages"

/** A new unread row can replace an older one without changing the unread count. */
internal fun newestUnread(chats: List<ChatSummary>): Long = chats.maxOfOrNull { it.newestUnread } ?: 0L
