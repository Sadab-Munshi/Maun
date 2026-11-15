package org.maun.messenger.crypto

/**
 * The login key that is bound to one phone. On a phone this is [DeviceKey]
 * (Android Keystore); tests use a software key.
 */
interface DeviceSigner {
    fun exists(): Boolean

    /**
     * Throws away any existing key and makes a new one. [challenge] comes from
     * the relay and is baked into the key so that the phone's hardware can
     * later prove the key was made for this account.
     */
    fun create(challenge: ByteArray)

    /** Public half in X.509 SubjectPublicKeyInfo form, as the relay expects it. */
    fun publicKeySpki(): ByteArray

    /** Signs with SHA256withECDSA. Output is ASN.1 DER. */
    fun sign(message: ByteArray): ByteArray
}
