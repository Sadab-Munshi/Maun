package org.maun.messenger.network

import android.content.Context
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject

/**
 * The name of a place, from OpenStreetMap's own geocoder.
 *
 * A coordinate is exact and says nothing: "22.37884, 87.98385" is a place on a
 * map, not a place somebody recognizes. This turns one into the other — a road,
 * a village, a district, a postcode — using Nominatim, the reverse geocoder run
 * by the OpenStreetMap Foundation.
 *
 * Its usage policy is short and this class is built around it: at most one
 * request a second, no bulk use, and **every answer cached**, so a place is
 * asked about once and never again. Only the phone that sends a place asks:
 * the name travels inside the encrypted message, so the phone that receives it
 * never tells anybody where anybody was.
 *
 * What it will not say: a name where OpenStreetMap has none. In much of rural
 * India the answer is a village and a postcode, because that is all the map
 * knows. Google knows more there, and no free geocoder can hand that over.
 */
class Addresses(
    private val context: Context,
    http: OkHttpClient = RelayClient.defaultHttp,
) {
    // One lookup at a time, and never two in the same second: the policy asks
    // for that, and a place sent by hand is not in a hurry.
    private val gate = Mutex()
    private var lastAsked = 0L

    private val client = http.newBuilder()
        .addInterceptor(
            okhttp3.Interceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        // The policy requires an app to say which app it is.
                        .header("User-Agent", agent())
                        .header("Accept", "application/json")
                        .build(),
                )
            },
        )
        .callTimeout(6, TimeUnit.SECONDS)
        .build()

    private val cache by lazy {
        context.getSharedPreferences("addresses", Context.MODE_PRIVATE)
    }

    /** The name of the place at [lat], [lon], or null when there is nothing true to say. */
    suspend fun of(lat: Double, lon: Double): String? {
        if (!lat.isFinite() || !lon.isFinite()) return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        val key = keyOf(lat, lon)
        // A place never moves: once it has an answer, that answer is the answer.
        cache.getString(key, null)?.let { remembered ->
            return remembered.takeIf { it != NOTHING }
        }
        return gate.withLock {
            cache.getString(key, null)?.let { remembered ->
                return@withLock remembered.takeIf { it != NOTHING }
            }
            val since = System.currentTimeMillis() - lastAsked
            if (since in 0 until MIN_GAP_MS) delay(MIN_GAP_MS - since)
            lastAsked = System.currentTimeMillis()
            val answer = ask(lat, lon)
            // Nothing found is worth remembering too, or a phone with no signal
            // would ask again every time the card was drawn.
            cache.edit().putString(key, answer ?: NOTHING).apply()
            answer
        }
    }

    private suspend fun ask(lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        val url = ENDPOINT.toHttpUrl().newBuilder()
            .addQueryParameter("format", "jsonv2")
            .addQueryParameter("lat", "%.6f".format(java.util.Locale.US, lat))
            .addQueryParameter("lon", "%.6f".format(java.util.Locale.US, lon))
            .addQueryParameter("zoom", "18")
            .addQueryParameter("addressdetails", "1")
            .addQueryParameter("accept-language", "en")
            .build()
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                if (body.length > MAX_BODY) return@withContext null
                addressFrom(body)
            }
        } catch (_: Exception) {
            // No network, or Nominatim would not answer: a card without a name
            // is a card with coordinates on it, which is not a failure.
            null
        }
    }

    private fun agent(): String {
        val name = runCatching { org.maun.messenger.BuildConfig.VERSION_NAME }.getOrDefault("0")
        return "Maun/$name (Android; ${context.packageName}; https://github.com/Sadab-Munshi/Maun)"
    }

    companion object {
        private const val ENDPOINT = "https://nominatim.openstreetmap.org/reverse"

        /** The policy's one request a second, with a little room on top of it. */
        private const val MIN_GAP_MS = 1_100L

        /** No real answer is longer than this; anything bigger is not one. */
        private const val MAX_BODY = 64 * 1024

        /** The longest a name may be, when it is put in a message and on a card. */
        const val MAX_LABEL = 160

        /** What is remembered for a place that OpenStreetMap has no name for. */
        private const val NOTHING = "!"

        /** About eleven metres: two places in the same street are the same place. */
        private fun keyOf(lat: Double, lon: Double): String =
            "%.4f,%.4f".format(java.util.Locale.US, lat, lon)

        /**
         * The name out of what the geocoder answered, as one line a person would
         * read. Null when the answer is not an answer.
         *
         * It says the smallest true thing: a named thing if there is one, then
         * the road, then where it is, then the postcode — and nothing at all
         * rather than "India".
         */
        fun addressFrom(json: String): String? {
            val root = try {
                JSONObject(json)
            } catch (_: JSONException) {
                return null
            }
            val address = root.optJSONObject("address")
            val parts = ArrayList<String>(5)
            fun add(value: String?) {
                val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return
                if (text.length > 80) return
                if (parts.any { it.equals(text, ignoreCase = true) }) return
                parts += text
            }
            if (address != null) {
                add(
                    root.optString("name").ifEmpty { null }
                        ?: address.optString("amenity").ifEmpty { null },
                )
                add(address.optString("road").ifEmpty { null } ?: address.optString("pedestrian").ifEmpty { null })
                add(
                    address.optString("suburb").ifEmpty { null }
                        ?: address.optString("neighbourhood").ifEmpty { null }
                        ?: address.optString("village").ifEmpty { null }
                        ?: address.optString("town").ifEmpty { null }
                        ?: address.optString("hamlet").ifEmpty { null }
                        ?: address.optString("city").ifEmpty { null },
                )
                add(address.optString("county").ifEmpty { null })
                add(address.optString("state_district").ifEmpty { null })
                add(address.optString("postcode").ifEmpty { null })
            }
            if (parts.isEmpty()) {
                // No structured answer: the first few parts of the display name
                // are the same thing said at greater length.
                val display = root.optString("display_name")
                display.split(',').map { it.trim() }
                    .filter { it.isNotEmpty() && !it.equals("India", ignoreCase = true) }
                    .take(3)
                    .forEach { add(it) }
            }
            if (parts.isEmpty()) return null
            return parts.joinToString(", ").take(MAX_LABEL)
        }
    }
}
