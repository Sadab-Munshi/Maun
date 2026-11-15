package org.maun.messenger.security

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import java.security.MessageDigest
import org.json.JSONObject
import org.maun.messenger.BuildConfig

/**
 * A fingerprint of this phone, for a member-only relay.
 *
 * It is a SHA-256 hash of what the phone says about itself: maker, model,
 * system build, screen size and density, Android's per-app device ID, and the
 * list of sensors. Only the hash and the make, model and Android version
 * leave the phone; the relay's owner can see when a member turns up on a
 * different phone, and cannot turn the hash back into its parts.
 *
 * It identifies the phone, not the person, and it is not proof: an app that
 * lies about its phone produces a different, equally valid-looking hash. What
 * proves the phone is the device key in its secure hardware. The hash changes
 * when Android itself is updated, because the system build is part of it.
 *
 * Nothing is collected or sent on a relay that is open to everyone.
 */
object DeviceFingerprint {
    /**
     * [hash] covers everything and changes with a system update. [deviceId]
     * covers only what stays: Android's ID for this app on this phone, and the
     * make and model. It survives deleting the app's data and reinstalling,
     * and is what a ban on a phone goes by.
     */
    data class Report(val hash: String, val deviceId: String, val maker: String, val model: String, val os: String, val app: String) {
        /** The form the relay reads. */
        fun toJson(): JSONObject = JSONObject()
            .put("hash", hash)
            .put("did", deviceId)
            .put("maker", maker)
            .put("model", model)
            .put("os", os)
            .put("app", app)
    }

    fun collect(context: Context): Report {
        val app = context.applicationContext
        return Report(
            hash = hash(parts(app)),
            deviceId = hash(listOf(androidId(app), Build.MANUFACTURER.orEmpty(), Build.MODEL.orEmpty()), label = "maun:device-id:v1\n"),
            app = BuildConfig.VERSION_NAME.take(MAX_FIELD),
            maker = Build.MANUFACTURER.orEmpty().take(MAX_FIELD),
            model = Build.MODEL.orEmpty().take(MAX_FIELD),
            os = Build.VERSION.RELEASE.orEmpty().take(MAX_FIELD),
        )
    }

    private fun parts(context: Context): List<String> {
        val screen = screen(context)
        val sensors = (context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)
            ?.getSensorList(Sensor.TYPE_ALL)
            .orEmpty()
            .map { "${it.type}|${it.vendor}|${it.name}|${it.version}" }
            .sorted()
        return listOf(
            Build.MANUFACTURER.orEmpty(),
            Build.MODEL.orEmpty(),
            Build.FINGERPRINT.orEmpty(),
            screen,
            androidId(context),
            sensors.size.toString(),
        ) + sensors
    }

    /** Width, height and density of the whole screen, the same whichever way the phone is held. */
    private fun screen(context: Context): String {
        val metrics = context.resources.displayMetrics
        val (width, height) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            metrics.widthPixels to metrics.heightPixels
        }
        return "${minOf(width, height)}x${maxOf(width, height)}@${metrics.densityDpi}"
    }

    /**
     * Android's ID for this app on this phone. It differs between apps and
     * between phones, and is reset by a factory reset.
     */
    @SuppressLint("HardwareIds")
    private fun androidId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()

    /** SHA-256 over the parts, each preceded by its length so that no two lists read the same. */
    internal fun hash(parts: List<String>, label: String = "maun:device:v1\n"): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(label.toByteArray(Charsets.UTF_8))
        for (part in parts) {
            val bytes = part.toByteArray(Charsets.UTF_8)
            digest.update("${bytes.size}:".toByteArray(Charsets.UTF_8))
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private const val MAX_FIELD = 64
}
