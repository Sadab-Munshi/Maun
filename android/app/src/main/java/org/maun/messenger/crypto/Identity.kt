package org.maun.messenger.crypto

import org.signal.libsignal.protocol.IdentityKeyPair

/**
 * A user's long-term identity: a Curve25519 key pair made on this phone.
 *
 * The public half is the user's ID and their address on the relay. The private
 * half never leaves the encrypted database. The same key pair later becomes
 * the identity key of every Signal session.
 */
class Identity private constructor(val keyPair: IdentityKeyPair) {

    /** Raw 32-byte public key, without libsignal's type byte. */
    val publicKey: ByteArray = keyPair.publicKey.publicKey.publicKeyBytes

    /** The public key in lower-case hex: the user's ID and mailbox address. */
    val id: String = publicKey.toHex()

    /** Signs with libsignal's XEdDSA scheme; the relay checks it at registration. */
    fun sign(message: ByteArray): ByteArray = keyPair.privateKey.calculateSignature(message)

    fun serialize(): ByteArray = keyPair.serialize()

    companion object {
        fun generate(): Identity = Identity(IdentityKeyPair.generate())

        fun restore(serialized: ByteArray): Identity = Identity(IdentityKeyPair(serialized))
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
