package org.maun.messenger

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maun.messenger.crypto.Identity
import org.maun.messenger.crypto.SignalSessions
import org.maun.messenger.data.MessageEntity
import org.maun.messenger.data.MessageKind
import org.maun.messenger.data.MessageStatus
import org.maun.messenger.data.OutboxEntity
import org.maun.messenger.support.FakeDb

/** What happens on this phone when a contact turns up under a new ID. No relay is needed for it. */
class FollowTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val db = FakeDb()
    private val identity = Identity.generate()
    private val engine = ChatEngine(identity.id, SignalSessions(identity, SignalSessions.newRegistrationId(), db), db, db, scope, now = { 1_000_000 })

    private val old = "aa".repeat(32)
    private val new = "bb".repeat(32)

    @After
    fun stop() = scope.cancel()

    private fun message(id: String, outgoing: Boolean, text: String, status: Int = MessageStatus.READ, at: Long = 900_000, kind: Int = MessageKind.TEXT) =
        db.insertMessage(MessageEntity(peer = old, id = id, outgoing = outgoing, kind = kind, text = text, at = at, status = status))

    private fun sent(position: Int): Payload = Payload.decode(db.outboxFor(new)[position].payload)

    @Test
    fun `the contact, the conversation and what was still to go out move to the new ID`() = runBlocking {
        db.addContact(old, name = "Bob", username = "bob")
        message("m1", outgoing = false, text = "from Bob")
        message("m2", outgoing = true, text = "reached Bob", status = MessageStatus.DELIVERED)
        message("m3", outgoing = true, text = "taken by the relay, never handed over", status = MessageStatus.SENT)
        message("m4", outgoing = true, text = "still on this phone", status = MessageStatus.PENDING)
        db.insertOutbox(OutboxEntity(peer = old, messageId = "m4", payload = Payload.encode(Payload.Text("m4", 900_000, "still on this phone"))))
        db.insertOutbox(OutboxEntity(peer = old, messageId = null, payload = Payload.encode(Payload.Receipt(read = listOf("m1")))))

        engine.movePeer(old, new, "bob")

        assertNull(db.contact(old))
        val contact = db.contact(new)!!
        assertEquals("Bob", contact.name)
        assertEquals("bob", contact.username)
        assertFalse("the new ID has not been checked by anyone", contact.verified)

        assertTrue(db.messagesWith(old).isEmpty())
        val chat = db.messagesWith(new)
        assertEquals(listOf("from Bob", "reached Bob", "taken by the relay, never handed over", "still on this phone"), chat.take(4).map { it.text })
        val notice = chat.last()
        assertEquals(MessageKind.NOTICE, notice.kind)
        assertTrue(notice.text.startsWith("Bob set up Maun again"))
        assertFalse(notice.unread)

        // The undelivered one goes out again, ahead of what was waiting; the receipt is dropped.
        assertEquals(MessageStatus.PENDING, chat.single { it.id == "m3" }.status)
        assertEquals(MessageStatus.DELIVERED, chat.single { it.id == "m2" }.status)
        assertEquals(2, db.outboxFor(new).size)
        assertEquals(Payload.Text("m3", 900_000, "taken by the relay, never handed over"), sent(0))
        assertEquals(Payload.Text("m4", 900_000, "still on this phone"), sent(1))
        assertTrue(db.outboxFor(old).isEmpty())
    }

    @Test
    fun `a contact already kept under the new ID keeps its name and takes the old conversation`() = runBlocking {
        db.addContact(old, name = "Bob (old phone)", username = "bob")
        db.addContact(new, name = "Bobby")
        message("m1", outgoing = false, text = "old history")
        engine.movePeer(old, new, "bob")
        assertNull(db.contact(old))
        assertEquals("Bobby", db.contact(new)!!.name)
        assertEquals("bob", db.contact(new)!!.username)
        assertEquals("old history", db.messagesWith(new).first().text)
    }

    @Test
    fun `very old undelivered messages are not sent again, and nothing happens for someone who is not a contact`() = runBlocking {
        engine.movePeer(old, new, "bob")
        assertNull(db.contact(new))
        assertTrue(db.messagesWith(new).isEmpty())

        db.addContact(old, name = "Bob", username = "bob")
        message("ancient", outgoing = true, text = "from long ago", status = MessageStatus.SENT, at = 1_000_000 - 40L * 24 * 60 * 60 * 1000)
        engine.movePeer(old, new, "bob")
        assertTrue(db.outboxFor(new).isEmpty())
        assertEquals(MessageStatus.SENT, db.messagesWith(new).first().status)
    }
}
