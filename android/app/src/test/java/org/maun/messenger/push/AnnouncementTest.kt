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

    private fun chat(peer: String, unread: Int, text: String = "the secret plan") =
        ChatSummary(peer = peer, seq = 1, kind = 0, text = text, outgoing = false, status = 0, at = 0, unread = unread)

    @Test
    fun nothingUnreadMeansNoNotification() {
        assertNull(announce(emptyList(), names))
        assertNull(announce(listOf(chat(asha, 0), chat(rohan, 0)), names))
    }

    @Test
    fun oneContactIsNamed() {
        assertEquals(Announcement("Asha", "New message", "New message", 1), announce(listOf(chat(asha, 1)), names))
        assertEquals(
            Announcement("Asha", "3 new messages", "3 new messages", 3),
            announce(listOf(chat(asha, 3), chat(rohan, 0)), names),
        )
    }

    @Test
    fun severalChatsAreCounted() {
        assertEquals(
            Announcement("Maun", "5 new messages in 2 chats", "5 new messages", 5),
            announce(listOf(chat(asha, 2), chat(rohan, 3)), names),
        )
    }

    @Test
    fun aStrangerIsNotNamed() {
        assertEquals(
            Announcement("Maun", "New message request", "New message", 1),
            announce(listOf(chat(stranger, 1)), names),
        )
        assertEquals("2 messages from someone new", announce(listOf(chat(stranger, 2)), names)!!.text)
    }

    @Test
    fun neverContainsTheMessageOrAnId() {
        val all = listOf(chat(asha, 1), chat(rohan, 2), chat(stranger, 1))
        for (chats in listOf(all, all.take(1), all.takeLast(1))) {
            val said = announce(chats, names).toString()
            assertFalse(said, said.contains("secret"))
            assertFalse(said, said.contains(stranger))
            assertFalse(said, said.contains(asha))
        }
        // What a locked screen shows names nobody at all.
        assertFalse(announce(all.take(1), names)!!.discreet.contains("Asha"))
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
