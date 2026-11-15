package org.maun.messenger.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertTrue
import org.maun.messenger.data.ChatSummary
import org.maun.messenger.data.ContactEntity
import org.maun.messenger.data.MessageEntity
import org.maun.messenger.data.MessageKind
import org.maun.messenger.data.MessageStatus

class ChatListsTest {
    private fun contact(id: String, name: String) = ContactEntity(id, name, verified = true, addedAt = 0)

    private fun summary(peer: String, seq: Long, text: String = "hi", outgoing: Boolean = false, kind: Int = MessageKind.TEXT) =
        ChatSummary(peer = peer, seq = seq, kind = kind, text = text, outgoing = outgoing, status = 0, at = seq, unread = 0)

    @Test
    fun `contacts with messages come first, newest on top, then the quiet ones`() {
        // Contacts arrive sorted by name, conversations newest first.
        val contacts = listOf(contact("a", "Asha"), contact("b", "Bala"), contact("c", "Chitra"))
        val summaries = listOf(summary("c", 9), summary("b", 4))

        val lists = homeLists(contacts, summaries)

        assertEquals(listOf("c", "b", "a"), lists.chats.map { it.peer })
        assertEquals(listOf("Chitra", "Bala", "Asha"), lists.chats.map { it.contact?.name })
        assertEquals(9L, lists.chats[0].last?.seq)
        assertNull(lists.chats[2].last)
        assertEquals(emptyList<ChatRow>(), lists.requests)
    }

    @Test
    fun `messages from someone who is not a contact are requests, not chats`() {
        val lists = homeLists(listOf(contact("a", "Asha")), listOf(summary("x", 5), summary("a", 3)))

        assertEquals(listOf("a"), lists.chats.map { it.peer })
        assertEquals(listOf("x"), lists.requests.map { it.peer })
        assertNull(lists.requests[0].contact)
    }

    @Test
    fun `nothing at all gives two empty lists`() {
        assertEquals(HomeLists(), homeLists(emptyList(), emptyList()))
    }

    @Test
    fun `the preview is one line of the newest message, and hides what could not be read`() {
        assertEquals("No messages yet", previewOf(null))
        assertEquals("hello", previewOf(summary("a", 1, text = "hello")))
        assertEquals("hello", previewOf(summary("a", 1, text = "hello", outgoing = true)))
        assertEquals("one two", previewOf(summary("a", 1, text = "one\ntwo")))
        assertEquals("A message could not be decrypted", previewOf(summary("a", 1, text = "", kind = MessageKind.UNREADABLE)))
    }

    @Test
    fun `status words follow the message, and explain a message that has not left`() {
        assertEquals("Read", statusLabel(MessageStatus.READ, online = false, stuck = true))
        assertEquals("Delivered", statusLabel(MessageStatus.DELIVERED, online = true, stuck = false))
        assertEquals("Sent", statusLabel(MessageStatus.SENT, online = true, stuck = false))
        assertEquals("Sending…", statusLabel(MessageStatus.PENDING, online = true, stuck = false))
        assertEquals("Waiting", statusLabel(MessageStatus.PENDING, online = true, stuck = true))
        assertEquals("Waiting for connection", statusLabel(MessageStatus.PENDING, online = false, stuck = false))
    }

    // ---- One conversation ----------------------------------------------------

    private val utc = ZoneOffset.UTC
    private val hour = 3_600_000L
    private val day = 24 * hour

    private fun message(seq: Long, at: Long, outgoing: Boolean, kind: Int = MessageKind.TEXT) =
        MessageEntity(seq = seq, peer = "p", id = "id$seq", outgoing = outgoing, kind = kind, text = "t$seq", at = at)

    /** One letter per item, in list order (newest first): d = day line, s/e/b/m = starts/ends/both/middle of a run. */
    private fun shape(items: List<ChatItem>): String = items.joinToString("") {
        when (it) {
            is DayItem -> "d"
            is MessageItem -> when {
                it.startsRun && it.endsRun -> "b"
                it.startsRun -> "s"
                it.endsRun -> "e"
                else -> "m"
            }
        }
    }

