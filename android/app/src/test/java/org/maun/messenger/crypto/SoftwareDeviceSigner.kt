package org.maun.messenger.crypto

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/** Stands in for Android Keystore in JVM tests: same curve, same signature format. */
class SoftwareDeviceSigner : DeviceSigner {
    private var pair: KeyPair? = null

    /** Every challenge a key was created with, oldest first. */
    val challenges = mutableListOf<ByteArray>()

    override fun exists(): Boolean = pair != null

    override fun create(challenge: ByteArray) {
        challenges += challenge
        pair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
    }

    override fun publicKeySpki(): ByteArray = checkNotNull(pair).public.encoded

    override fun sign(message: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(checkNotNull(pair).private)
            update(message)
            sign()
        }
}
