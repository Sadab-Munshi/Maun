package org.maun.messenger.crypto

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * The key that binds an account to this physical phone.
 *
 * It is a P-256 key created inside Android Keystore and can never be exported:
 * the app can ask the secure hardware to sign with it, but cannot read it. The
 * relay learns only the public half and checks a fresh signature at each login.
 */
class DeviceKey(context: Context, private val alias: String = ACCOUNT_ALIAS) : DeviceSigner {

    enum class Protection {
        /** Dedicated tamper-resistant security chip. */
        STRONGBOX,

        /** Isolated area of the main processor (TEE). */
        TRUSTED_ENVIRONMENT,

        /** Secure hardware, but Android 10 and 11 do not say which kind. */
        SECURE_HARDWARE,

        /** Ordinary software keystore: an attacker with root could copy the key. */
        SOFTWARE,
        UNKNOWN,
    }

    private val hasStrongBox =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    override fun exists(): Boolean = keyStore().containsAlias(alias)

    fun delete() {
        keyStore().deleteEntry(alias)
    }

    /**
     * Makes a new key, replacing any old one. Tries the best protection first:
     * the StrongBox chip, then the processor's secure area, each with the
     * relay's challenge baked in. Some phones cannot attest keys at all; for
     * those the last attempt leaves the challenge out.
     */
    override fun create(challenge: ByteArray) = createKey(challenge)

    /** As [create]; a null [challenge] makes a key that cannot be attested. */
    @Synchronized
    fun createKey(challenge: ByteArray?) {
        delete()
        val attempts = buildList<Pair<Boolean, ByteArray?>> {
            if (hasStrongBox) add(true to challenge)
            add(false to challenge)
            if (challenge != null) add(false to null)
        }
        var lastError: Exception? = null
        for ((strongBox, attestation) in attempts) {
            try {
                generate(strongBox, attestation)
                return
            } catch (e: Exception) {
                // StrongBox advertised but unusable, or attestation unsupported
                // on this phone. A half-made entry must not be left behind.
                lastError = e
                runCatching { delete() }
            }
        }
        throw IllegalStateException("This phone could not create a device key", lastError)
    }

    private fun generate(strongBox: Boolean, challenge: ByteArray?) {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setIsStrongBoxBacked(strongBox)
            .apply { if (challenge != null) setAttestationChallenge(challenge) }
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE).run {
            initialize(spec)
            generateKeyPair()
        }
    }

    private fun privateKey(): PrivateKey =
        keyStore().getKey(alias, null) as? PrivateKey ?: error("The device key is missing from this phone")

    override fun publicKeySpki(): ByteArray =
        (keyStore().getCertificate(alias) ?: error("The device key is missing from this phone")).publicKey.encoded

    override fun sign(message: ByteArray): ByteArray =
        Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initSign(privateKey())
            update(message)
            sign()
        }

    /** Checks a signature against the public half, outside the secure hardware. */
    fun verify(message: ByteArray, signature: ByteArray): Boolean =
        Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initVerify(keyStore().getCertificate(alias).publicKey)
            update(message)
            verify(signature)
        }

    /** Where the private key actually lives on this phone. */
    fun protection(): Protection {
        val key = privateKey()
        val info = KeyFactory.getInstance(key.algorithm, KEYSTORE).getKeySpec(key, KeyInfo::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> Protection.STRONGBOX
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> Protection.TRUSTED_ENVIRONMENT
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> Protection.SOFTWARE
                else -> Protection.UNKNOWN
            }
        }
        @Suppress("DEPRECATION")
        return if (info.isInsideSecureHardware) Protection.SECURE_HARDWARE else Protection.SOFTWARE
    }

    companion object {
        /** The key the account logs in with. */
        const val ACCOUNT_ALIAS = "maun.device.v1"

        /** A throwaway key for the setup check, so the check never touches the account's key. */
        const val SELF_CHECK_ALIAS = "maun.selfcheck.v1"

        private const val KEYSTORE = "AndroidKeyStore"
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    }
}
