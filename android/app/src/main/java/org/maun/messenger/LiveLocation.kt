package org.maun.messenger

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONException
import org.json.JSONObject

/**
 * One point of a live-location share: where this phone was, how well it knew,
 * and when.
 *
 * Nothing of this ever reaches the relay in the clear. It is sealed with the
 * share's own key first, and that key only ever travels inside an encrypted
 * message ([Payload.LiveLocation]), so the relay holds a few dozen bytes it
 * cannot read.
 */
data class LivePoint(val lat: Double, val lon: Double, val accuracy: Double, val at: Long) {

    fun toJson(): String = JSONObject()
        .put("la", lat)
        .put("lo", lon)
        .put("ac", accuracy)
        .put("at", at)
        .toString()

    companion object {
        /** The most accurate a phone can be, in metres; anything else is a reading gone wrong. */
        private const val MAX_ACCURACY = 100_000.0

        fun fromJson(text: String): LivePoint? = try {
            from(JSONObject(text))
        } catch (_: JSONException) {
            null
        }

        fun from(json: JSONObject): LivePoint? {
            val lat = json.optDouble("la", Double.NaN)
            val lon = json.optDouble("lo", Double.NaN)
            val at = json.optLong("at", 0L)
            if (!lat.isFinite() || !lon.isFinite() || at <= 0L) return null
            if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) return null
            val accuracy = json.optDouble("ac", 0.0).takeIf { it.isFinite() && it in 0.0..MAX_ACCURACY } ?: 0.0
            return LivePoint(lat, lon, accuracy, at)
        }
    }
}

/**
 * A place this phone was told about once, and never again.
 *
 * The two ways to send where you are differ in one thing: a share keeps
 * saying where the phone is until its time runs out, and this says it once.
 * A place is therefore an ordinary message — sealed inside the same
 * encryption as the words around it, kept by nobody else, and never asked
 * after: the relay has no row for it, no service runs on the phone that sent
 * it, and nothing polls for it on the phone that receives it.
 *
 * [at] is when the fix was taken on the sending phone, so a card can say how
 * old the place is. Nobody may be there any more; the card says so.
 */
data class Place(val latitude: Double, val longitude: Double, val accuracy: Double, val at: Long) {

    fun point(): LivePoint = LivePoint(latitude, longitude, accuracy, at)

    fun toLocal(): String = JSONObject()
        .put("lat", latitude)
        .put("lon", longitude)
        .put("ac", accuracy)
        .put("at", at)
        .toString()

    companion object {
        fun fromLocal(text: String): Place? = try {
            from(JSONObject(text))
        } catch (_: JSONException) {
            null
        }

        /** A place out of a message that carries one. Null for anything that is not one. */
        fun from(json: JSONObject): Place? = of(
            json.optDouble("lat", Double.NaN),
            json.optDouble("lon", Double.NaN),
            json.optDouble("ac", 0.0),
            json.optLong("at", 0L),
        )

        /** A place from its four numbers, or null when they are not a place on this earth. */
        fun of(latitude: Double, longitude: Double, accuracy: Double, at: Long): Place? {
            if (!latitude.isFinite() || !longitude.isFinite() || at <= 0L) return null
            if (latitude < -90.0 || latitude > 90.0 || longitude < -180.0 || longitude > 180.0) return null
            val known = accuracy.takeIf { it.isFinite() && it in 0.0..100_000.0 } ?: 0.0
            return Place(latitude, longitude, known, at)
        }
    }
}

/**
 * How far apart two points are, in metres, on the sphere the earth very nearly
 * is — the great-circle distance, which is what "how far away is he" means for
 * distances of a street or a district.
 *
 * This is arithmetic on the phone that asks. A person watching a share who
 * lets Maun use their own location learns how far away the other phone is, and
 * the phone that shares is told nothing at all: no second share, no second
 * trail, and nothing on the relay.
 */
fun metresBetween(from: LivePoint, to: LivePoint): Double {
    val earth = 6_371_008.8
    val lat1 = Math.toRadians(from.lat)
    val lat2 = Math.toRadians(to.lat)
    val dLat = lat2 - lat1
    val dLon = Math.toRadians(to.lon - from.lon)
    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
        Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2)
    return 2 * earth * Math.asin(minOf(1.0, Math.sqrt(a)))
}

/** What the person watching knows about a share right now. */
enum class LiveState {
    /** The share is running: [LiveView.point] is where the other phone was, as it last said. */
    LIVE,

    /** It is over. Nothing of where anybody was comes with it. */
    ENDED,

    /** There is no such share: never one, or the relay has forgotten it. */
    GONE,
}

/** A share as the relay tells it: the newest point, or that it has ended. */
data class LiveView(val state: LiveState, val until: Long, val point: LivePoint?) {

