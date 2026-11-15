package org.maun.messenger.crypto

import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator

/**
 * A number two people can read to each other to check that nobody sits
 * between them. It is worked out from both IDs, so both phones show the same
 * sixty digits, and any other pair of IDs gives a different number.
 *
 * In Maun an ID is the person's identity key, so this checks nothing that
 * comparing the two IDs letter by letter would not. It is only much easier.
 */
object SafetyNumber {
    /** The work factor Signal uses for the same purpose. */
    private const val ITERATIONS = 5200
    private const val VERSION = 2

    /** Sixty digits, or null when either ID is not a valid key. */
    fun of(ownId: String, theirId: String): String? {
        if (!ContactCode.isId(ownId) || !ContactCode.isId(theirId)) return null
        return try {
            val own = fromHex(ownId)
            val theirs = fromHex(theirId)
            NumericFingerprintGenerator(ITERATIONS)
                .createFor(VERSION, own, key(own), theirs, key(theirs))
                .displayableFingerprint
                .displayText
        } catch (_: Exception) {
            null
        }
    }

    /** Twelve groups of five digits, four groups to a line. */
    fun grouped(number: String): String =
        number.chunked(5).chunked(4).joinToString("\n") { it.joinToString(" ") }

    private fun key(raw: ByteArray) = IdentityKey(ECPublicKey.fromPublicKeyBytes(raw))

    private fun fromHex(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
