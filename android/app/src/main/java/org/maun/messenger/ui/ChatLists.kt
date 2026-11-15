package org.maun.messenger.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import org.maun.messenger.Attachment
import org.maun.messenger.data.ChatSummary
import org.maun.messenger.data.ContactEntity
import org.maun.messenger.data.MessageEntity
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

/**
 * What the list shows under a name: the newest message, in one line. Who
 * wrote it is not said in words; one's own messages have their tick in front.
 */
fun previewOf(last: ChatSummary?): String = when {
    last == null -> "No messages yet"
    last.kind == MessageKind.UNREADABLE -> "A message could not be decrypted"
    last.kind == MessageKind.NOTICE -> last.text
    last.kind == MessageKind.FILE -> Attachment.fromLocal(last.text)?.let { if (it.isImage) "Photo" else it.name } ?: "File"
    else -> last.text.replace('\n', ' ')
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

// ---- One conversation --------------------------------------------------------

/** What the conversation screen lists: messages, with a line wherever the day changes. */
sealed interface ChatItem {
    /** Stays the same for as long as the item is in the list. */
    val key: String
}

/**
 * [startsRun] and [endsRun] say where a run of messages from the same person
 * on the same day begins and ends; the bubbles of one run are drawn close together.
 */
data class MessageItem(val message: MessageEntity, val startsRun: Boolean, val endsRun: Boolean) : ChatItem {
    override val key: String get() = "m${message.seq}"
}

/** Stands above the first message of [day]. [above] is that message's position in the database. */
data class DayItem(val day: LocalDate, val above: Long) : ChatItem {
    override val key: String get() = "d$above"
}

/**
 * Turns a conversation, newest message first, into what the screen lists, in
 * the same order: each message, and after the oldest message of each day (so
 * above it on screen) that day.
 *
 * Messages are kept in the order they reached this phone, so a message written
 * yesterday and delivered today stands under yesterday's date, after today's.
 */
fun chatItems(newestFirst: List<MessageEntity>, zone: ZoneId): List<ChatItem> {
    val days = newestFirst.map { dayOf(it.at, zone) }
    fun sameRun(a: Int, b: Int): Boolean {
        val one = newestFirst[a]
        val other = newestFirst[b]
        return one.outgoing == other.outgoing && days[a] == days[b] &&
            one.kind == MessageKind.TEXT && other.kind == MessageKind.TEXT
    }
    val items = ArrayList<ChatItem>(newestFirst.size + 8)
    for (i in newestFirst.indices) {
        val hasOlder = i + 1 < newestFirst.size
        items += MessageItem(
            message = newestFirst[i],
            startsRun = !hasOlder || !sameRun(i, i + 1),
            endsRun = i == 0 || !sameRun(i, i - 1),
        )
        if (!hasOlder || days[i + 1] != days[i]) items += DayItem(days[i], newestFirst[i].seq)
    }
    return items
}

fun dayOf(atMillis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(atMillis).atZone(zone).toLocalDate()

/** "Today", "Yesterday", or the date the way the phone's language writes it. */
fun dayLabel(day: LocalDate, today: LocalDate, locale: Locale = Locale.getDefault()): String = when (day) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(day)
}

// ---- People --------------------------------------------------------------------

/** The letter in the circle next to a name. People without a name get "?". */
fun initialOf(name: String?): String {
    val trimmed = name?.trim().orEmpty()
    if (trimmed.isEmpty()) return "?"
    // One whole character, even where that takes two UTF-16 units.
    val end = trimmed.offsetByCodePoints(0, 1)
    return trimmed.substring(0, end).uppercase()
}

/** Which of [count] colours stands for [id]. The same ID always gives the same answer. */
fun avatarIndex(id: String, count: Int): Int = Math.floorMod(id.hashCode(), count)
