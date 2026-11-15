package org.maun.messenger.crypto

import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore
import org.signal.libsignal.protocol.util.KeyHelper

/**
 * Two throwaway users set up a Signal session and exchange a message each way,
 * entirely in memory. Run on the phone it proves libsignal's native library
 * loads and works on this device; run as a unit test it guards against a
 * libsignal upgrade changing behaviour.
 */
object SignalSelfTest {

    class Result(val sessionVersion: Int)

    /** One side of the conversation: its key store and the bundle it publishes. */
    class Party(val name: String) {
        val address = SignalProtocolAddress(name, DEVICE_ID)
        val store: SignalProtocolStore =
            InMemorySignalProtocolStore(IdentityKeyPair.generate(), KeyHelper.generateRegistrationId(false))

        /** Creates fresh pre-keys, stores the private halves, returns the public bundle. */
        fun publishBundle(): PreKeyBundle {
            val now = System.currentTimeMillis()
            val signed = PreKeys.signedPreKey(store.identityKeyPair, 1, now)
            val kyber = PreKeys.kyberPreKey(store.identityKeyPair, 1, now)
            val oneTime = PreKeys.oneTimePreKeys(firstId = 1, count = 1).single()
            store.storeSignedPreKey(signed.id, signed)
            store.storeKyberPreKey(kyber.id, kyber)
            store.storePreKey(oneTime.id, oneTime)
            return PreKeyBundle(
                store.localRegistrationId,
                DEVICE_ID,
                oneTime.id,
                oneTime.keyPair.publicKey,
                signed.id,
                signed.keyPair.publicKey,
                signed.signature,
                store.identityKeyPair.publicKey,
                kyber.id,
                kyber.keyPair.publicKey,
                kyber.signature,
            )
        }

        fun startSessionWith(other: Party, bundle: PreKeyBundle) {
            SessionBuilder(store, other.address).process(bundle)
        }

        fun encryptFor(other: Party, plaintext: ByteArray): CiphertextMessage =
            SessionCipher(store, other.address).encrypt(plaintext)

        fun decryptFrom(other: Party, type: Int, serialized: ByteArray): ByteArray {
            val cipher = SessionCipher(store, other.address)
            return when (type) {
                CiphertextMessage.PREKEY_TYPE -> cipher.decrypt(PreKeySignalMessage(serialized))
                CiphertextMessage.WHISPER_TYPE -> cipher.decrypt(SignalMessage(serialized))
                else -> throw IllegalArgumentException("unexpected message type $type")
            }
        }
    }

    /** Throws if any step fails. */
    fun run(): Result {
        val alice = Party("alice")
        val bob = Party("bob")

        alice.startSessionWith(bob, bob.publishBundle())

        val hello = "hello bob".toByteArray()
        val first = alice.encryptFor(bob, hello)
        check(first.type == CiphertextMessage.PREKEY_TYPE) { "first message should carry the key exchange" }
        check(!first.serialize().containsSlice(hello)) { "ciphertext contains the plaintext" }
        check(bob.decryptFrom(alice, first.type, first.serialize()).contentEquals(hello)) {
            "Bob decrypted something else"
        }

        val reply = "hello alice".toByteArray()
        val second = bob.encryptFor(alice, reply)
        check(second.type == CiphertextMessage.WHISPER_TYPE) { "reply should use the established session" }
        check(alice.decryptFrom(bob, second.type, second.serialize()).contentEquals(reply)) {
            "Alice decrypted something else"
        }

        return Result(SessionCipher(alice.store, bob.address).sessionVersion)
    }

    private const val DEVICE_ID = 1

    private fun ByteArray.containsSlice(slice: ByteArray): Boolean =
        (0..size - slice.size).any { start -> slice.indices.all { this[start + it] == slice[it] } }
}
