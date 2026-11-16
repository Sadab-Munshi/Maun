package org.maun.messenger.webrtc

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator

/** Where the other person's voice comes out during a call. */
enum class AudioRoute { Earpiece, Speaker, Wired, Bluetooth }

/**
 * The phone's sound during a call: the ringing on both sides, where the
 * other person's voice comes out, and the screen going dark at the ear.
 * Everything it changes is put back by [release].
 */
class CallAudio(context: Context) {
    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val power = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager

    private var ringtone: Ringtone? = null
    private var ringback: ToneGenerator? = null
    private var vibrating = false
    private var focus: AudioFocusRequest? = null
    private var modeBefore: Int? = null
    private var atEar: PowerManager.WakeLock? = null

    /** Rings and vibrates for a call that is coming in, as far as the phone's own ringer setting allows. */
    fun ringIncoming() {
        stopRinging()
        val mode = audio.ringerMode
        if (mode == AudioManager.RINGER_MODE_NORMAL) {
            runCatching {
                val tone = RingtoneManager.getRingtone(appContext, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
                tone?.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                tone?.isLooping = true
                tone?.play()
                ringtone = tone
            }
        }
        if (mode != AudioManager.RINGER_MODE_SILENT) {
            runCatching {
                @Suppress("DEPRECATION")
                val vibrator = appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                // Named as ringing, or Android stops it the moment the app is not in front.
                vibrator?.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 800, 1200), 0),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                vibrating = vibrator != null
            }
        }
    }

    /** The tone the caller hears while the other phone rings. */
    fun ringOutgoing() {
        stopRinging()
        runCatching {
            ringback = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 60).also { it.startTone(ToneGenerator.TONE_SUP_RINGTONE) }
        }
    }

    fun stopRinging() {
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching {
            ringback?.stopTone()
            ringback?.release()
        }
        ringback = null
        if (vibrating) {
            runCatching {
                @Suppress("DEPRECATION")
                (appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.cancel()
            }
            vibrating = false
        }
    }

    /** Told when a headset is plugged in or taken out, or a Bluetooth one comes or goes. */
    var onDevicesChanged: (() -> Unit)? = null

    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
            onDevicesChanged?.invoke()
        }

        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            onDevicesChanged?.invoke()
        }
    }
    private var watching = false

    /** Where the other person's voice comes out right now. */
    var route: AudioRoute = AudioRoute.Earpiece
        private set

    private fun routeOf(type: Int): AudioRoute? = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioRoute.Earpiece
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioRoute.Speaker
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET -> AudioRoute.Wired
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> AudioRoute.Bluetooth
        else -> null
    }

    /**
     * The places the voice can come out of at the moment, in the order they
     * are offered. Before Android 12 a call cannot be steered to a headset by
     * hand; a wired one is used by itself there, so only the two built into
     * the phone are listed.
     */
    fun routes(): List<AudioRoute> {
        val found = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audio.availableCommunicationDevices.mapNotNull { routeOf(it.type) } }.getOrDefault(emptyList())
        } else {
            listOf(AudioRoute.Earpiece, AudioRoute.Speaker)
        }
        return found.distinct().sortedBy { it.ordinal }.ifEmpty { listOf(AudioRoute.Speaker) }
    }

    /**
     * Puts the phone into its call mode and picks where the voice comes out:
     * a headset if there is one, otherwise the earpiece, or the loudspeaker
     * when [preferSpeaker] (a video call) or the device has no earpiece.
     */
    fun begin(preferSpeaker: Boolean) {
        if (modeBefore == null) modeBefore = audio.mode
        runCatching { audio.mode = AudioManager.MODE_IN_COMMUNICATION }
        if (focus == null) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .build()
            runCatching { audio.requestAudioFocus(request) }
            focus = request
        }
        if (!watching) {
            runCatching { audio.registerAudioDeviceCallback(devices, null) }
            watching = true
        }
        setRoute(best(routes(), preferSpeaker))
    }

    /** The route to use when nobody has chosen: a headset before the phone's own. */
    fun best(available: List<AudioRoute>, preferSpeaker: Boolean): AudioRoute = when {
        AudioRoute.Bluetooth in available -> AudioRoute.Bluetooth
        AudioRoute.Wired in available -> AudioRoute.Wired
        preferSpeaker || AudioRoute.Earpiece !in available -> AudioRoute.Speaker
        else -> AudioRoute.Earpiece
    }

    /** Sends the voice to [wanted]. At the earpiece the screen also goes dark when the phone is held to the ear. */
    fun setRoute(wanted: AudioRoute) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val device = audio.availableCommunicationDevices.firstOrNull { routeOf(it.type) == wanted }
                if (device != null) audio.setCommunicationDevice(device) else audio.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audio.isSpeakerphoneOn = wanted == AudioRoute.Speaker
            }
        }
        route = wanted
        runCatching {
            if (wanted != AudioRoute.Earpiece) {
                atEar?.takeIf { it.isHeld }?.release()
            } else if (power.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                val lock = atEar ?: power.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "maun:call").also { atEar = it }
                if (!lock.isHeld) lock.acquire(MAX_CALL_MILLIS)
            }
        }
    }

    /** Ends everything this class started and puts the phone's sound back as it was. */
    fun release() {
        stopRinging()
        onDevicesChanged = null
        if (watching) {
            runCatching { audio.unregisterAudioDeviceCallback(devices) }
            watching = false
        }
        runCatching { atEar?.takeIf { it.isHeld }?.release() }
        atEar = null
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audio.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audio.isSpeakerphoneOn = false
            }
        }
        focus?.let { runCatching { audio.abandonAudioFocusRequest(it) } }
        focus = null
        modeBefore?.let { runCatching { audio.mode = it } }
        modeBefore = null
        route = AudioRoute.Earpiece
    }

    private companion object {
        /** The credentials of a call run out after four hours; so does everything held for it. */
        const val MAX_CALL_MILLIS = 4 * 60 * 60 * 1000L
    }
}
