package org.maun.messenger

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * What travels inside the encryption: a chat message, or a receipt for
 * earlier ones. The relay sees neither, and cannot tell the two apart.
 */
sealed interface Payload {

    /** [id] is chosen by the sender and names the message in later receipts. */
    data class Text(val id: String, val sentAt: Long, val text: String) : Payload

    /** IDs of the receiver's messages that have arrived on, or been read on, the sender's phone. */
    /** A file: where its encrypted form is on the relay, and the key that opens it. */
    data class File(val id: String, val sentAt: Long, val file: Attachment) : Payload

    data class Receipt(val delivered: List<String> = emptyList(), val read: List<String> = emptyList()) : Payload

    /** Something a newer version of Maun sent. It is dropped without complaint. */
    data object Unknown : Payload

    companion object {
        private const val VERSION = 1

        /** Messages are padded to a multiple of this, so their length says little about their text. */
        private const val BLOCK = 160

        /** The longest text one message may carry, in UTF-8 bytes. */
        const val MAX_TEXT_BYTES = 4096

        const val MAX_RECEIPT_IDS = 50

        private val ID = Regex("^[A-Za-z0-9_-]{1,64}$")

        fun isMessageId(value: String): Boolean = ID.matches(value)

        fun encode(payload: Payload): ByteArray {
            val json = JSONObject().put("v", VERSION)
            when (payload) {
                is Text -> json.put("k", "text").put("id", payload.id).put("at", payload.sentAt).put("text", payload.text)
                is File -> payload.file.toWire(json.put("k", "file").put("id", payload.id).put("at", payload.sentAt))
                is Receipt -> {
                    json.put("k", "receipt")
                    if (payload.delivered.isNotEmpty()) json.put("delivered", JSONArray(payload.delivered))
                    if (payload.read.isNotEmpty()) json.put("read", JSONArray(payload.read))
                }
                Unknown -> throw IllegalArgumentException("nothing to encode")
            }
            return pad(json.toString().toByteArray(Charsets.UTF_8))
        }

        /** Never throws: anything unreadable is [Unknown]. */
        fun decode(padded: ByteArray): Payload {
            val bytes = unpad(padded) ?: return Unknown
            val json = try {
                JSONObject(String(bytes, Charsets.UTF_8))
            } catch (_: JSONException) {
                return Unknown
            }
            return when (json.optString("k")) {
                "text" -> {
                    val id = json.optString("id")
                    val text = json.optString("text")
                    if (!isMessageId(id) || text.isEmpty() || !json.has("at")) return Unknown
                    Text(id, json.optLong("at"), text)
                }
                "file" -> {
                    val id = json.optString("id")
                    val file = Attachment.fromWire(json)
                    if (!isMessageId(id) || file == null || !json.has("at")) return Unknown
                    File(id, json.optLong("at"), file)
                }
                "receipt" -> Receipt(ids(json.optJSONArray("delivered")), ids(json.optJSONArray("read")))
                else -> Unknown
            }
        }

        private fun ids(array: JSONArray?): List<String> {
            if (array == null) return emptyList()
            val out = ArrayList<String>()
            for (i in 0 until minOf(array.length(), MAX_RECEIPT_IDS)) {
                val id = array.optString(i)
                if (isMessageId(id)) out += id
            }
            return out
        }

        /** Appends a 0x80 marker and zeros up to the next multiple of [BLOCK]. */
        internal fun pad(bytes: ByteArray): ByteArray {
            val padded = ByteArray((bytes.size / BLOCK + 1) * BLOCK)
            bytes.copyInto(padded)
            padded[bytes.size] = 0x80.toByte()
            return padded
        }

        internal fun unpad(padded: ByteArray): ByteArray? {
            var end = padded.size - 1
            while (end >= 0 && padded[end] == 0.toByte()) end--
            if (end < 0 || padded[end] != 0x80.toByte()) return null
            return padded.copyOf(end)
        }
    }
}
