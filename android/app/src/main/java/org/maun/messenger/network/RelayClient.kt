package org.maun.messenger.network

import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONException
import org.json.JSONObject

class RelayException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Talks to the Maun relay (the Cloudflare Worker and its mailboxes). */
class RelayClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        // Protocol-level pings keep the connection alive without waking the mailbox.
        .pingInterval(30, TimeUnit.SECONDS)
        .build(),
) {

    /**
     * Opens the mailbox socket, sends [body] and waits for the relay to echo it.
     * Returns the round-trip time in milliseconds.
     *
     * Milestone 0 only: this is replaced by the authenticated connection.
     */
    suspend fun echo(relayUrl: String, mailboxId: String, body: String, timeoutMillis: Long = 15_000): Long {
        val url = socketUrl(relayUrl, mailboxId)
        val echoId = UUID.randomUUID().toString()
        return withTimeout(timeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                var sentAt = 0L

                fun fail(message: String, cause: Throwable? = null) {
                    if (continuation.isActive) continuation.resumeWithException(RelayException(message, cause))
                }

                val listener = object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val frame = try {
                            JSONObject(text)
                        } catch (e: JSONException) {
                            webSocket.cancel()
                            return fail("The relay sent something unreadable", e)
                        }
                        when (frame.optString("t")) {
                            "hello" -> {
                                val proto = frame.optInt("proto", -1)
                                if (proto != PROTOCOL_VERSION) {
                                    webSocket.close(1000, null)
                                    return fail("The relay speaks protocol $proto; this app needs $PROTOCOL_VERSION")
                                }
                                sentAt = System.nanoTime()
                                val request = JSONObject().put("t", "echo").put("id", echoId).put("body", body)
                                webSocket.send(request.toString())
                            }
                            "echo" -> {
                                if (frame.optString("id") != echoId) return
                                webSocket.close(1000, null)
                                if (frame.optString("body") != body) return fail("The relay echoed different text")
                                if (continuation.isActive) {
                                    continuation.resume((System.nanoTime() - sentAt) / 1_000_000)
                                }
                            }
                            "error" -> {
                                webSocket.close(1000, null)
                                fail("The relay refused the message: ${frame.optString("code")}")
                            }
                        }
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(1000, null)
                        fail("The relay closed the connection ($code)")
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        val message = when {
                            response == null -> "Could not reach the relay: ${t.message ?: t.javaClass.simpleName}"
                            response.code == 404 -> "That address answered, but it is not a Maun relay (404)"
                            else -> "The relay answered with HTTP ${response.code}"
                        }
                        fail(message, t)
                    }
                }

                val socket = http.newWebSocket(Request.Builder().url(url).build(), listener)
                continuation.invokeOnCancellation { socket.cancel() }
            }
        }
    }

    companion object {
        /** Must match PROTOCOL_VERSION in backend/src/protocol.ts. */
        const val PROTOCOL_VERSION = 0

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

        private fun socketUrl(relayUrl: String, mailboxId: String): HttpUrl {
            val base = normalizeRelayUrl(relayUrl) ?: throw RelayException("\"$relayUrl\" is not a web address")
            // OkHttp upgrades an http(s) URL to a WebSocket itself.
            return base.newBuilder().addPathSegments("v1/ws/$mailboxId").build()
        }
    }
}
