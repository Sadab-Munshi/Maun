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
import java.security.ProviderException
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * The key that binds an account to this physical phone.
 *
 * It is a P-256 key created inside Android Keystore and can never be exported:
 * the app can ask the secure hardware to sign with it, but cannot read it. The
 * relay learns only the public half and checks a fresh signature at each login.
 */
object DeviceKey {

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

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "maun.device.v1"
    private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    /** Creates the key on first use. Prefers the StrongBox chip when the phone has one. */
    @Synchronized
    fun ensure(context: Context) {
        if (keyStore().containsAlias(ALIAS)) return
        val hasStrongBox = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        if (hasStrongBox) {
            try {
                generate(strongBox = true)
                return
            } catch (_: ProviderException) {
                // Advertised but unusable on this device (StrongBoxUnavailableException
                // or a vendor-specific failure); fall back to the TEE.
            }
        }
        generate(strongBox = false)
    }

    private fun generate(strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setIsStrongBoxBacked(strongBox)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE).run {
            initialize(spec)
            generateKeyPair()
        }
    }

    private fun privateKey(): PrivateKey =
        keyStore().getKey(ALIAS, null) as? PrivateKey ?: error("device key missing; call ensure() first")

    /** Public half in X.509 SubjectPublicKeyInfo form, as the relay expects it. */
    fun publicKeySpki(): ByteArray =
        (keyStore().getCertificate(ALIAS) ?: error("device key missing; call ensure() first")).publicKey.encoded

    /** Signs with SHA256withECDSA. Output is ASN.1 DER. */
    fun sign(message: ByteArray): ByteArray =
        Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initSign(privateKey())
            update(message)
            sign()
        }

    /** Checks a signature against the public half, outside the secure hardware. */
    fun verify(message: ByteArray, signature: ByteArray): Boolean =
        Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initVerify(keyStore().getCertificate(ALIAS).publicKey)
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
}
