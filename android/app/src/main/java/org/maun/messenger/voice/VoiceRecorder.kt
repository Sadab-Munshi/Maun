package org.maun.messenger.voice

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.util.UUID

/** A finished recording: where it is, and how long. */
class Recording(val file: File, val millis: Long)

/**
 * Records a voice message from the microphone.
 *
 * The sound is stored as Opus in an Ogg file, mono, at about 20 kbit/s: a
 * minute is roughly 150 kB, small enough for a slow connection. While it is
 * being recorded the file sits, unencrypted, in the app's private cache; it is
 * encrypted and deleted as soon as the recording is handed over.
 */
class VoiceRecorder(context: Context) {
    private val appContext = context.applicationContext
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    /** When the stretch being recorded now began, and how much was recorded before it. */
    private var startedAt = 0L
    private var before = 0L

    val active: Boolean get() = recorder != null

    var paused = false
        private set

    /** How much has been recorded so far, not counting the pauses. */
    val elapsedMillis: Long
        get() = if (recorder == null) 0 else before + if (paused) 0 else SystemClock.elapsedRealtime() - startedAt

    /** How loud it has been since this was last asked, from 0 to 1. For the moving bars. */
    fun level(): Float = runCatching { (recorder?.maxAmplitude ?: 0) / 32767f }.getOrDefault(0f).coerceIn(0f, 1f)

    /** Holds the recording where it is. */
    fun pause() {
        val running = recorder ?: return
        if (paused || runCatching { running.pause() }.isFailure) return
        before += SystemClock.elapsedRealtime() - startedAt
        paused = true
    }

    /** Carries on after [pause]. */
    fun resume() {
        val running = recorder ?: return
        if (!paused || runCatching { running.resume() }.isFailure) return
        startedAt = SystemClock.elapsedRealtime()
        paused = false
    }

    /** Starts recording. Returns false when the microphone could not be opened. */
    fun start(): Boolean {
        cancel()
        val target = File(appContext.cacheDir, "voice-${UUID.randomUUID()}.ogg")
        @Suppress("DEPRECATION")
        val created = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(appContext) else MediaRecorder()
        return try {
            created.setAudioSource(MediaRecorder.AudioSource.MIC)
            created.setOutputFormat(MediaRecorder.OutputFormat.OGG)
            created.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            created.setAudioChannels(1)
            created.setAudioSamplingRate(SAMPLE_RATE)
            created.setAudioEncodingBitRate(BIT_RATE)
            created.setOutputFile(target.absolutePath)
            created.prepare()
            created.start()
            recorder = created
            file = target
            startedAt = SystemClock.elapsedRealtime()
            before = 0
            paused = false
            true
        } catch (_: Exception) {
            // The microphone is in use by a call, or the phone refused.
            runCatching { created.release() }
            target.delete()
            false
        }
    }

    /** Ends the recording. Null when there was none, or it was too short to be anything. */
    fun stop(): Recording? {
        val running = recorder ?: return null
        val target = file
        val millis = elapsedMillis
        recorder = null
        file = null
        // Stopping right after starting throws: nothing was recorded yet.
        val stopped = runCatching { running.stop() }.isSuccess
        runCatching { running.release() }
        if (target == null) return null
        if (!stopped || millis < MIN_MILLIS || target.length() == 0L) {
            target.delete()
            return null
        }
        return Recording(target, millis)
    }

    /** Ends the recording and throws it away. */
    fun cancel() {
        val running = recorder ?: return
        recorder = null
        runCatching { running.stop() }
        runCatching { running.release() }
        file?.delete()
        file = null
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val BIT_RATE = 20_000

        /** Anything shorter was a slip of the finger. */
        const val MIN_MILLIS = 700L
    }
}
