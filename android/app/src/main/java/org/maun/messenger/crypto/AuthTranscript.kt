package org.maun.messenger.crypto

/**
 * The exact bytes that get signed to create an account and to log in.
 * Must match registerTranscript and loginTranscript in backend/src/protocol.ts.
 */
object AuthTranscript {
    const val NONCE_BYTES = 32
    const val IDENTITY_BYTES = 32

    private val REGISTER_LABEL = "maun:register:v1\n".toByteArray(Charsets.US_ASCII)
    private val LOGIN_LABEL = "maun:login:v1\n".toByteArray(Charsets.US_ASCII)

    /**
     * Signed by both the identity key and the device key. It ties this
     * identity, this device key and this connection's nonce together.
     */
    fun register(identity: ByteArray, nonce: ByteArray, deviceKey: ByteArray): ByteArray {
        checkSizes(identity, nonce)
        return REGISTER_LABEL + identity + nonce + deviceKey
    }

    /** Signed by the device key on every connection. */
    fun login(identity: ByteArray, nonce: ByteArray): ByteArray {
        checkSizes(identity, nonce)
        return LOGIN_LABEL + identity + nonce
    }

    private fun checkSizes(identity: ByteArray, nonce: ByteArray) {
        require(identity.size == IDENTITY_BYTES) { "identity key must be $IDENTITY_BYTES bytes" }
        require(nonce.size == NONCE_BYTES) { "nonce must be $NONCE_BYTES bytes" }
    }
}
