package org.maun.messenger.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maun.messenger.crypto.SignalSelfTest.Party
import org.signal.libsignal.protocol.DuplicateMessageException
import org.signal.libsignal.protocol.InvalidKeyException
import org.signal.libsignal.protocol.InvalidMessageException
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.state.PreKeyBundle

/**
 * Runs libsignal on the JVM (its jar ships a desktop build of the native
 * library). Pins down the behaviour Maun relies on, so a libsignal upgrade that
 * changes it fails here instead of on a phone.
 */
class SignalSelfTestTest {

    @Test
    fun `two users set up a session and exchange messages`() {
        val result = SignalSelfTest.run()
        assertTrue("expected a current protocol version, got ${result.sessionVersion}", result.sessionVersion >= 4)
    }

    @Test
    fun `conversation keeps working over many messages in both directions`() {
        val alice = Party("alice")
        val bob = Party("bob")
        alice.startSessionWith(bob, bob.publishBundle())

        repeat(25) { round ->
            val toBob = "to bob $round".toByteArray()
            val a = alice.encryptFor(bob, toBob)
            assertArrayEquals(toBob, bob.decryptFrom(alice, a.type, a.serialize()))

            val toAlice = "to alice $round".toByteArray()
            val b = bob.encryptFor(alice, toAlice)
            assertEquals(CiphertextMessage.WHISPER_TYPE, b.type)
            assertArrayEquals(toAlice, alice.decryptFrom(bob, b.type, b.serialize()))
        }
    }

    @Test
    fun `messages that arrive out of order still decrypt`() {
        val alice = Party("alice")
        val bob = Party("bob")
        alice.startSessionWith(bob, bob.publishBundle())

        val sent = (0 until 5).map { alice.encryptFor(bob, "message $it".toByteArray()) }
        for (index in listOf(0, 3, 1, 4, 2)) {
            val message = sent[index]
            assertArrayEquals(
                "message $index".toByteArray(),
                bob.decryptFrom(alice, message.type, message.serialize()),
            )
        }
    }

    @Test
    fun `a tampered message is rejected`() {
        val alice = Party("alice")
        val bob = Party("bob")
        alice.startSessionWith(bob, bob.publishBundle())
        val first = alice.encryptFor(bob, "first".toByteArray())
        bob.decryptFrom(alice, first.type, first.serialize())

        val reply = bob.encryptFor(alice, "reply".toByteArray())
        val tampered = reply.serialize().also { it[it.size - 12] = (it[it.size - 12] + 1).toByte() }
        assertThrows(InvalidMessageException::class.java) {
            alice.decryptFrom(bob, reply.type, tampered)
        }
        // The untouched message still decrypts afterwards.
        assertArrayEquals("reply".toByteArray(), alice.decryptFrom(bob, reply.type, reply.serialize()))
    }

    @Test
    fun `the same message cannot be decrypted twice`() {
        val alice = Party("alice")
        val bob = Party("bob")
        alice.startSessionWith(bob, bob.publishBundle())
        val first = alice.encryptFor(bob, "once".toByteArray())

        bob.decryptFrom(alice, first.type, first.serialize())
        assertThrows(DuplicateMessageException::class.java) {
            bob.decryptFrom(alice, first.type, first.serialize())
        }
    }

    @Test
    fun `a one-time pre-key is used up by the first message`() {
        val alice = Party("alice")
        val bob = Party("bob")
        val bundle = bob.publishBundle()
        assertTrue(bob.store.containsPreKey(bundle.preKeyId))

        alice.startSessionWith(bob, bundle)
        val first = alice.encryptFor(bob, "hi".toByteArray())
        bob.decryptFrom(alice, first.type, first.serialize())

        assertFalse(bob.store.containsPreKey(bundle.preKeyId))
    }

    @Test
    fun `a bundle whose pre-key signature does not match the identity is refused`() {
        val alice = Party("alice")
        val bob = Party("bob")
        val mallory = Party("mallory")
        val real = bob.publishBundle()
        val forged = mallory.publishBundle()

        // Mallory's keys, presented under Bob's identity.
        val swapped = PreKeyBundle(
            real.registrationId,
            real.deviceId,
            forged.preKeyId,
            forged.preKey,
            forged.signedPreKeyId,
            forged.signedPreKey,
            forged.signedPreKeySignature,
            real.identityKey,
            forged.kyberPreKeyId,
            forged.kyberPreKey,
            forged.kyberPreKeySignature,
        )
        assertThrows(InvalidKeyException::class.java) { alice.startSessionWith(bob, swapped) }
    }

    @Test
    fun `a third party cannot read a message meant for someone else`() {
        val alice = Party("alice")
        val bob = Party("bob")
        val eve = Party("eve")
        eve.publishBundle()
        alice.startSessionWith(bob, bob.publishBundle())
        val first = alice.encryptFor(bob, "for bob only".toByteArray())

        assertThrows(Exception::class.java) {
            eve.decryptFrom(alice, first.type, first.serialize())
        }
    }
}
