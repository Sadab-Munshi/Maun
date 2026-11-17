package org.maun.messenger.network

import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject

/** The rules a username follows, the same as on the relay. */
object Usernames {
    private val SHAPE = Regex("^[a-z][a-z0-9_]{2,19}$")
    private val RESERVED = setOf("admin", "administrator", "maun", "support", "system", "relay", "moderator")
    const val RULE = "3 to 20 small letters, digits or underscores, starting with a letter."

    /** What the user typed, in the form a username is kept in. */
    fun clean(typed: String): String = typed.trim().removePrefix("@").lowercase()

    fun isValid(name: String): Boolean = SHAPE.matches(name) && name !in RESERVED
}

/**
 * The email login of a member-only relay.
 *
 * The relay sends a six-digit code to an email address, exchanges the right
 * code for a ticket, and lets a new member put a username on it. The ticket is
 * what the phone then shows the relay when it connects. Every function
 * returns what went wrong in words for the screen, or the result.
 */
class AuthApi(relayUrl: HttpUrl, private val http: OkHttpClient = RelayClient.defaultHttp) {
    private val base = relayUrl.newBuilder().encodedPath("/").build()

    sealed interface Verified {
        /**
         * [username] is the one this address already has, or null for a new
         * member of this relay. [id] is the Maun ID the address is tied to, when
         * it already has one: a phone with new keys is not that ID, so it has
         * to either restore it or say it is starting again. [hasRecovery] says
         * whether that ID left a sealed backup that could be restored.
         */
        data class Ok(
            val ticket: String,
            val username: String?,
            val id: String? = null,
            val hasRecovery: Boolean = false,
        ) : Verified
        data class Problem(val message: String) : Verified
    }

    /** A sealed identity backup, as the relay keeps it. */
    data class Backup(val salt: ByteArray, val kdf: String, val blob: ByteArray, val id: String)

    sealed interface Found {
        data class Ok(val backup: Backup) : Found
        /** This address has an ID, and that ID left no backup. */
        data object NoBackup : Found
        data class Problem(val message: String) : Found
    }

    /** Asks for a code to be sent to [email]. Returns null when it is on its way. */
    suspend fun start(email: String): String? {
        val (status, body) = post("v1/auth/start", JSONObject().put("email", email)) ?: return UNREACHABLE
        return if (status == 200) null else explain(status, body)
    }

    suspend fun verify(email: String, code: String): Verified {
        val (status, body) = post("v1/auth/verify", JSONObject().put("email", email).put("code", code))
            ?: return Verified.Problem(UNREACHABLE)
        val ticket = body.optString("ticket")
        if (status != 200 || !TICKET.matches(ticket)) return Verified.Problem(explain(status, body))
        val username = if (body.isNull("username")) null else body.optString("username").ifEmpty { null }
        val id = if (body.isNull("id")) null else body.optString("id").ifEmpty { null }
        return Verified.Ok(ticket, username, id, body.optBoolean("hasRecovery"))
    }

    /** Fetches the sealed backup of the ID this ticket's address is tied to. */
    suspend fun recovery(ticket: String): Found {
        val (status, body) = post("v1/auth/recovery", JSONObject().put("ticket", ticket)) ?: return Found.Problem(UNREACHABLE)
        if (status == 404) return Found.NoBackup
        if (status != 200) return Found.Problem(explain(status, body))
        return try {
            Found.Ok(
                Backup(
                    salt = Base64.getDecoder().decode(body.getString("salt")),
                    kdf = body.optString("kdf"),
                    blob = Base64.getDecoder().decode(body.getString("blob")),
                    id = body.getString("id"),
                ),
            )
        } catch (_: Exception) {
            Found.Problem("That backup is not one this version of Maun can open.")
        }
    }

    /**
     * Says, in as many words, that a new ID is to take the place of the one
     * this address already has. Nothing on the relay changes until the login
     * that follows it, and that login is the last thing the old ID sees.
     */
    suspend fun replace(ticket: String): String? {
        val (status, body) = post("v1/auth/replace", JSONObject().put("ticket", ticket)) ?: return UNREACHABLE
        return if (status == 200) null else explain(status, body)
    }

    /** Puts [username] on the ticket. Returns null when it is theirs. */
    suspend fun name(ticket: String, username: String): String? {
        val (status, body) = post("v1/auth/name", JSONObject().put("ticket", ticket).put("username", username))
            ?: return UNREACHABLE
        return if (status == 200) null else explain(status, body)
    }

    private suspend fun post(path: String, json: JSONObject): Pair<Int, JSONObject>? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(base.newBuilder().addPathSegments(path).build())
            .post(json.toString().toRequestBody(JSON))
            .build()
        try {
            http.newCall(request).execute().use { response ->
                val body = try {
                    JSONObject(response.peekBody(MAX_ANSWER_BYTES).string())
                } catch (_: JSONException) {
                    JSONObject()
                }
                response.code to body
            }
        } catch (_: IOException) {
            null
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private val TICKET = Regex("^[A-Za-z0-9_-]{20,64}$")
        private const val MAX_ANSWER_BYTES = 4096L
        private const val UNREACHABLE = "Could not reach the relay. Check your connection and try again."

        /** What a refusal means, in words. */
        fun explain(status: Int, body: JSONObject): String = when (body.optString("error")) {
            "bad_email" -> "That does not look like an email address."
            "wait" -> "A code was sent a moment ago. Wait a minute before asking for another."
            "limit" -> "Too many codes were sent to this address today. Try again tomorrow."
            "mail_failed" -> "The relay could not send the email. Its owner needs to check the mail settings."
            "wrong_code" -> "That code is not right."
            "expired" -> "That code has run out. Ask for a new one."
            "taken" -> "Somebody already has that username."
            "bad_name" -> "A username has ${Usernames.RULE}"
            "not_member_only" -> "This relay does not ask for a login."
            else -> if (status == 429) "Too many tries. Wait a minute." else "The relay answered with HTTP $status."
        }
    }
}
