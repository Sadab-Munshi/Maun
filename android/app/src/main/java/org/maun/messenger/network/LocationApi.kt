package org.maun.messenger.network

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject

/** Why a share could not be started, or a point could not be handed over. */
class LocationException(message: String, val gone: Boolean = false, cause: Throwable? = null) : IOException(message, cause)

/**
 * What the relay says about a share: the newest sealed point and when it was
 * left, or that the share has ended.
 */
class LiveRemote(
    val state: String,
    val until: Long,
    val at: Long?,
    /** The sealed point, still sealed: only the key that came in the message opens it. */
    val sealed: ByteArray?,
) {
    val ended: Boolean get() = state == "ended"
    val gone: Boolean get() = state == "gone"

    companion object {
        const val LIVE = "live"
        const val ENDED = "ended"
        const val GONE = "gone"
    }
}

/**
 * The relay's live-location routes, under `/v1/location`.
 *
 * A share is started here, a sealed point is handed over every half minute,
 * and the person it was started for reads it back through the same mailbox.
 * The relay stores one point per share and cannot open it; everything about
 * who may see it is decided by the mailbox that owns it.
 */
class LocationApi(
    relayUrl: HttpUrl,
    selfId: String,
    token: () -> String?,
    onBanned: () -> Unit,
    http: OkHttpClient = RelayClient.defaultHttp,
) {
    private val locationUrl = relayUrl.newBuilder().encodedPath("/v1/location").build()

    // A point is small and comes every half minute: a slow network means a
    // missed point, not a wait.
    private val client = http.newBuilder()
        .addInterceptor(RelayAuthInterceptor(selfId, token, onBanned))
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Starts a share with [peer] and returns its ID and the minute it runs out at. Blocks. */
    fun start(peer: String, minutes: Int): Pair<String, Long> {
        val body = JSONObject().put("to", peer).put("minutes", minutes).toString()
            .toRequestBody(JSON)
        val request = Request.Builder().url(locationUrl).post(body).build()
        return call(request) { response ->
            if (response.code != 201) throw refused(response)
            val json = json(response)
            val share = json.optString("share")
            val until = json.optLong("until", 0L)
            if (!SHARE.matches(share) || until <= 0L) throw LocationException("The relay's answer could not be read.")
            share to until
        }
    }

    /**
     * Hands over the newest sealed point. False when the share is over: the
     * relay turns away a point for a share that has ended or been stopped.
     */
    fun point(share: String, sealed: ByteArray): Boolean {
        val request = Request.Builder()
            .url(locationUrl.newBuilder().addPathSegment(share).build())
            .post(sealed.toRequestBody(OCTETS))
            .build()
        return call(request) { response ->
            when (response.code) {
                200 -> true
                410 -> false
                else -> throw refused(response)
            }
        }
    }

    /** Ends the share now. Doing it twice, or after it ran out, is not an error. */
    fun stop(share: String) {
        val request = Request.Builder().url(locationUrl.newBuilder().addPathSegment(share).build()).delete().build()
        call(request) { response ->
            if (response.code != 204 && response.code != 404 && response.code != 410) throw refused(response)
        }
    }

    /** What [sharer]'s share looks like from here. Blocks. */
    fun peek(sharer: String, share: String): LiveRemote {
        val request = Request.Builder()
            .url(locationUrl.newBuilder().addPathSegment(sharer).addPathSegment(share).build())
            .get()
            .build()
        return call(request) { response ->
            if (response.code != 200) throw refused(response)
            val json = json(response)
            val state = json.optString("state")
            if (state != LiveRemote.LIVE && state != LiveRemote.ENDED && state != LiveRemote.GONE) {
                throw LocationException("The relay's answer could not be read.")
            }
            val point = json.optString("point").takeIf { it.isNotEmpty() }
            LiveRemote(
                state = state,
                until = json.optLong("until", 0L),
                at = if (json.has("at") && !json.isNull("at")) json.optLong("at") else null,
                sealed = point?.let {
                    try {
                        java.util.Base64.getDecoder().decode(it)
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                },
            )
        }
    }

    private fun <T> call(request: Request, read: (Response) -> T): T =
        try {
            client.newCall(request).execute().use(read)
        } catch (e: LocationException) {
            throw e
        } catch (e: IOException) {
            throw LocationException("The connection was lost. Try again.", cause = e)
        }

    private fun json(response: Response): JSONObject = try {
        JSONObject(response.body.string())
    } catch (e: JSONException) {
        throw LocationException("The relay's answer could not be read.", cause = e)
    } catch (e: IOException) {
        throw LocationException("The connection was lost. Try again.", cause = e)
    }

    private fun refused(response: Response): LocationException = when (response.code) {
        401 -> LocationException("Not connected to the relay yet. Try again in a moment.")
        403 -> LocationException("The relay no longer accepts this account.", gone = true)
        404 -> LocationException("This location is no longer being shared.", gone = true)
        409 -> LocationException("This phone has no account on that relay any more.")
        410 -> LocationException("This location is no longer being shared.", gone = true)
        429 -> LocationException("Wait a moment, then try again.")
        else -> LocationException("The relay answered with HTTP ${response.code}.")
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private val OCTETS = "application/octet-stream".toMediaType()
        private val SHARE = Regex("^[0-9a-f]{32}$")
    }
}
