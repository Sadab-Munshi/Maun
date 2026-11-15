package org.maun.messenger.network

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.buffer
import okio.sink
import okio.source
import org.json.JSONException
import org.json.JSONObject

/**
 * Signs every request to the relay's HTTP routes and watches the answers for
 * a ban.
 *
 * The credential is the account's ID and the `files` token the relay handed
 * out at the last login, which itself was signed by this phone's hardware
 * key. Nothing is sent when there is no token: the request goes out bare and
 * the relay answers 401.
 *
 * An answer of 403 with `{"error":"banned"}` means the relay's owner has shut
 * this member out. [onBanned] is told at once, before the caller sees the
 * answer, so the app can drop what it holds.
 */
class RelayAuthInterceptor(
    private val selfId: String,
    private val token: () -> String?,
    private val onBanned: () -> Unit,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val current = token()
        val request = if (current == null) {
            chain.request()
        } else {
            chain.request().newBuilder().header("Authorization", "Bearer $selfId.$current").build()
        }
        val response = chain.proceed(request)
        if (response.code == 403 && errorOf(response) == "banned") onBanned()
        return response
    }

    companion object {
        /** The `error` field of a JSON answer, read without using the body up. */
        fun errorOf(response: Response): String? = try {
            JSONObject(response.peekBody(1024).string()).optString("error").ifEmpty { null }
        } catch (_: JSONException) {
            null
        } catch (_: IOException) {
            null
        }
    }
}

/** Why a file could not be sent or fetched, in words for the person using the app. */
class FileException(message: String, val gone: Boolean = false, cause: Throwable? = null) : IOException(message, cause)

/** The relay's file routes: store an encrypted file, fetch one back. */
class FileApi(
    relayUrl: HttpUrl,
    selfId: String,
    token: () -> String?,
    onBanned: () -> Unit,
    http: OkHttpClient = RelayClient.defaultHttp,
) {
    private val filesUrl = relayUrl.newBuilder().encodedPath("/v1/files").build()

    // A big file on a slow connection takes minutes; only a stalled one is given up on.
    private val client = http.newBuilder()
        .addInterceptor(RelayAuthInterceptor(selfId, token, onBanned))
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    /**
     * Uploads the already encrypted [file] and returns the name the relay
     * gave it. [onProgress] is told how many bytes have gone, out of how many.
     * Blocks; call it off the main thread.
     */
    fun upload(file: File, onProgress: (Long, Long) -> Unit): String {
        val length = file.length()
        val body = object : RequestBody() {
            override fun contentType() = OCTETS
            override fun contentLength() = length
            override fun writeTo(sink: BufferedSink) {
                file.source().use { source ->
                    var sent = 0L
                    while (true) {
                        val read = source.read(sink.buffer, SEGMENT)
                        if (read < 0) break
                        sent += read
                        sink.emitCompleteSegments()
                        onProgress(sent, length)
                    }
                }
            }
        }
        val request = Request.Builder().url(filesUrl).post(body).build()
        return call(request) { response ->
            if (response.code != 201) throw refused(response)
            val key = try {
                JSONObject(response.body.string()).optString("key")
            } catch (e: JSONException) {
                throw FileException("The relay's answer could not be read.", cause = e)
            }
            if (!FILE_KEY.matches(key)) throw FileException("The relay's answer could not be read.")
            key
        }
    }

    /** Fetches the encrypted file stored as [key] into [target]. Blocks. */
    fun download(key: String, target: File, onProgress: (Long, Long) -> Unit) {
        require(FILE_KEY.matches(key)) { "not a file name" }
        val request = Request.Builder().url(filesUrl.newBuilder().addPathSegment(key).build()).build()
        call(request) { response ->
            if (response.code != 200) throw refused(response)
            val body = response.body
            val length = body.contentLength()
            target.sink().buffer().use { sink ->
                val source = body.source()
                var received = 0L
                while (true) {
                    val read = source.read(sink.buffer, SEGMENT)
                    if (read < 0) break
                    received += read
                    if (received > MAX_DOWNLOAD_BYTES) throw FileException("The file is larger than Maun accepts.")
                    sink.emitCompleteSegments()
                    onProgress(received, length)
                }
            }
        }
    }

    private fun <T> call(request: Request, read: (Response) -> T): T =
        try {
            client.newCall(request).execute().use(read)
        } catch (e: FileException) {
            throw e
        } catch (e: IOException) {
            throw FileException("The connection was lost. Try again.", cause = e)
        }

    private fun refused(response: Response): FileException = when (response.code) {
        401 -> FileException("Not connected to the relay yet. Try again in a moment.")
        403 -> FileException("The relay no longer accepts this account.")
        404 -> FileException("The relay no longer has this file. Files are kept for one day.", gone = true)
        413 -> FileException("The file is larger than 25 MB.")
        429 -> if (RelayAuthInterceptor.errorOf(response) == "quota") {
            FileException("Today's allowance for sending files is used up.")
        } else {
            FileException("The relay is busy. Try again in a minute.")
        }
        else -> FileException("The relay answered with HTTP ${response.code}.")
    }

    companion object {
        private val OCTETS = "application/octet-stream".toMediaType()
        private val FILE_KEY = Regex("^[0-9a-f]{64}$")
        private const val SEGMENT = 8192L
        /** The 25 MB limit plus the encryption's overhead, as on the relay. */
        private const val MAX_DOWNLOAD_BYTES = 25_000_000L + 65_536L
    }
}
