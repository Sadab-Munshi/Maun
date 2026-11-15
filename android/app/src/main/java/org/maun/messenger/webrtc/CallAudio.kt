package org.maun.messenger.webrtc

import android.content.Context
import android.media.AudioAttributes
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
                vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 1200), 0))
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

    /** Puts the phone into its call mode: the voice goes to the earpiece (or a headset) until [setSpeaker] says otherwise. */
    fun begin() {
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
        setSpeaker(false)
    }

    /** Loudspeaker on or off. Off also lets the screen go dark when the phone is held to the ear. */
    fun setSpeaker(on: Boolean) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val speaker = audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (on && speaker != null) audio.setCommunicationDevice(speaker) else audio.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audio.isSpeakerphoneOn = on
            }
        }
        runCatching {
            if (on) {
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
    }

    private companion object {
        /** The credentials of a call run out after four hours; so does everything held for it. */
        const val MAX_CALL_MILLIS = 4 * 60 * 60 * 1000L
    }
}
