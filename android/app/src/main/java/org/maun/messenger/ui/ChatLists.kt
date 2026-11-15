package org.maun.messenger.ui

import org.maun.messenger.data.ChatSummary
import org.maun.messenger.data.ContactEntity
import org.maun.messenger.data.MessageKind
import org.maun.messenger.data.MessageStatus

/** One line of the home screen: a person, and the newest message with them if there is one. */
data class ChatRow(
    val peer: String,
    /** Null for someone who is not a contact (a message request). */
    val contact: ContactEntity?,
    val last: ChatSummary?,
)

data class HomeLists(
    /** Contacts: those with messages first, most recent on top, then the rest by name. */
    val chats: List<ChatRow> = emptyList(),
    /** People who wrote without being a contact. */
    val requests: List<ChatRow> = emptyList(),
)

/** Sorts contacts and conversations into what the home screen shows. */
fun homeLists(contacts: List<ContactEntity>, summaries: List<ChatSummary>): HomeLists {
    val byId = contacts.associateBy { it.id }
    val lastByPeer = summaries.associateBy { it.peer }
    val active = summaries.filter { it.peer in byId }.map { ChatRow(it.peer, byId.getValue(it.peer), it) }
    // `contacts` arrives sorted by name.
    val quiet = contacts.filter { it.id !in lastByPeer }.map { ChatRow(it.id, it, null) }
    val requests = summaries.filter { it.peer !in byId }.map { ChatRow(it.peer, null, it) }
    return HomeLists(chats = active + quiet, requests = requests)
}

/** What the list shows under a name: the newest message, in one line. */
fun previewOf(last: ChatSummary?): String = when {
    last == null -> "No messages yet"
    last.kind == MessageKind.UNREADABLE -> "A message could not be decrypted"
    last.outgoing -> "You: ${last.text}"
    else -> last.text
}

/** Sent, Delivered and Read, in words: the three states the product promises. */
fun statusLabel(status: Int, online: Boolean, stuck: Boolean): String = when {
    status >= MessageStatus.READ -> "Read"
    status == MessageStatus.DELIVERED -> "Delivered"
    status == MessageStatus.SENT -> "Sent"
    stuck -> "Waiting"
    online -> "Sending…"
    else -> "Waiting for connection"
}
