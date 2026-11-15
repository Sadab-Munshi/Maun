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

    /**
     * One step in setting up or ending a call. [type] is one of the constants
     * below: an offer and its answer carry [sdp], a candidate carries where
     * the caller can be reached through the call servers, and hanging up may
     * say [reason]. The call itself never passes through messages.
     */
    data class Call(
        val callId: String,
        val type: String,
        val sdp: String? = null,
        val candidate: Candidate? = null,
        val reason: String? = null,
        /** On an offer: this is a video call. */
        val video: Boolean = false,
    ) : Payload

    /** One way to reach a phone during a call, as WebRTC describes it. */
    data class Candidate(val mid: String, val index: Int, val sdp: String)

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

        const val CALL_OFFER = "offer"
        const val CALL_ANSWER = "answer"
        const val CALL_ICE = "ice"
        const val CALL_HANGUP = "hangup"
        const val CALL_BUSY = "busy"
        private val CALL_TYPES = setOf(CALL_OFFER, CALL_ANSWER, CALL_ICE, CALL_HANGUP, CALL_BUSY)

        /**
         * A voice call's description is about 2,000 characters, a video call's
         * 6,000 to 8,000. The relay takes 8,192 bytes a message, so a long one
         * travels packed (deflate), which makes it about a quarter the size.
         */
        const val MAX_SDP_CHARS = 20_000
        private const val SDP_PLAIN_CHARS = 3000

        private fun pack(text: String): String {
            val deflater = java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION)
            val out = java.io.ByteArrayOutputStream()
            java.util.zip.DeflaterOutputStream(out, deflater).use { it.write(text.toByteArray(Charsets.UTF_8)) }
            deflater.end()
            return java.util.Base64.getEncoder().encodeToString(out.toByteArray())
        }

        /** Unpacks a description. Null when it is not one, or would be longer than any description is. */
        private fun unpack(packed: String): String? = try {
            val inflater = java.util.zip.Inflater()
            inflater.setInput(java.util.Base64.getDecoder().decode(packed))
            val buffer = ByteArray(MAX_SDP_CHARS + 1)
            var length = 0
            while (!inflater.finished() && length < buffer.size) {
                val read = inflater.inflate(buffer, length, buffer.size - length)
                if (read == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                length += read
            }
            val complete = inflater.finished()
            inflater.end()
            if (complete && length in 1..MAX_SDP_CHARS) String(buffer, 0, length, Charsets.UTF_8) else null
        } catch (_: Exception) {
            null
        }
        private const val MAX_CANDIDATE_CHARS = 600

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
                is Call -> {
                    json.put("k", "call").put("call", payload.callId).put("type", payload.type)
                    // A video call's description is long and repetitive; packed, it fits in one message.
                    payload.sdp?.let { if (it.length > SDP_PLAIN_CHARS) json.put("sdpz", pack(it)) else json.put("sdp", it) }
                    if (payload.video) json.put("video", true)
                    payload.candidate?.let { json.put("mid", it.mid).put("idx", it.index).put("cand", it.sdp) }
                    payload.reason?.let { json.put("why", it) }
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
                "call" -> {
                    val callId = json.optString("call")
                    val type = json.optString("type")
                    if (!isMessageId(callId) || type !in CALL_TYPES) return Unknown
                    val sdp = json.optString("sdp").takeIf { it.isNotEmpty() && it.length <= MAX_SDP_CHARS }
                        ?: json.optString("sdpz").takeIf { it.isNotEmpty() }?.let(::unpack)
                    val candidate = json.optString("cand").takeIf { it.isNotEmpty() && it.length <= MAX_CANDIDATE_CHARS }
                        ?.let { Candidate(json.optString("mid").take(32), json.optInt("idx", 0).coerceIn(0, 64), it) }
                    // An offer or an answer without its description, or a candidate without one, says nothing.
                    if ((type == CALL_OFFER || type == CALL_ANSWER) && sdp == null) return Unknown
                    if (type == CALL_ICE && candidate == null) return Unknown
                    Call(callId, type, sdp, candidate, json.optString("why").take(40).ifEmpty { null }, video = json.optBoolean("video"))
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
