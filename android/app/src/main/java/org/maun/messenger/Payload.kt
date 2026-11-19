package org.maun.messenger

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * What travels inside the encryption: a chat message, or a receipt for
 * earlier ones. The relay sees neither, and cannot tell the two apart.
 */
sealed interface Payload {

    /**
     * [id] is chosen by the sender and names the message in later receipts.
     * [replyTo] is the ID of an earlier message of the conversation this one
     * answers, when it answers one: the quoted part is drawn from the reader's
     * own copy, so a reply carries no more than that ID.
     */
    data class Text(
        val id: String,
        val sentAt: Long,
        val text: String,
        val replyTo: String? = null,
    ) : Payload

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

    /**
     * An emoji put on one of the other phone's messages, or taken off again.
     *
     * [of] names the message it is about, [emoji] is the character and an
     * empty one takes the last off; [sentAt] is when the phone that chose it
     * did so, which is what settles which of two answers is the later one when
     * they arrive out of order. Only emoji from [REACTIONS] travel: a reaction
     * is not a way to send text the reader is not expecting.
     */
    data class Reaction(val of: String, val emoji: String, val sentAt: Long) : Payload

    /**
     * A profile picture, said to somebody one talks to: its [version], the
     * [key] that opens it (base64, a key of its own), and its [size] before
     * sealing. The picture itself sits on the relay under the sender's ID,
     * sealed, and only this key opens it; the key never leaves an encrypted
     * message.
     *
     * An empty [version] is not a picture but a question: "what picture do
     * you have?". [NONE] says the sender has taken their picture down.
     */
    data class Avatar(val version: String, val key: String = "", val size: Long = 0L) : Payload

    /**
     * A live-location share, and the two things said about one later.
     *
     * [share] is the ID the relay knows it by, [key] the key that opens its
     * sealed points — the only copy of that key outside the phone that made
     * it is here, inside an encrypted message. [until] is the minute it ends,
     * counted by the relay, and [minutes] how long it was started for.
     * An empty [key] comes with [LIVE_SEEN] and [LIVE_STOP], which say nothing
     * new and only move a card from one line to the next.
     */
    data class LiveLocation(
        val id: String,
        val sentAt: Long,
        val share: String,
        val type: String,
        val key: String = "",
        val minutes: Int = 15,
        val until: Long = 0L,
    ) : Payload

    /**
     * Where the sending phone was, once: no share, no key, no relay row.
     *
     * It is an ordinary message — the place travels inside the same encryption
     * as a sentence would — so there is nothing to end, nothing to expire, and
     * nothing that keeps asking where anybody is.
     */
    data class Pin(val id: String, val sentAt: Long, val place: Place) : Payload

    /** Something a newer version of Maun sent. It is dropped without complaint. */
    data object Unknown : Payload

