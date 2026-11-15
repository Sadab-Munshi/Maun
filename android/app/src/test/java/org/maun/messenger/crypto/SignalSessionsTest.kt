package org.maun.messenger.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maun.messenger.support.FakeDb

class SignalSessionsTest {

    private class Party(val identity: Identity = Identity.generate(), val db: FakeDb = FakeDb()) {
        val id = identity.id
        val registrationId = SignalSessions.newRegistrationId()

        /** A new object on the same storage: what the app has after a restart. */
        fun sessions() = SignalSessions(identity, registrationId, db)

        fun bundle(withOneTimeKey: Boolean = true): PeerKeys {
            val sessions = sessions()
            val (published, _) = db.inTransaction { sessions.publishedKeys(1_000) }
            val oneTime = if (withOneTimeKey) db.inTransaction { sessions.newOneTimeKeys(1) }.single() else null
            return PeerKeys(published.registrationId, published.signedPreKey, published.kyberPreKey, oneTime)
        }
    }

    private fun text(bytes: ByteArray) = String(bytes, Charsets.UTF_8)

    @Test
    fun `two people exchange messages using published keys`() {
        val alice = Party()
        val bob = Party()
        assertFalse(alice.sessions().hasSession(bob.id))

        alice.sessions().startSession(bob.id, bob.bundle())
        assertTrue(alice.sessions().hasSession(bob.id))

        val first = alice.sessions().encrypt(bob.id, "hello bob".toByteArray())
        assertEquals(SignalSessions.KIND_PREKEY_MESSAGE, first.kind)
        assertEquals("hello bob", text(bob.sessions().decrypt(alice.id, first.kind, first.body)))

        val reply = bob.sessions().encrypt(alice.id, "hello alice".toByteArray())
        assertEquals(SignalSessions.KIND_MESSAGE, reply.kind)
        assertEquals("hello alice", text(alice.sessions().decrypt(bob.id, reply.kind, reply.body)))

        val next = alice.sessions().encrypt(bob.id, "and again".toByteArray())
        assertEquals(SignalSessions.KIND_MESSAGE, next.kind)
        assertEquals("and again", text(bob.sessions().decrypt(alice.id, next.kind, next.body)))
    }

    @Test
    fun `a session can start without a one-time key`() {
        val alice = Party()
        val bob = Party()
        alice.sessions().startSession(bob.id, bob.bundle(withOneTimeKey = false))
        val sealed = alice.sessions().encrypt(bob.id, "hi".toByteArray())
        assertEquals("hi", text(bob.sessions().decrypt(alice.id, sealed.kind, sealed.body)))
    }

    @Test
    fun `a one-time key is used once and then gone`() {
        val alice = Party()
        val bob = Party()
        val bundle = bob.bundle()
        assertEquals(1, bob.db.preKeyCount(SignalStorage.ONE_TIME))
        alice.sessions().startSession(bob.id, bundle)
        val sealed = alice.sessions().encrypt(bob.id, "hi".toByteArray())
        bob.sessions().decrypt(alice.id, sealed.kind, sealed.body)
        assertEquals(0, bob.db.preKeyCount(SignalStorage.ONE_TIME))

        // Someone else handed the same, now spent, key cannot start a session with it.
        val carol = Party()
        carol.sessions().startSession(bob.id, bundle)
        val late = carol.sessions().encrypt(bob.id, "hi".toByteArray())
        assertThrows(UndecryptableException::class.java) { bob.sessions().decrypt(carol.id, late.kind, late.body) }
    }

    @Test
    fun `the published keys are created once and stay the same`() {
        val bob = Party()
        val (first, createdFirst) = bob.sessions().publishedKeys(1)
        val (second, createdSecond) = bob.sessions().publishedKeys(2)
        assertTrue(createdFirst)
        assertFalse(createdSecond)
        assertArrayEquals(first.signedPreKey.key, second.signedPreKey.key)
        assertArrayEquals(first.kyberPreKey.key, second.kyberPreKey.key)
        assertEquals(33, first.signedPreKey.key.size)
        assertEquals(1569, first.kyberPreKey.key.size)
        assertEquals(64, first.signedPreKey.signature.size)
    }

    @Test
    fun `new one-time keys never reuse a number that is still in use`() {
        val bob = Party()
        val first = bob.sessions().newOneTimeKeys(3)
        val second = bob.sessions().newOneTimeKeys(2)
        assertEquals(listOf(1, 2, 3), first.map { it.id })
        assertEquals(listOf(4, 5), second.map { it.id })
        assertEquals(33, first.first().key.size)
    }

    @Test
    fun `keys signed by someone else are refused`() {
        val alice = Party()
        val bob = Party()
        val mallory = Party()
        // The relay answers a request for Bob's keys with Mallory's.
        assertThrows(BadKeysException::class.java) { alice.sessions().startSession(bob.id, mallory.bundle()) }
        assertFalse(alice.sessions().hasSession(bob.id))

        // Bob's real keys with one swapped in.
        val real = bob.bundle()
        val swapped = PeerKeys(real.registrationId, real.signedPreKey, mallory.bundle().kyberPreKey, real.oneTimePreKey)
        assertThrows(BadKeysException::class.java) { alice.sessions().startSession(bob.id, swapped) }

        // Garbage of the right size.
        val garbage = PeerKeys(1, SignedPublicKey(1, ByteArray(33), ByteArray(64)), real.kyberPreKey, null)
        assertThrows(BadKeysException::class.java) { alice.sessions().startSession(bob.id, garbage) }
    }