    @Test
    fun `an empty conversation lists nothing`() {
        assertEquals(emptyList<ChatItem>(), chatItems(emptyList(), utc))
    }

    @Test
    fun `each day gets one line, above its oldest message`() {
        // Newest first: two messages on day 2, one on day 1.
        val messages = listOf(
            message(3, 2 * day + 2 * hour, outgoing = true),
            message(2, 2 * day + hour, outgoing = true),
            message(1, day + hour, outgoing = true),
        )

        val items = chatItems(messages, utc)

        assertEquals("esdbd", shape(items))
        assertEquals(LocalDate.ofEpochDay(2), (items[2] as DayItem).day)
        assertEquals(LocalDate.ofEpochDay(1), (items[4] as DayItem).day)
    }

    @Test
    fun `a run is messages in a row from the same side`() {
        val messages = listOf(
            message(6, 6 * hour, outgoing = false),
            message(5, 5 * hour, outgoing = true),
            message(4, 4 * hour, outgoing = true),
            message(3, 3 * hour, outgoing = true),
            message(2, 2 * hour, outgoing = false),
            message(1, hour, outgoing = false),
        )

        assertEquals("bemsesd", shape(chatItems(messages, utc)))
    }

    @Test
    fun `a note about an unreadable message stands alone`() {
        val messages = listOf(
            message(3, 3 * hour, outgoing = false),
            message(2, 2 * hour, outgoing = false, kind = MessageKind.UNREADABLE),
            message(1, hour, outgoing = false),
        )

        assertEquals("bbbd", shape(chatItems(messages, utc)))
    }

    @Test
    fun `a message that arrived late stands under its own day, and every line can be told apart`() {
        // Written yesterday, delivered after today's messages.
        val messages = listOf(
            message(3, hour, outgoing = false),
            message(2, day + hour, outgoing = true),
            message(1, hour, outgoing = true),
        )

        val items = chatItems(messages, utc)

        assertEquals("bdbdbd", shape(items))
        assertEquals(items.size, items.map { it.key }.toSet().size)
    }

    @Test
    fun `the day of a message depends on where the phone is`() {
        val lateEvening = 23 * hour
        val india = java.time.ZoneId.of("Asia/Kolkata")

        assertEquals(LocalDate.ofEpochDay(0), dayOf(lateEvening, utc))
        assertEquals(LocalDate.ofEpochDay(1), dayOf(lateEvening, india))
    }

    @Test
    fun `recent days are named, older ones dated`() {
        val today = LocalDate.of(2026, 11, 14)

        assertEquals("Today", dayLabel(today, today))
        assertEquals("Yesterday", dayLabel(today.minusDays(1), today))
        assertEquals("Nov 12, 2026", dayLabel(today.minusDays(2), today, Locale.US))
        // Tomorrow can only come from a wrong clock; it is dated, not named.
        assertEquals("Nov 15, 2026", dayLabel(today.plusDays(1), today, Locale.US))
    }

    // ---- People ----------------------------------------------------------------

    @Test
    fun `the circle shows the first character of the name`() {
        assertEquals("A", initialOf("asha"))
        assertEquals("R", initialOf("  rohan "))
        assertEquals("?", initialOf(null))
        assertEquals("?", initialOf("   "))
        assertEquals("\uD83D\uDE00", initialOf("\uD83D\uDE00 Meera"))
    }

    @Test
    fun `a person keeps their colour`() {
        val ids = listOf("ab12".repeat(16), "cd34".repeat(16), "", "ffff")

        ids.forEach { id ->
            val index = avatarIndex(id, 8)
            assertTrue(index in 0 until 8)
            assertEquals(index, avatarIndex(id, 8))
        }
    }
}