    /** True while the last point is older than [STALE_MS]: the other phone may have lost its signal. */
    fun stale(now: Long): Boolean = point == null || now - point.at > STALE_MS

    companion object {
        /**
         * How old the newest point may be before the card says updates have
         * paused. A phone sends one every half minute, so a minute and a half
         * of silence means something is wrong rather than that nothing moved.
         */
        const val STALE_MS = 90_000L
    }
}

/** Where a share stands, for the card in the conversation. */
enum class LivePhase {
    /** This phone is sharing, and the other person may be looking. */
    SHARING,

    /** The other person has shared, and this phone has not opened it yet. */
    OFFER,

    /** This phone opened it and is watching. */
    WATCHING,

    /** Over, on either side. */
    ENDED,
}

/**
 * What a live-location message carries on this phone, kept in the message's
 * own text. The relay knows the share by [share] and the minute it ends by
 * [until]; [key] never leaves an encrypted message, and is what opens the
 * points.
 */
data class LiveShare(
    val share: String,
    val key: String,
    val minutes: Int,
    val until: Long,
    val state: Int = LIVE,
) {

    /** True when this phone is the one the location belongs to. */
    fun phase(outgoing: Boolean, now: Long): LivePhase = when {
        state == STOPPED || now >= until -> LivePhase.ENDED
        outgoing -> LivePhase.SHARING
        state == WATCHING -> LivePhase.WATCHING
        else -> LivePhase.OFFER
    }

    fun toLocal(): String = JSONObject()
        .put("share", share)
        .put("key", key)
        .put("mins", minutes)
        .put("until", until)
        .put("state", state)
        .toString()

    companion object {
        /** Running, and this phone is sharing where it is. */
        const val LIVE = 0

        /** The person it was started for has opened it: this phone is watched. */
        const val WATCHING = 1

        /** Over, because somebody stopped it, or its minutes ran out. */
        const val STOPPED = 2

        /**
         * What a share may be started for. Safety, not convenience: a walk home
         * is not over in a quarter of an hour, so the list runs to a day.
         */
        val CHOICES = listOf(15, 60, 4 * 60, 8 * 60, 24 * 60)

        private val SHARE = Regex("^[0-9a-f]{32}$")

        fun fromLocal(text: String): LiveShare? = try {
            val json = JSONObject(text)
            val share = json.optString("share")
            val key = json.optString("key")
            val minutes = json.optInt("mins", 0)
            val until = json.optLong("until", 0L)
            if (SHARE.matches(share) && key.isNotEmpty() && minutes in 1 until 24 * 60 + 1 && until > 0L) {
                LiveShare(share, key, minutes, until, json.optInt("state", LIVE))
            } else {
                null
            }
        } catch (_: JSONException) {
            null
        }

        /** What a message that is not a share says instead of a card. */
        const val UNREADABLE = "Live location"
    }
}

/**
 * Sealing a point, and opening one.
 *
 * AES-GCM with the share's own 256-bit key, a fresh 96-bit IV for every
 * point, and the share's ID as associated data, so a point cannot be lifted
 * from one share into another. Sealed, a point is its IV followed by the
 * ciphertext and its tag: about a hundred and twenty bytes.
 */
object LiveSeal {
    private const val KEY_BYTES = 32
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val TRANSFORM = "AES/GCM/NoPadding"

    private val random = SecureRandom()

    /** A new key for one share, base64 as it travels in a message. */
    fun newKey(): String {
        val key = ByteArray(KEY_BYTES)
        random.nextBytes(key)
        return Base64.getEncoder().encodeToString(key)
    }

    /** A new ID for one share: 16 random bytes in lower-case hex. */
    fun newShareId(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** The sealed point: its IV, then the ciphertext and its tag. */
    fun seal(key: String, share: String, point: LivePoint): ByteArray {
        val secret = SecretKeySpec(Base64.getDecoder().decode(key), "AES")
        val iv = ByteArray(IV_BYTES)
        random.nextBytes(iv)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(share.toByteArray(Charsets.UTF_8))
        val body = cipher.doFinal(point.toJson().toByteArray(Charsets.UTF_8))
        return iv + body
    }

    /** Opens a sealed point, or null when it is not one or not this share's. */
    fun open(key: String, share: String, sealed: ByteArray): LivePoint? {
        if (sealed.size <= IV_BYTES) return null
        return try {
            val secret = SecretKeySpec(Base64.getDecoder().decode(key), "AES")
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
            cipher.updateAAD(share.toByteArray(Charsets.UTF_8))
            val plain = cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
            LivePoint.fromJson(String(plain, Charsets.UTF_8))
        } catch (_: Exception) {
            // Not this key, not this share, or not a point at all.
            null
        }
    }
}
