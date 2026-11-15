package org.maun.messenger.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The secret that encrypts the local database.
 *
 * A random 32-byte key is generated on first launch. It is stored on disk only
 * after being encrypted ("wrapped") by an AES key that lives inside Android
 * Keystore, so copying the app's files off the phone yields nothing readable.
 */
object DatabaseKey {

    /** [created] is true when no key existed before this call. */
    class Loaded(val key: ByteArray, val created: Boolean)

    /** The wrapped key exists but Keystore can no longer unwrap it. */
    class LostException(cause: Throwable?) :
        IllegalStateException("The database key can no longer be unlocked on this phone", cause)

    private const val KEYSTORE = "AndroidKeyStore"
    private const val WRAP_ALIAS = "maun.dbwrap.v1"
    private const val FILE_NAME = "db.key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEY_BYTES = 32

    @Synchronized
    fun loadOrCreate(context: Context): Loaded {
        val file = File(context.noBackupFilesDir, FILE_NAME)
        if (file.exists()) return Loaded(unwrap(file.readBytes()), created = false)

        val key = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        // Write to a temporary file first so a crash cannot leave half a key.
        val temp = File(file.parentFile, "$FILE_NAME.tmp")
        temp.writeBytes(wrap(key))
        check(temp.renameTo(file)) { "could not save the database key" }
        return Loaded(key, created = true)
    }

    private fun wrap(key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrapKey(createIfMissing = true))
        return cipher.iv + cipher.doFinal(key)
    }

    private fun unwrap(stored: ByteArray): ByteArray {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val iv = GCMParameterSpec(TAG_BITS, stored, 0, IV_BYTES)
            cipher.init(Cipher.DECRYPT_MODE, wrapKey(createIfMissing = false), iv)
            return cipher.doFinal(stored, IV_BYTES, stored.size - IV_BYTES)
        } catch (e: Exception) {
            throw LostException(e)
        }
    }

    private fun wrapKey(createIfMissing: Boolean): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(WRAP_ALIAS, null) as? SecretKey)?.let { return it }
        check(createIfMissing) { "wrapping key missing from Keystore" }

        val spec = KeyGenParameterSpec.Builder(
            WRAP_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(spec)
            generateKey()
        }
    }
}
