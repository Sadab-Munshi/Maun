package org.maun.messenger.crypto

/**
 * Where the encryption sessions and the private halves of the pre-keys are
 * kept. Every call blocks, and is made inside a database transaction, so a
 * session never moves forward without the message that moved it being saved.
 */
interface SignalStorage {
    fun loadSession(peer: String): ByteArray?

    fun storeSession(peer: String, record: ByteArray)

    fun deleteSession(peer: String)

    /** [kind] is one of the constants below. */
    fun loadPreKey(kind: Int, id: Int): ByteArray?

    fun loadPreKeys(kind: Int): List<ByteArray>

    fun storePreKey(kind: Int, id: Int, record: ByteArray)

    fun removePreKey(kind: Int, id: Int)

    /** The highest ID in use for [kind], or 0 when there is none. */
    fun highestPreKeyId(kind: Int): Int

    /**
     * Remembers that a Kyber pre-key was used together with [baseKey].
     * Returns false if exactly this use was recorded before: a replayed
     * session start.
     */
    fun recordKyberUse(kyberId: Int, signedId: Int, baseKey: String): Boolean

    companion object {
        const val ONE_TIME = 1
        const val SIGNED = 2
        const val KYBER = 3
    }
}
