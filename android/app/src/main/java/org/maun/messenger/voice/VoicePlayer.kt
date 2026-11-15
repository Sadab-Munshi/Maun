package org.maun.messenger.voice

import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The voice message that is loaded for playing: which one, whether it is running, and how far it has got. */
data class VoiceState(val seq: Long, val playing: Boolean, val positionMillis: Long, val durationMillis: Long) {
    val fraction: Float get() = if (durationMillis <= 0) 0f else (positionMillis.toFloat() / durationMillis).coerceIn(0f, 1f)
}

/**
 * Plays voice messages, one at a time.
 *
 * A message is decrypted into memory and played from there; nothing is
 * written to storage unencrypted. Call from the main thread: [scope] has to
 * run there too.
 */
class VoicePlayer(private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<VoiceState?>(null)
    val state: StateFlow<VoiceState?> = _state.asStateFlow()

    private var player: MediaPlayer? = null
    private var ticker: Job? = null

    /**
     * Plays message [seq]; pauses it if it is playing, carries on if it was
     * paused. Anything else that was playing stops. [load] gives the sound.
     * Returns false when it could not be played.
     */
    suspend fun toggle(seq: Long, load: suspend () -> ByteArray?): Boolean {
        val current = _state.value
        val running = player
        if (current?.seq == seq && running != null) {
            if (current.playing) {
                runCatching { running.pause() }
                ticker?.cancel()
                _state.value = current.copy(playing = false, positionMillis = position(running))
            } else {
                runCatching { running.start() }
                _state.value = current.copy(playing = true)
                tick()
            }
            return true
        }
        stop()
        val sound = load() ?: return false
        val created = MediaPlayer()
        try {
            created.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            created.setDataSource(InMemory(sound))
            created.setOnCompletionListener { if (player === it) stop() }
            created.setOnErrorListener { failed, _, _ ->
                if (player === failed) stop()
                true
            }
            withContext(Dispatchers.IO) { created.prepare() }
            created.start()
        } catch (_: Exception) {
            runCatching { created.release() }
            return false
        }
        player = created
        _state.value = VoiceState(seq, playing = true, positionMillis = 0, durationMillis = created.duration.toLong().coerceAtLeast(0))
        tick()
        return true
    }

    /** Stops whatever is playing. */
    fun stop() {
        ticker?.cancel()
        ticker = null
        player?.let { runCatching { it.release() } }
        player = null
        _state.value = null
    }

    private fun position(of: MediaPlayer): Long = runCatching { of.currentPosition.toLong() }.getOrDefault(0)

    private fun tick() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(TICK_MILLIS)
                val running = player ?: break
                val current = _state.value ?: break
                if (current.playing) _state.value = current.copy(positionMillis = position(running))
            }
        }
    }

    private class InMemory(private val bytes: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= bytes.size) return -1
            val count = minOf(size.toLong(), bytes.size - position).toInt()
            bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
            return count
        }

        override fun getSize(): Long = bytes.size.toLong()

        override fun close() = Unit
    }

    private companion object {
        const val TICK_MILLIS = 100L
    }
}
