package org.maun.messenger.crypto

import java.util.UUID
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.ReusedBaseKeyException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord

/**
 * libsignal's view of [SignalStorage].
 *
 * A Maun ID is the identity key itself, so there is nothing to remember about
 * other people's identity keys and no "safety number changed" case: a key
 * either is the ID it claims to be, or it is refused.
 */
internal class SignalStore(
    private val identity: IdentityKeyPair,
    private val registrationId: Int,
    private val storage: SignalStorage,
) : SignalProtocolStore {

    // ---- Identity ----------------------------------------------------------

    override fun getIdentityKeyPair(): IdentityKeyPair = identity

    override fun getLocalRegistrationId(): Int = registrationId

    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityKeyStore.IdentityChange =
        IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED

    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction,
    ): Boolean = identityKey.publicKey.publicKeyBytes.toHex() == address.name

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? = identityKeyOf(address.name)

    // ---- Sessions ----------------------------------------------------------

    override fun loadSession(address: SignalProtocolAddress): SessionRecord? =
        storage.loadSession(address.name)?.let(::SessionRecord)

    override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> =
        addresses.map { loadSession(it) ?: throw NoSessionException(it, "no session with ${it.name}") }

    override fun getSubDeviceSessions(name: String): List<Int> = emptyList()

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) =
        storage.storeSession(address.name, record.serialize())

    override fun containsSession(address: SignalProtocolAddress): Boolean = storage.loadSession(address.name) != null

    override fun deleteSession(address: SignalProtocolAddress) = storage.deleteSession(address.name)

    override fun deleteAllSessions(name: String) = storage.deleteSession(name)

    // ---- One-time pre-keys -------------------------------------------------

    override fun loadPreKey(preKeyId: Int): PreKeyRecord =
        PreKeyRecord(storage.loadPreKey(SignalStorage.ONE_TIME, preKeyId) ?: throw InvalidKeyIdException("no one-time pre-key $preKeyId"))

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) =
        storage.storePreKey(SignalStorage.ONE_TIME, preKeyId, record.serialize())

    override fun containsPreKey(preKeyId: Int): Boolean = storage.loadPreKey(SignalStorage.ONE_TIME, preKeyId) != null

    override fun removePreKey(preKeyId: Int) = storage.removePreKey(SignalStorage.ONE_TIME, preKeyId)

    // ---- Signed pre-keys ---------------------------------------------------

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord =
        SignedPreKeyRecord(storage.loadPreKey(SignalStorage.SIGNED, signedPreKeyId) ?: throw InvalidKeyIdException("no signed pre-key $signedPreKeyId"))

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> =
        storage.loadPreKeys(SignalStorage.SIGNED).map(::SignedPreKeyRecord)

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) =
        storage.storePreKey(SignalStorage.SIGNED, signedPreKeyId, record.serialize())

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean =
        storage.loadPreKey(SignalStorage.SIGNED, signedPreKeyId) != null

    override fun removeSignedPreKey(signedPreKeyId: Int) = storage.removePreKey(SignalStorage.SIGNED, signedPreKeyId)

    // ---- Kyber pre-keys ----------------------------------------------------

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord =
        KyberPreKeyRecord(storage.loadPreKey(SignalStorage.KYBER, kyberPreKeyId) ?: throw InvalidKeyIdException("no Kyber pre-key $kyberPreKeyId"))

    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> =
        storage.loadPreKeys(SignalStorage.KYBER).map(::KyberPreKeyRecord)

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) =
        storage.storePreKey(SignalStorage.KYBER, kyberPreKeyId, record.serialize())

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean =
        storage.loadPreKey(SignalStorage.KYBER, kyberPreKeyId) != null

    /**
     * Maun publishes one long-lived Kyber pre-key, so it is never deleted on
     * use. What must not happen is the same session start being accepted
     * twice; each use is remembered by the sender's one-off base key.
     */
    override fun markKyberPreKeyUsed(kyberPreKeyId: Int, signedPreKeyId: Int, baseKey: ECPublicKey) {
        if (!storage.recordKyberUse(kyberPreKeyId, signedPreKeyId, baseKey.serialize().toHex())) {
            throw ReusedBaseKeyException("this session start was already used")
        }
    }

    // ---- Group messaging: not part of Maun ---------------------------------

    override fun storeSenderKey(sender: SignalProtocolAddress, distributionId: UUID, record: SenderKeyRecord) =
        throw UnsupportedOperationException("group messaging is not supported")

    override fun loadSenderKey(sender: SignalProtocolAddress, distributionId: UUID): SenderKeyRecord? =
        throw UnsupportedOperationException("group messaging is not supported")

    companion object {
        /** The identity key a Maun ID stands for. Throws if [id] is not a valid key. */
        fun identityKeyOf(id: String): IdentityKey = IdentityKey(ECPublicKey.fromPublicKeyBytes(id.hexToBytes()))
    }
}

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd number of hex digits" }
    return ByteArray(length / 2) { i ->
        val high = Character.digit(this[i * 2], 16)
        val low = Character.digit(this[i * 2 + 1], 16)
        require(high >= 0 && low >= 0) { "not hex" }
        ((high shl 4) or low).toByte()
    }
}
