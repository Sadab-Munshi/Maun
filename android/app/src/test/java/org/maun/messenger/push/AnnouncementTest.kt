package org.maun.messenger.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maun.messenger.data.ChatSummary

class AnnouncementTest {

    private val asha = "aa".repeat(32)
    private val rohan = "bb".repeat(32)
    private val stranger = "cc".repeat(32)
    private val names = mapOf(asha to "Asha", rohan to "Rohan")

    @Test
    fun `a notification about one conversation names its newest message`() {
        val said = announce(
            listOf(
                ChatSummary(
                    peer = asha,
                    seq = 1,
                    kind = 0,
                    text = "See you at six",
                    outgoing = false,
                    status = 0,
                    at = 0,
                    unread = 1,
                    id = "3f2c9a1e-0000-4000-8000-000000000001",
                ),
            ),
            names,
        )!!
        // Tapping it opens the conversation at that message, not at its bottom.
        assertEquals("3f2c9a1e-0000-4000-8000-000000000001", said.message)
        // Several conversations at once name none: there is nowhere single to go.
        assertNull(announce(listOf(chat(asha, 1), chat(rohan, 1)), names)!!.message)
    }

    private fun chat(peer: String, unread: Int, text: String = "the secret plan") =
        ChatSummary(peer = peer, seq = 1, kind = 0, text = text, outgoing = false, status = 0, at = 0, unread = unread)

    @Test
    fun nothingUnreadMeansNoNotification() {
        assertNull(announce(emptyList(), names))
        assertNull(announce(listOf(chat(asha, 0), chat(rohan, 0)), names))
    }

    @Test
    fun oneContactIsNamed() {
        assertEquals(Announcement("Asha", "New message", "New message", 1, peer = asha), announce(listOf(chat(asha, 1)), names))
        assertEquals(
            Announcement("Asha", "3 new messages", "3 new messages", 3, peer = asha),
            announce(listOf(chat(asha, 3), chat(rohan, 0)), names),
        )
    }

    @Test
    fun severalChatsAreCounted() {
        assertEquals(
            Announcement("Maun", "5 new messages in 2 chats", "5 new messages", 5, lines = listOf("Asha: 2 new messages", "Rohan: 3 new messages")),
            announce(listOf(chat(asha, 2), chat(rohan, 3)), names),
        )
    }

    @Test
    fun aStrangerIsNotNamed() {
        assertEquals(
            Announcement("Maun", "New message request", "New message", 1, peer = stranger),
            announce(listOf(chat(stranger, 1)), names),
        )
        assertEquals("2 messages from someone new", announce(listOf(chat(stranger, 2)), names)!!.text)
    }

    /** Everything of a notification that is put on the screen. */
    private fun shown(a: Announcement) = (listOf(a.title, a.text, a.discreet) + a.lines).joinToString(" | ")

    @Test
    fun withTextTurnedOffItNeverContainsTheMessageOrAnId() {
        val all = listOf(chat(asha, 1), chat(rohan, 2), chat(stranger, 1))
        for (chats in listOf(all, all.take(1), all.takeLast(1))) {
            val said = shown(announce(chats, names, showText = false)!!)
            assertFalse(said, said.contains("secret"))
            assertFalse(said, said.contains(stranger))
            assertFalse(said, said.contains(asha))
        }
        // What a locked screen shows names nobody at all.
        assertFalse(announce(all.take(1), names)!!.discreet.contains("Asha"))
    }

    @Test
    fun withTextTurnedOnItQuotesTheNewestMessageOfContacts() {
        val one = announce(listOf(chat(asha, 1, "See you at six")), names, showText = true)!!
        assertEquals(Announcement("Asha", "See you at six", "New message", 1, peer = asha), one)
        assertEquals("Asha (3 new)", announce(listOf(chat(asha, 3, "last one")), names, showText = true)!!.title)

        val several = announce(listOf(chat(asha, 2, "hello"), chat(rohan, 1, "line one\nline two")), names, showText = true)!!
        assertEquals(listOf("Asha: hello", "Rohan: line one line two"), several.lines)
        assertNull(several.peer)
    }

    @Test
    fun aStrangerIsNeverQuotedAndTheLockScreenNeverShowsText() {
        val all = listOf(chat(asha, 1), chat(stranger, 1))
        val said = announce(all, names, showText = true)!!
        assertTrue(said.lines.contains("Someone new: new message"))
        assertFalse(shown(said).contains(stranger))
        assertFalse(said.discreet.contains("secret"))
        assertFalse(announce(listOf(chat(stranger, 1)), names, showText = true)!!.text.contains("secret"))
        assertFalse(announce(listOf(chat(asha, 1)), names, showText = true)!!.discreet.contains("secret"))
    }

    @Test
    fun whatWasUnreadAtStartIsNotNews() {
        val tracker = UnreadTracker()
        assertFalse(tracker.hasNew(listOf(chat(asha, 2))))
        assertFalse(tracker.hasNew(listOf(chat(asha, 2))))
    }

    @Test
    fun aNewMessageIsNews() {
        val tracker = UnreadTracker()
        tracker.hasNew(listOf(chat(asha, 2)))
        assertTrue(tracker.hasNew(listOf(chat(asha, 3))))
        // The same state again is not.
        assertFalse(tracker.hasNew(listOf(chat(asha, 3))))
        // A first message from someone else is.
        assertTrue(tracker.hasNew(listOf(chat(asha, 3), chat(rohan, 1))))
    }

    @Test
    fun readingIsNotNewsButTheNextMessageIs() {
        val tracker = UnreadTracker()
        tracker.hasNew(emptyList())
        assertTrue(tracker.hasNew(listOf(chat(asha, 1))))
        assertFalse(tracker.hasNew(listOf(chat(asha, 0))))
        assertTrue(tracker.hasNew(listOf(chat(asha, 1))))
        // A conversation that was deleted and starts again.
        assertFalse(tracker.hasNew(emptyList()))
        assertTrue(tracker.hasNew(listOf(chat(asha, 1))))
    }
}
