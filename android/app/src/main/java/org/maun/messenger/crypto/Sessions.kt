package org.maun.messenger.crypto

import java.time.Instant
import org.signal.libsignal.protocol.DuplicateMessageException
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.SignedPreKeyRecord

/** The public half of a pre-key, with its owner's signature over it. */
class SignedPublicKey(val id: Int, val key: ByteArray, val signature: ByteArray)

/** The public half of a one-time pre-key. */
class PublicPreKey(val id: Int, val key: ByteArray)

/** The keys this phone publishes on the relay. */
class PublishedKeys(val registrationId: Int, val signedPreKey: SignedPublicKey, val kyberPreKey: SignedPublicKey)

/** Someone else's published keys, as fetched from the relay. Untrusted until checked. */
class PeerKeys(
    val registrationId: Int,
    val signedPreKey: SignedPublicKey,
    val kyberPreKey: SignedPublicKey,
    val oneTimePreKey: PublicPreKey?,
)

/** An encrypted message: [kind] says whether it also carries the key exchange. */
class Sealed(val kind: Int, val body: ByteArray)

/** The fetched keys were not signed by the ID they were fetched for. */
class BadKeysException(cause: Throwable) : Exception("keys do not belong to this ID", cause)

/** This exact message was decrypted before. */
class DuplicateMessageError : Exception("message already received")

/** The message cannot be decrypted with the keys on this phone. */
class UndecryptableException(cause: Throwable?) : Exception("message could not be decrypted", cause)

/**
 * End-to-end encryption between this phone and its contacts, by Maun ID.
 *
 * This is the only place (with [SignalStore]) that talks to libsignal. Calls
 * block and must run inside a storage transaction: each one reads a session,
 * moves it forward and writes it back.
 */
class SignalSessions(identity: Identity, private val registrationId: Int, private val storage: SignalStorage) {

    private val keyPair = identity.keyPair
    private val store = SignalStore(keyPair, registrationId, storage)

    /** True when a message to [peer] can be encrypted without fetching their keys first. */
    fun hasSession(peer: String): Boolean =
        store.loadSession(address(peer))?.hasSenderChain(Instant.now()) == true

    /**
     * Starts a session with [peer] from keys the relay handed out. The relay
     * is not trusted: the keys must be signed by the identity key that *is*
     * [peer]'s ID, or this throws [BadKeysException].
     */
    fun startSession(peer: String, keys: PeerKeys) {
        try {
            val oneTime = keys.oneTimePreKey
            val bundle = PreKeyBundle(
                keys.registrationId,
                DEVICE_ID,
                oneTime?.id ?: PreKeyBundle.NULL_PRE_KEY_ID,
                oneTime?.let { ECPublicKey(it.key) },
                keys.signedPreKey.id,
                ECPublicKey(keys.signedPreKey.key),
                keys.signedPreKey.signature,
                SignalStore.identityKeyOf(peer),
                keys.kyberPreKey.id,
                KEMPublicKey(keys.kyberPreKey.key),
                keys.kyberPreKey.signature,
            )
            SessionBuilder(store, address(peer)).process(bundle)
        } catch (e: Exception) {
            throw BadKeysException(e)
        }
    }

    /** Encrypts for [peer]. Needs a session: see [hasSession] and [startSession]. */
    fun encrypt(peer: String, plaintext: ByteArray): Sealed {
        val message = SessionCipher(store, address(peer)).encrypt(plaintext)
        return Sealed(message.type, message.serialize())
    }

    /**
     * Decrypts a message that the relay says came from [peer]. A message that
     * starts a session only decrypts if it was made by the key that is
     * [peer]'s ID, so the relay cannot pass off one sender as another.
     */
    fun decrypt(peer: String, kind: Int, body: ByteArray): ByteArray {
        try {
            val cipher = SessionCipher(store, address(peer))
            return when (kind) {
                CiphertextMessage.PREKEY_TYPE -> cipher.decrypt(PreKeySignalMessage(body))
                CiphertextMessage.WHISPER_TYPE -> cipher.decrypt(SignalMessage(body))
                else -> throw UndecryptableException(null)
            }
        } catch (e: DuplicateMessageException) {
            throw DuplicateMessageError()
        } catch (e: UndecryptableException) {
            throw e
        } catch (e: Exception) {
            throw UndecryptableException(e)
        }
    }

    /**
     * The keys this phone publishes so that others can write to it while it is
     * offline. They are created on first use. [created] tells the caller that
     * the relay cannot have them yet.
     */
    fun publishedKeys(nowMillis: Long): Pair<PublishedKeys, Boolean> {
        var created = false
        val signed = storage.loadPreKey(SignalStorage.SIGNED, LONG_LIVED_KEY_ID)?.let(::SignedPreKeyRecord)
            ?: PreKeys.signedPreKey(keyPair, LONG_LIVED_KEY_ID, nowMillis).also {
                store.storeSignedPreKey(it.id, it)
                created = true
            }
        val kyber = storage.loadPreKey(SignalStorage.KYBER, LONG_LIVED_KEY_ID)?.let(::KyberPreKeyRecord)
            ?: PreKeys.kyberPreKey(keyPair, LONG_LIVED_KEY_ID, nowMillis).also {
                store.storeKyberPreKey(it.id, it)
                created = true
            }
        val published = PublishedKeys(
            registrationId = registrationId,
            signedPreKey = SignedPublicKey(signed.id, signed.keyPair.publicKey.serialize(), signed.signature),
            kyberPreKey = SignedPublicKey(kyber.id, kyber.keyPair.publicKey.serialize(), kyber.signature),
        )
        return published to created
    }

    /**
     * Makes [count] new one-time pre-keys, keeps their private halves, and
     * returns the public halves to upload. Each is used for one session start
     * and then deleted, here and on the relay.
     */
    fun newOneTimeKeys(count: Int): List<PublicPreKey> {
        val first = storage.highestPreKeyId(SignalStorage.ONE_TIME) % MAX_KEY_ID + 1
        val usable = minOf(count, MAX_KEY_ID - first + 1)
        return PreKeys.oneTimePreKeys(first, usable).map { record ->
            store.storePreKey(record.id, record)
            PublicPreKey(record.id, record.keyPair.publicKey.serialize())
        }
    }

    /** Forgets the session with [peer]. The next message to them starts a new one. */
    fun endSession(peer: String) = storage.deleteSession(peer)

    private fun address(peer: String) = SignalProtocolAddress(peer, DEVICE_ID)

    companion object {
        /** One phone per ID, so the device number is always the same. */
        private const val DEVICE_ID = 1

        /** The signed and Kyber pre-keys are made once; replacing them on a schedule comes later. */
        private const val LONG_LIVED_KEY_ID = 1

        /** libsignal numbers pre-keys with 24 bits. */
        private const val MAX_KEY_ID = 0xFFFFFF

        const val KIND_MESSAGE = CiphertextMessage.WHISPER_TYPE
        const val KIND_PREKEY_MESSAGE = CiphertextMessage.PREKEY_TYPE

        /** A new random registration ID: libsignal's way of telling installs of one ID apart. */
        fun newRegistrationId(): Int = org.signal.libsignal.protocol.util.KeyHelper.generateRegistrationId(false)
    }
}
