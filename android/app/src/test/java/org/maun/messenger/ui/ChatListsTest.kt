package org.maun.messenger.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.maun.messenger.data.ChatSummary
import org.maun.messenger.data.ContactEntity
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
    fun `the preview line says who wrote and hides what could not be read`() {
        assertEquals("No messages yet", previewOf(null))
        assertEquals("hello", previewOf(summary("a", 1, text = "hello")))
        assertEquals("You: hello", previewOf(summary("a", 1, text = "hello", outgoing = true)))
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
}
