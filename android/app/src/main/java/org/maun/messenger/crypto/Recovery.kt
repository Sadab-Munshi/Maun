package org.maun.messenger.crypto

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONException
import org.json.JSONObject

/**
 * The sealed copy of an account that makes a lost phone survivable.
 *
 * What leaves the phone is ciphertext: the identity key pair, the registration
 * ID it was published with, and the record that opens this account's profile
 * picture on the relay. The key to it is derived here, on the phone, from a
 * passphrase the person chose. The relay is never told the passphrase, never
 * sees a key, and cannot make one: it stores [Sealed.blob] and hands it back to
 * whoever proves the email address on the account.
 *
 * A wrong passphrase cannot be detected by anybody but the phone: it fails the
 * GCM tag, which is the same failure as a blob that was tampered with. Both are
 * reported as [WrongPassphrase], because to the person they are the same
 * problem, and neither tells an attacker anything they did not already know.
 *
 * This is not chat history. It restores the account — the ID, its safety
 * number, its picture — and nothing that was said on the old phone.
 */
object Recovery {

    /** What this build writes. A newer one may raise the iteration count. */
    const val KDF = "pbkdf2-sha256-600000"

    /**
     * The shortest passphrase accepted. Six digits' worth of guessing per
     * second is what a weak one costs: the blob is on a server, so anybody who
     * ever gets a copy can attack it offline as long as they like.
     */
    const val MIN_PASSPHRASE = 12

    private const val ITERATIONS = 600_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val AAD = "maun.identity.v1"
    private const val VERSION = 1

    /** The passphrase does not open this blob, or the blob is not one. */
    class WrongPassphrase : Exception("That passphrase does not open this backup")

    /** It opened, but it belongs to a different account. */
    class NotMine : Exception("That backup belongs to a different Maun ID")

    /** What a backup holds. [avatar] is the sealed picture record, or "". */
    data class Contents(
        val maunId: String,
        val identity: ByteArray,
        val registrationId: Int,
        val avatar: String,
    )

    /** The ciphertext, and the salt the key was derived with. */
    class Sealed(val salt: ByteArray, val kdf: String, val blob: ByteArray)

    /** Whether a passphrase is long enough to be worth deriving a key from. */
    fun isStrongEnough(passphrase: CharArray): Boolean = passphrase.size >= MIN_PASSPHRASE

    fun seal(passphrase: CharArray, contents: Contents): Sealed {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val plain = JSONObject()
            .put("v", VERSION)
            .put("id", contents.maunId)
            .put("identity", b64(contents.identity))
            .put("regId", contents.registrationId)
            .put("avatar", contents.avatar)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(passphrase, salt), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
        return Sealed(salt, KDF, iv + cipher.doFinal(plain))
    }

    /**
     * Opens a backup and checks it is the one for [expectedId], which the relay
     * named for this email address. A blob that opens but belongs to another
     * account is refused rather than installed: it would put this phone on
     * somebody else's identity.
     */
    fun open(passphrase: CharArray, salt: ByteArray, blob: ByteArray, expectedId: String): Contents {
        if (blob.size <= IV_BYTES) throw WrongPassphrase()
        val iv = blob.copyOfRange(0, IV_BYTES)
        val body = blob.copyOfRange(IV_BYTES, blob.size)
        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(passphrase, salt), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (_: Exception) {
            throw WrongPassphrase()
        }
        val json = try {
            JSONObject(plain)
        } catch (_: JSONException) {
            throw WrongPassphrase()
        }
        if (json.optInt("v") != VERSION) throw WrongPassphrase()
        val id = json.optString("id")
        val identity = try {
            fromB64(json.optString("identity"))
        } catch (_: IllegalArgumentException) {
            throw WrongPassphrase()
        }
        if (identity.isEmpty()) throw WrongPassphrase()
        if (id != expectedId) throw NotMine()
        return Contents(
            maunId = id,
            identity = identity,
            registrationId = json.optInt("regId"),
            avatar = json.optString("avatar"),
        )
    }

    private fun key(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, ITERATIONS, KEY_BITS)
        return try {
            SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun fromB64(text: String): ByteArray = Base64.getDecoder().decode(text)
}
