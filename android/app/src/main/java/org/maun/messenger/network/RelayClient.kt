package org.maun.messenger.network

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject

class RelayException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** One-off requests to the Maun relay. The lasting connection is [RelayConnection]. */
class RelayClient(private val http: OkHttpClient = defaultHttp) {

    /**
     * Asks the address whether it is a Maun relay that speaks this app's
     * protocol. Returns normally if so; otherwise throws a [RelayException]
     * whose message can be shown to the user.
     */
    suspend fun checkHealth(relayUrl: HttpUrl) {
        val request = Request.Builder().url(relayUrl.newBuilder().encodedPath("/health").build()).build()
        val (code, body) = withContext(Dispatchers.IO) {
            try {
                http.newCall(request).execute().use { response ->
                    // A relay's answer is tiny; never read an unbounded page.
                    response.code to response.peekBody(MAX_HEALTH_BYTES).string()
                }
            } catch (e: IOException) {
                throw RelayException("Could not reach the relay: ${e.message ?: e.javaClass.simpleName}", e)
            }
        }
        if (code != 200) {
            throw RelayException(
                if (code == 404) "That address answered, but it is not a Maun relay (404)"
                else "The relay answered with HTTP $code",
            )
        }
        val health = try {
            JSONObject(body)
        } catch (e: JSONException) {
            throw RelayException("That address answered, but it is not a Maun relay", e)
        }
        if (health.optString("service") != SERVICE_NAME) {
            throw RelayException("That address answered, but it is not a Maun relay")
        }
        protocolProblem(health.optInt("proto", -1))?.let { throw RelayException(it) }
    }

    companion object {
        /** Must match PROTOCOL_VERSION in backend/src/protocol.ts. */
        const val PROTOCOL_VERSION = 2

        private const val SERVICE_NAME = "maun-relay"
        private const val MAX_HEALTH_BYTES = 4096L

        val defaultHttp: OkHttpClient by lazy {
            OkHttpClient.Builder()
                // Protocol-level pings keep the connection alive without waking the mailbox.
                .pingInterval(30, TimeUnit.SECONDS)
                .build()
        }

        /** Explains a protocol mismatch in words, or returns null when the versions match. */
        fun protocolProblem(relayProtocol: Int): String? = when {
            relayProtocol == PROTOCOL_VERSION -> null
            relayProtocol < PROTOCOL_VERSION ->
                "The relay runs an older version (protocol $relayProtocol, this app needs " +
                    "$PROTOCOL_VERSION). Update the relay."
            else ->
                "The relay runs a newer version (protocol $relayProtocol, this app speaks " +
                    "$PROTOCOL_VERSION). Update the app."
        }

        /**
         * Cleans up a relay address typed by a person: trims it, assumes https
         * when no scheme is given, and drops any path. Returns null if it is
         * not a usable web address.
         */
        fun normalizeRelayUrl(input: String): HttpUrl? {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return null
            val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
            val parsed = withScheme.toHttpUrlOrNull() ?: return null
            return parsed.newBuilder().encodedPath("/").query(null).fragment(null).build()
        }

        /** OkHttp upgrades an http(s) URL to a WebSocket itself. */
        fun socketUrl(relayUrl: HttpUrl, mailboxId: String): HttpUrl =
            relayUrl.newBuilder().encodedPath("/").addPathSegments("v1/ws/$mailboxId").build()
    }
}