    @Test
    fun `the relay cannot pass one sender off as another`() {
        val alice = Party()
        val bob = Party()
        val mallory = Party()
        mallory.sessions().startSession(bob.id, bob.bundle())
        val sealed = mallory.sessions().encrypt(bob.id, "it's alice, honest".toByteArray())
        // Delivered with Alice's ID on the envelope.
        assertThrows(UndecryptableException::class.java) { bob.sessions().decrypt(alice.id, sealed.kind, sealed.body) }
        assertFalse(bob.db.hasSession(alice.id))
        // Under Mallory's own ID it is a perfectly good message.
        assertEquals("it's alice, honest", text(bob.sessions().decrypt(mallory.id, sealed.kind, sealed.body)))
    }

    @Test
    fun `the same message is not accepted twice`() {
        val alice = Party()
        val bob = Party()
        alice.sessions().startSession(bob.id, bob.bundle())
        val first = alice.sessions().encrypt(bob.id, "one".toByteArray())
        val second = alice.sessions().encrypt(bob.id, "two".toByteArray())

        bob.sessions().decrypt(alice.id, first.kind, first.body)
        // The message that started the session, delivered again.
        assertThrows(DuplicateMessageError::class.java) { bob.sessions().decrypt(alice.id, first.kind, first.body) }
        bob.sessions().decrypt(alice.id, second.kind, second.body)
        assertThrows(DuplicateMessageError::class.java) { bob.sessions().decrypt(alice.id, second.kind, second.body) }

        val reply = bob.sessions().encrypt(alice.id, "three".toByteArray())
        alice.sessions().decrypt(bob.id, reply.kind, reply.body)
        assertThrows(DuplicateMessageError::class.java) { alice.sessions().decrypt(bob.id, reply.kind, reply.body) }
    }

    @Test
    fun `messages that arrive out of order still decrypt`() {
        val alice = Party()
        val bob = Party()
        alice.sessions().startSession(bob.id, bob.bundle())
        val sealed = (1..5).map { alice.sessions().encrypt(bob.id, "message $it".toByteArray()) }
        val order = listOf(2, 0, 4, 1, 3)
        val received = order.map { text(bob.sessions().decrypt(alice.id, sealed[it].kind, sealed[it].body)) }
        assertEquals(order.map { "message ${it + 1}" }, received)
    }

    @Test
    fun `a failed decryption leaves the session as it was`() {
        val alice = Party()
        val bob = Party()
        alice.sessions().startSession(bob.id, bob.bundle())
        val first = alice.sessions().encrypt(bob.id, "one".toByteArray())
        bob.sessions().decrypt(alice.id, first.kind, first.body)
        val reply = bob.sessions().encrypt(alice.id, "got it".toByteArray())
        alice.sessions().decrypt(bob.id, reply.kind, reply.body)

        // An ordinary message ends in its authentication code.
        val second = alice.sessions().encrypt(bob.id, "two".toByteArray())
        assertEquals(SignalSessions.KIND_MESSAGE, second.kind)
        val damaged = second.body.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertThrows(UndecryptableException::class.java) { bob.sessions().decrypt(alice.id, second.kind, damaged) }
        assertThrows(UndecryptableException::class.java) { bob.sessions().decrypt(alice.id, 99, second.body) }
        assertThrows(UndecryptableException::class.java) { bob.sessions().decrypt(alice.id, second.kind, ByteArray(5)) }
        assertEquals("two", text(bob.sessions().decrypt(alice.id, second.kind, second.body)))
    }

    @Test
    fun `both sides writing first at the same moment still end up talking`() {
        val alice = Party()
        val bob = Party()
        alice.sessions().startSession(bob.id, bob.bundle())
        bob.sessions().startSession(alice.id, alice.bundle())
        val fromAlice = alice.sessions().encrypt(bob.id, "hi bob".toByteArray())
        val fromBob = bob.sessions().encrypt(alice.id, "hi alice".toByteArray())
        assertEquals("hi bob", text(bob.sessions().decrypt(alice.id, fromAlice.kind, fromAlice.body)))
        assertEquals("hi alice", text(alice.sessions().decrypt(bob.id, fromBob.kind, fromBob.body)))

        repeat(3) { round ->
            val a = alice.sessions().encrypt(bob.id, "a$round".toByteArray())
            assertEquals("a$round", text(bob.sessions().decrypt(alice.id, a.kind, a.body)))
            val b = bob.sessions().encrypt(alice.id, "b$round".toByteArray())
            assertEquals("b$round", text(alice.sessions().decrypt(bob.id, b.kind, b.body)))
        }
    }

    @Test
    fun `hex helpers agree with each other`() {
        val bytes = ByteArray(32) { (it * 7 - 100).toByte() }
        assertArrayEquals(bytes, bytes.toHex().hexToBytes())
        assertThrows(IllegalArgumentException::class.java) { "abc".hexToBytes() }
        assertThrows(IllegalArgumentException::class.java) { "zz".hexToBytes() }
    }
}
