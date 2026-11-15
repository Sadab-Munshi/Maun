package org.maun.messenger.crypto

import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord

/**
 * Generates the pre-keys a user publishes so that someone else can start an
 * encrypted session with them while they are offline.
 *
 * libsignal sets sessions up with PQXDH, so a Kyber pre-key is required next to
 * the classic signed pre-key; one-time pre-keys are optional extras.
 */
object PreKeys {

    fun signedPreKey(identity: IdentityKeyPair, id: Int, nowMillis: Long): SignedPreKeyRecord {
        val pair = ECKeyPair.generate()
        val signature = identity.privateKey.calculateSignature(pair.publicKey.serialize())
        return SignedPreKeyRecord(id, nowMillis, pair, signature)
    }

    fun kyberPreKey(identity: IdentityKeyPair, id: Int, nowMillis: Long): KyberPreKeyRecord {
        val pair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val signature = identity.privateKey.calculateSignature(pair.publicKey.serialize())
        return KyberPreKeyRecord(id, nowMillis, pair, signature)
    }

    fun oneTimePreKeys(firstId: Int, count: Int): List<PreKeyRecord> =
        List(count) { offset -> PreKeyRecord(firstId + offset, ECKeyPair.generate()) }
}
