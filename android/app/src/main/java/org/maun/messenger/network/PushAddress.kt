package org.maun.messenger.network

import java.net.URI
import java.util.Base64
import org.json.JSONException
import org.json.JSONObject

/**
 * Where the relay can send this phone a wake-up: a Web Push address given out
 * by the push app on the phone, and the two keys wake-ups must be encrypted
 * with. [key] and [auth] are standard base64, as the relay expects them.
 */
data class PushAddress(val url: String, val key: String, val auth: String) {

    /** One line of text, for storage. */
    fun serialize(): String = JSONObject().put("url", url).put("key", key).put("auth", auth).toString()

    companion object {
        const val KEY_BYTES = 65
        const val AUTH_BYTES = 16
        const val MAX_URL_LENGTH = 1024

        fun parse(text: String?): PushAddress? {
            if (text.isNullOrEmpty()) return null
            return try {
                val json = JSONObject(text)
                PushAddress(json.getString("url"), json.getString("key"), json.getString("auth"))
            } catch (_: JSONException) {
                null
            }
        }

        /**
         * Builds an address from what a push app hands out: the URL, and the
         * two keys in URL-safe base64. Returns null when any part is not what
         * Web Push prescribes; the relay would refuse it anyway.
         */
        fun fromWebPush(url: String, p256dh: String, auth: String): PushAddress? {
            if (url.length > MAX_URL_LENGTH) return null
            val parsed = try {
                URI(url)
            } catch (_: Exception) {
                return null
            }
            if (!parsed.scheme.equals("https", ignoreCase = true) || parsed.host.isNullOrEmpty()) return null
            val keyBytes = decodeUrlSafe(p256dh) ?: return null
            val authBytes = decodeUrlSafe(auth) ?: return null
            // An uncompressed point on the P-256 curve, and a 16-byte secret.
            if (keyBytes.size != KEY_BYTES || keyBytes[0] != 4.toByte() || authBytes.size != AUTH_BYTES) return null
            val encoder = Base64.getEncoder()
            return PushAddress(url, encoder.encodeToString(keyBytes), encoder.encodeToString(authBytes))
        }

        /**
         * Turns the relay's signing key, as the relay sends it, into the form
         * push apps expect (VAPID: 87 characters of URL-safe base64). Returns
         * null for anything that is not such a key.
         */
        fun keyForPushApp(relayKey: String?): String? {
            if (relayKey.isNullOrEmpty()) return null
            val bytes = try {
                Base64.getDecoder().decode(relayKey)
            } catch (_: IllegalArgumentException) {
                return null
            }
            if (bytes.size != KEY_BYTES || bytes[0] != 4.toByte()) return null
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        private fun decodeUrlSafe(text: String): ByteArray? = try {
            Base64.getUrlDecoder().decode(text.trimEnd('='))
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/** What this phone knows about its wake-up address. Kept in the app's storage. */
interface PushState {
    /** The address the relay should use, or null when the phone has none. */
    suspend fun wanted(): PushAddress?

    /** The address the relay was last given, or null. */
    suspend fun uploaded(): PushAddress?

    suspend fun setUploaded(address: PushAddress?)
}