    companion object {
        private const val VERSION = 1

        /** Messages are padded to a multiple of this, so their length says little about their text. */
        private const val BLOCK = 160

        /** The longest text one message may carry, in UTF-8 bytes. */
        const val MAX_TEXT_BYTES = 4096

        const val MAX_RECEIPT_IDS = 50

        /** Told to somebody who asks: this phone has no picture to show. */
        /**
         * The emoji a reaction may carry, in the order the bar shows them. The
         * list is fixed so that a reaction is a mark, not free text, and so
         * that an older Maun can tell whether it knows the character.
         */
        val REACTIONS = listOf("\uD83D\uDC4D", "\u2764\uFE0F", "\uD83D\uDE02", "\uD83D\uDE22", "\uD83D\uDE4F")

        /**
         * Everything this version accepts from a message. The bar offers the
         * five in [REACTIONS]; the rest are here so that a phone that was sent
         * one by a newer Maun can still show it rather than drop it.
         */
        val MORE_REACTIONS = REACTIONS + listOf(
            "\uD83D\uDC4F", "\uD83D\uDD25", "\uD83C\uDF89", "\uD83D\uDE0D", "\uD83D\uDE0E", "\uD83D\uDC4C",
            "\u270C\uFE0F", "\uD83E\uDD1D", "\uD83D\uDE4C", "\uD83D\uDCAF", "\uD83D\uDCA9", "\uD83E\uDD14",
        )

        /** A share has started, the person watching has opened it, or it has ended. */
        const val LIVE_SHARE = "share"
        const val LIVE_SEEN = "seen"
        const val LIVE_STOP = "stop"
        private val LIVE_TYPES = setOf(LIVE_SHARE, LIVE_SEEN, LIVE_STOP)

        /** A share's ID: 16 random bytes in lower-case hex, as the relay takes it. */
        private val SHARE = Regex("^[0-9a-f]{32}$")

        /** The key that opens a point: 32 random bytes, base64. */
        private val LIVE_KEY = Regex("^[A-Za-z0-9+/]{43}=$")

        const val AVATAR_NONE = "none"

        /** The question "which picture do you have?" carries no version. */
        const val AVATAR_ASK = ""

        /** A picture's version: 16 random bytes in lower-case hex, made by the phone that owns it. */
        private val AVATAR_VERSION = Regex("^[0-9a-f]{16}$")

        /** The most a picture can be, as the relay takes it. */
        private const val MAX_AVATAR_BYTES = 256L * 1024L

        fun isAvatarVersion(value: String): Boolean = AVATAR_VERSION.matches(value)

        const val CALL_OFFER = "offer"
        const val CALL_ANSWER = "answer"
        const val CALL_ICE = "ice"
        const val CALL_HANGUP = "hangup"
        const val CALL_BUSY = "busy"

        /** During a call: this phone's camera was turned on (reason "on") or off ("off"). */
        const val CALL_VIDEO = "video"
        private val CALL_TYPES = setOf(CALL_OFFER, CALL_ANSWER, CALL_ICE, CALL_HANGUP, CALL_BUSY, CALL_VIDEO)

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
                is Text -> {
                    json.put("k", "text").put("id", payload.id).put("at", payload.sentAt).put("text", payload.text)
                    payload.replyTo?.let { json.put("re", it) }
                }
                is File -> payload.file.toWire(json.put("k", "file").put("id", payload.id).put("at", payload.sentAt))
                is Avatar -> {
                    json.put("k", "avatar").put("ver", payload.version)
                    if (payload.key.isNotEmpty()) json.put("key", payload.key)
                    if (payload.size > 0L) json.put("size", payload.size)
                }
                is Reaction -> json.put("k", "reaction").put("of", payload.of).put("emoji", payload.emoji).put("at", payload.sentAt)
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
                is LiveLocation -> {
                    json.put("k", "place").put("id", payload.id).put("at", payload.sentAt)
                        .put("share", payload.share).put("type", payload.type)
                    if (payload.key.isNotEmpty()) json.put("key", payload.key)
                    if (payload.type == LIVE_SHARE) json.put("mins", payload.minutes).put("until", payload.until)
                }
                is Pin -> json.put("k", "pin").put("id", payload.id).put("at", payload.sentAt)
                    .put("la", payload.place.latitude).put("lo", payload.place.longitude)
                    .put("ac", payload.place.accuracy).put("pat", payload.place.at)
                    // The name of the place, worked out once by the phone that
                    // was there: inside the encryption, so the phone that reads
                    // it never has to ask anybody where anybody was.
                    .apply { if (payload.place.label.isNotEmpty()) put("lbl", payload.place.label) }
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
                    // A reply to something whose ID makes no sense is still a
                    // message: it arrives, without the quote above it.
                    val replyTo = json.optString("re").takeIf { isMessageId(it) }
                    Text(id, json.optLong("at"), text, replyTo)
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
                "reaction" -> {
                    val of = json.optString("of")
                    val emoji = json.optString("emoji")
                    // Only the characters this version knows; anything else is
                    // from a newer Maun and is dropped without complaint.
                    if (!isMessageId(of) || (emoji.isNotEmpty() && emoji !in MORE_REACTIONS)) return Unknown
                    if (!json.has("at")) return Unknown
                    Reaction(of, emoji, json.optLong("at"))
                }
                "place" -> {
                    val id = json.optString("id")
                    val share = json.optString("share")
                    val type = json.optString("type")
                    if (!isMessageId(id) || !SHARE.matches(share) || type !in LIVE_TYPES) return Unknown
                    val key = json.optString("key")
                    val minutes = json.optInt("mins", 0)
                    val until = json.optLong("until", 0L)
                    if (type == LIVE_SHARE) {
                        // A share without the key that opens it says nothing;
                        // one for longer than the relay allows is not one.
                        if (!LIVE_KEY.matches(key)) return Unknown
                        if (minutes !in 15..(24 * 60) || until <= 0L) return Unknown
                    }
                    if (!json.has("at")) return Unknown
                    LiveLocation(id, json.optLong("at"), share, type, if (type == LIVE_SHARE) key else "", minutes, until)
                }
                "pin" -> {
                    val id = json.optString("id")
                    if (!isMessageId(id) || !json.has("at")) return Unknown
                    // A place is four numbers; anything else is not one, and a
                    // message that has lost its place says nothing at all.
                    val place = Place.of(
                        json.optDouble("la", Double.NaN),
                        json.optDouble("lo", Double.NaN),
                        json.optDouble("ac", 0.0),
                        json.optLong("pat", 0L),
                        json.optString("lbl"),
                    ) ?: return Unknown
                    Pin(id, json.optLong("at"), place)
                }
                "receipt" -> Receipt(ids(json.optJSONArray("delivered")), ids(json.optJSONArray("read")))
                "avatar" -> {
                    val version = json.optString("ver")
                    if (version != AVATAR_NONE && version != AVATAR_ASK && !isAvatarVersion(version)) return Unknown
                    val key = json.optString("key")
                    val size = json.optLong("size", 0L)
                    // A picture with nothing to open, or one too large to have come this way, says nothing.
                    if (version != AVATAR_NONE && version != AVATAR_ASK && (key.isEmpty() || size !in 1..MAX_AVATAR_BYTES)) {
                        return Unknown
                    }
                    Avatar(version, key, size)
                }
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
