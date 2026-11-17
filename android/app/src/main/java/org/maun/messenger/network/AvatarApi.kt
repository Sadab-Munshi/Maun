package org.maun.messenger.network

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject

/** Why a profile picture could not be put on, or taken from, the relay. */
class AvatarException(message: String, val gone: Boolean = false, cause: Throwable? = null) : IOException(message, cause)

/**
 * The relay's profile-picture routes: one sealed picture per account, under
 * `/v1/avatars`.
 *
 * The picture travels already encrypted with a key of its own, and that key
 * only ever leaves the phone inside an end-to-end encrypted message. What the
 * relay keeps is therefore a few dozen kilobytes it cannot read.
 */
class AvatarApi(
    relayUrl: HttpUrl,
    selfId: String,
    token: () -> String?,
    onBanned: () -> Unit,
    http: OkHttpClient = RelayClient.defaultHttp,
) {
    private val avatarsUrl = relayUrl.newBuilder().encodedPath("/v1/avatars").build()

    private val client = http.newBuilder()
        .addInterceptor(RelayAuthInterceptor(selfId, token, onBanned))
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Stores, or replaces, this phone's picture and returns the version the
     * relay gave it. Blocks; call it off the main thread.
     */
    fun upload(sealed: File): String {
        if (!sealed.exists() || sealed.length() <= 0L) throw AvatarException("There is no picture to send.")
        val body = sealed.asRequestBody(OCTETS)
        val request = Request.Builder().url(avatarsUrl).put(body).build()
        return call(request) { response ->
            if (response.code != 201) throw refused(response)
            val ver = try {
                JSONObject(response.body.string()).optString("ver")
            } catch (e: JSONException) {
                throw AvatarException("The relay's answer could not be read.", cause = e)
            }
            if (!VERSION.matches(ver)) throw AvatarException("The relay's answer could not be read.")
            ver
        }
    }

    /**
     * Fetches the sealed picture of [id] into [target] and returns the version
     * it is. Blocks.
     */
    fun download(id: String, target: File): String {
        val request = Request.Builder().url(avatarsUrl.newBuilder().addPathSegment(id).build()).build()
        return call(request) { response ->
            if (response.code != 200) throw refused(response)
            val body = response.body
            if (body.contentLength() > MAX_SEALED_BYTES) throw AvatarException("That picture is larger than Maun accepts.")
            var received = 0L
            target.outputStream().buffered().use { out ->
                val source = body.source()
                val buffer = ByteArray(SEGMENT)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    received += read
                    if (received > MAX_SEALED_BYTES) throw AvatarException("That picture is larger than Maun accepts.")
                    out.write(buffer, 0, read)
                }
            }
            val version = response.header("X-Maun-Avatar-Version").orEmpty()
            if (!VERSION.matches(version)) throw AvatarException("The relay's answer could not be read.")
            version
        }
    }

    /** Removes this phone's picture from the relay. Blocks. Doing it twice is not an error. */
    fun delete() {
        val request = Request.Builder().url(avatarsUrl).delete().build()
        call(request) { response ->
            if (response.code != 204 && response.code != 404) throw refused(response)
        }
    }

    private fun <T> call(request: Request, read: (Response) -> T): T =
        try {
            client.newCall(request).execute().use(read)
        } catch (e: AvatarException) {
            throw e
        } catch (e: IOException) {
            throw AvatarException("The connection was lost. Try again.", cause = e)
        }

    private fun refused(response: Response): AvatarException = when (response.code) {
        401 -> AvatarException("Not connected to the relay yet. Try again in a moment.")
        403 -> AvatarException("The relay no longer accepts this account.")
        404 -> AvatarException("There is no picture to fetch.", gone = true)
        413 -> AvatarException("That picture is too large. Try a smaller one.")
        else -> AvatarException("The relay answered with HTTP ${response.code}.")
    }

    companion object {
        private val OCTETS = "application/octet-stream".toMediaType()
        private val VERSION = Regex("^[0-9a-f]{16}$")
        private const val SEGMENT = 4096
        /** The relay's limit plus the sealing's overhead, as in `MAX_AVATAR_BYTES`. */
        private const val MAX_SEALED_BYTES = 256L * 1024L + 1024L
    }
}
