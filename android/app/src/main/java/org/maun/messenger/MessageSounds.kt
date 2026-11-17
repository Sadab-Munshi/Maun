package org.maun.messenger

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.media.SoundPool
import android.net.Uri
import android.util.Log

/**
 * The two sounds Maun makes itself: one when a message has been handed to the
 * phone's sending queue, and one when a message arrives while the app is open
 * (in the background Android's own notification makes the other one, so the
 * two can never both play for the same message).
 *
 * [SoundPool] is what a short sound wants: it is decoded once, mixed without
 * taking the audio focus away from whatever the phone is playing, and plays
 * again immediately when asked. Both sounds are a few kilobytes. Everything it
 * holds is freed by [release], which is called when the app closes.
 *
 * Nothing here overrides the phone. Silent mode, Do Not Disturb and a muted
 * notification stream all win over a choice made in Settings.
 */
class MessageSounds(context: Context) {
    private val app = context.applicationContext

    /** Built when the first sound is played, so an app that never sends anything loads nothing. */
    private var pool: SoundPool? = null
    private var sentSample = 0
    private var maunIncoming = 0
    private var systemIncoming = 0

    /**
     * A message was handed to the local sending queue: one short sound, once.
     * Delivery receipts, retries and messages read back from storage never
     * come through here.
     */
    fun playSent() {
        if (SoundSettings.sent(app) == SoundSettings.SENT_SILENT) return
        if (!audible()) return
        val pool = pool() ?: return
        val sample = sentSample.takeIf { it != 0 } ?: return
        play(pool, sample)
    }

    /** A message arrived while the app is on screen. Once for each that arrives. */
    fun playIncoming() {
        when (SoundSettings.incoming(app)) {
            SoundSettings.INCOMING_SILENT -> return
            else -> Unit
        }
        if (!audible()) return
        val pool = pool() ?: return
        val sample = if (SoundSettings.incoming(app) == SoundSettings.INCOMING_SYSTEM) systemIncoming else maunIncoming
        if (sample == 0) return
        play(pool, sample)
    }

    /** Plays the sound the user is trying out in Settings, whether or not it is the one in use. */
    fun previewSent() {
        val pool = pool() ?: return
        if (sentSample != 0) play(pool, sentSample)
    }

    fun previewIncoming(choice: String = SoundSettings.incoming(app)) {
        if (choice == SoundSettings.INCOMING_SILENT) return
        val pool = pool() ?: return
        val sample = if (choice == SoundSettings.INCOMING_SYSTEM) systemIncoming else maunIncoming
        if (sample != 0) play(pool, sample)
    }

    /** Frees the decoded sounds. Called when the app closes; playing again loads them again. */
    fun release() {
        synchronized(this) {
            pool?.release()
            pool = null
            sentSample = 0
            maunIncoming = 0
            systemIncoming = 0
        }
    }

    private fun play(pool: SoundPool, sample: Int) {
        try {
            pool.play(sample, VOLUME, VOLUME, PRIORITY, 0, RATE)
        } catch (_: Exception) {
            // A sound is never worth a crash: the message itself has already
            // been handled by the time this is called.
        }
    }

    private fun pool(): SoundPool? = synchronized(this) {
        pool ?: try {
            val made = SoundPool.Builder()
                .setMaxStreams(MAX_STREAMS)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .build()
            sentSample = made.load(app, R.raw.message_sent_sound, 1)
            maunIncoming = made.load(app, R.raw.incoming_message_ringtone1, 1)
            // The phone's own notification sound, if it has one. Playing it
            // through the same pool keeps both sounds on one mechanism.
            systemIncoming = try {
                val ringtone: Uri? = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                if (ringtone == null) 0 else made.load(app, ringtone, 1)
            } catch (_: Exception) {
                0
            }
            pool = made
            made
        } catch (e: Exception) {
            Log.w(TAG, "the message sounds could not be loaded", e)
            null
        }
    }

    /**
     * Whether a sound of Maun's may be heard at all: the phone is not silent,
     * is not in Do Not Disturb, and its notification stream is not muted.
     */
    private fun audible(): Boolean {
        val audio = app.getSystemService(AudioManager::class.java) ?: return true
        if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return false
        if (audio.isStreamMute(AudioManager.STREAM_NOTIFICATION)) return false
        val filter = try {
            val manager = app.getSystemService(android.app.NotificationManager::class.java)
            manager?.currentInterruptionFilter ?: android.app.NotificationManager.INTERRUPTION_FILTER_ALL
        } catch (_: Exception) {
            // Some phones will not say. Then the ringer mode above is the answer.
            android.app.NotificationManager.INTERRUPTION_FILTER_ALL
        }
        return filter == android.app.NotificationManager.INTERRUPTION_FILTER_ALL ||
            filter == android.app.NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    private companion object {
        const val TAG = "MessageSounds"
        const val MAX_STREAMS = 2
        const val VOLUME = 1f
        const val PRIORITY = 1
        const val RATE = 1f
    }
}
