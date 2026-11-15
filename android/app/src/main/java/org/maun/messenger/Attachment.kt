package org.maun.messenger

import java.util.Base64
import org.json.JSONException
import org.json.JSONObject
import org.maun.messenger.crypto.FileCipher

/**
 * A file sent in a conversation.
 *
 * What travels (inside the encrypted message) is where the encrypted file is
 * on the relay and the key that opens it, with its name, type and size. On
 * this phone the same record, plus [state], is the text of the message row.
 */
data class Attachment(
    /** The name the relay stored the encrypted file under. Empty until it is uploaded. */
    val key: String,
    /** The file's own AES key, in standard base64. */
    val secret: String,
    val name: String,
    val mime: String,
    /** Size of the file itself, in bytes. */
    val size: Long,
    /** Where the file is, as far as this phone is concerned. Never sent. */
    val state: Int = REMOTE,
    /** A picture's size in pixels, the way it is shown; 0 when not known. Lets the screen keep room for it. */
    val width: Int = 0,
    val height: Int = 0,
    /** How long a voice message is, in milliseconds; 0 for anything else. */
    val duration: Long = 0,
) {
    val isImage: Boolean get() = mime in IMAGE_TYPES

    /** A voice message: recorded in the conversation, and played there. */
    val isVoice: Boolean get() = duration > 0 && mime.startsWith("audio/")

    fun secretBytes(): ByteArray = Base64.getDecoder().decode(secret)

    /** The record that is sent to the other person. */
    fun toWire(json: JSONObject = JSONObject()): JSONObject {
        json.put("key", key).put("secret", secret).put("name", name).put("mime", mime).put("size", size)
        if (width > 0 && height > 0) json.put("w", width).put("h", height)
        if (duration > 0) json.put("dur", duration)
        return json
    }

    /** The record kept in the message row. */
    fun toLocal(): String = toWire().put("state", state).toString()

    companion object {
        /** Largest file that can be sent. The relay refuses more. */
        const val MAX_BYTES = 25_000_000L

        /** The longest voice message that can be recorded. */
        const val MAX_VOICE_MILLIS = 5 * 60 * 1000L
        const val VOICE_MIME = "audio/ogg"

        /** On the relay; not fetched yet. */
        const val REMOTE = 0
        /** Being fetched. */
        const val DOWNLOADING = 1
        /** On this phone. */
        const val READY = 2
        /** Being sent to the relay. */
        const val UPLOADING = 3
        /** The last attempt to send or fetch it failed. */
        const val FAILED = 4
        /** The relay no longer has it: a file is kept for one day. */
        const val GONE = 5

        private val IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/gif")
        private val KEY = Regex("^[0-9a-f]{64}$")
        private const val MAX_NAME_CHARS = 120

        private fun side(json: JSONObject, name: String): Int = json.optInt(name, 0).coerceIn(0, 50_000)

        /** A file name that is safe to show and to suggest when saving. */
        fun cleanName(name: String): String {
            val cleaned = name.substringAfterLast('/').substringAfterLast('\\')
                .filter { it >= ' ' && it != '\u007f' }
                .trim()
                .take(MAX_NAME_CHARS)
            return cleaned.ifEmpty { "file" }
        }

        /** Reads what the other person sent. Null when it is not a usable record. */
        fun fromWire(json: JSONObject): Attachment? {
            val key = json.optString("key")
            val secret = json.optString("secret")
            val size = json.optLong("size", -1)
            if (!KEY.matches(key) || size < 0 || size > MAX_BYTES) return null
            val secretOk = try {
                Base64.getDecoder().decode(secret).size == FileCipher.KEY_BYTES
            } catch (_: IllegalArgumentException) {
                false
            }
            if (!secretOk) return null
            val mime = json.optString("mime").lowercase().take(100).ifEmpty { "application/octet-stream" }
            return Attachment(
                key, secret, cleanName(json.optString("name")), mime, size, REMOTE, side(json, "w"), side(json, "h"),
                duration = json.optLong("dur", 0).coerceIn(0, MAX_VOICE_MILLIS),
            )
        }

        /** Reads the record kept in a message row. */
        fun fromLocal(text: String): Attachment? = try {
            val json = JSONObject(text)
            Attachment(
                key = json.optString("key"),
                secret = json.optString("secret"),
                name = json.optString("name"),
                mime = json.optString("mime"),
                size = json.optLong("size"),
                state = json.optInt("state", REMOTE),
                width = side(json, "w"),
                height = side(json, "h"),
                duration = json.optLong("dur", 0),
            )
        } catch (_: JSONException) {
            null
        }
    }
}
