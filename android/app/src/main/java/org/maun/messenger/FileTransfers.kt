package org.maun.messenger

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.LruCache
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maun.messenger.crypto.FileCipher
import org.maun.messenger.data.ChatDao
import org.maun.messenger.network.FileApi
import org.maun.messenger.network.FileException

/**
 * Sends and fetches the files of messages, and keeps them on this phone.
 *
 * A file is encrypted with a key of its own before anything else happens to
 * it. That encrypted form is what goes to the relay, and it is also the only
 * form kept here, in the app's private folder, named after its message. The
 * key is in the message row, inside the encrypted database. The file is
 * decrypted only into memory to show it, or into a place the user picks.
 */
class FileTransfers(
    context: Context,
    private val engine: ChatEngine,
    private val dao: ChatDao,
    private val api: FileApi,
    private val scope: CoroutineScope,
    /** Returns once the relay is connected, or after waiting long enough for it. */
    private val whenOnline: suspend () -> Unit = {},
) {
    private val resolver = context.applicationContext.contentResolver
    private val dir = File(context.applicationContext.filesDir, "media")

    private val jobs = ConcurrentHashMap<Long, Job>()
    private val _progress = MutableStateFlow<Map<Long, Float>>(emptyMap())

    /** Transfers under way, by message: how much is done, from 0 to 1. */
    val progress: StateFlow<Map<Long, Float>> = _progress.asStateFlow()

    private val _problem = MutableStateFlow<String?>(null)

    /** Why the last transfer that failed did so. Cleared by [clearProblem]. */
    val problem: StateFlow<String?> = _problem.asStateFlow()

    fun clearProblem() {
        _problem.value = null
    }

    private val images = object : LruCache<String, Bitmap>(IMAGE_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private fun stored(seq: Long) = File(dir, "$seq.enc")

    /**
     * Encrypts the file at [uri] and starts sending it to [peer]. Returns why
     * it could not be, or null when it is on its way.
     */
    suspend fun send(peer: String, uri: Uri): String? = withContext(Dispatchers.IO) {
        var name = uri.lastPathSegment ?: "file"
        var size = -1L
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { row ->
                if (row.moveToFirst()) {
                    if (!row.isNull(0)) name = row.getString(0)
                    if (!row.isNull(1)) size = row.getLong(1)
                }
            }
        } catch (_: RuntimeException) {
            // Not every source answers questions about its files. The name stays a guess.
        }
        if (size > Attachment.MAX_BYTES) return@withContext TOO_LARGE
        val mime = resolver.getType(uri)?.lowercase() ?: "application/octet-stream"

        dir.mkdirs()
        val secret = FileCipher.newKey()
        val temp = File(dir, "out-${UUID.randomUUID()}.tmp")
        val plainBytes = try {
            val input = resolver.openInputStream(uri) ?: return@withContext UNREADABLE
            input.use { source ->
                temp.outputStream().buffered().use { FileCipher.encrypt(source, it, secret, Attachment.MAX_BYTES) }
            }
        } catch (_: FileCipher.TooLargeException) {
            temp.delete()
            return@withContext TOO_LARGE
        } catch (_: IOException) {
            temp.delete()
            return@withContext UNREADABLE
        } catch (_: SecurityException) {
            temp.delete()
            return@withContext UNREADABLE
        }

        val file = Attachment(
            key = "",
            secret = Base64.getEncoder().encodeToString(secret),
            name = Attachment.cleanName(name),
            mime = mime,
            size = plainBytes,
            state = Attachment.UPLOADING,
        )
        val message = try {
            engine.addFile(peer, file)
        } catch (_: IllegalStateException) {
            temp.delete()
            return@withContext "Add this person as a contact before sending a file."
        }
        if (!temp.renameTo(stored(message.seq))) {
            temp.delete()
            engine.deleteMessage(message.seq)
            return@withContext UNREADABLE
        }
        start(message.seq)
        null
    }

    /**
     * Sends the file of message [seq] if it has not reached the relay, fetches
     * it if it is not on this phone. Does nothing while that is under way.
     */
    fun start(seq: Long) {
        if (jobs[seq]?.isActive == true) return
        val job = scope.launch(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                val file = engine.fileOf(seq) ?: return@launch
                val upload = file.key.isEmpty()
                if (upload || !stored(seq).exists()) whenOnline()
                when {
                    upload -> upload(seq, file)
                    !stored(seq).exists() -> download(seq, file)
                    file.state != Attachment.READY -> engine.setFileState(seq, Attachment.READY)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: FileException) {
                engine.setFileState(seq, if (e.gone) Attachment.GONE else Attachment.FAILED)
                if (!e.gone) _problem.value = e.message
            } catch (e: IOException) {
                engine.setFileState(seq, Attachment.FAILED)
                _problem.value = "This phone could not store the file: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                _progress.update { it - seq }
                jobs.remove(seq)
            }
        }
        // Registered before it runs, so its own clean-up cannot come first.
        if (jobs.putIfAbsent(seq, job) == null) {
            _progress.update { it + (seq to 0f) }
            job.start()
        } else {
            job.cancel()
        }
    }

    private suspend fun upload(seq: Long, file: Attachment) {
        val source = stored(seq)
        if (!source.exists()) {
            // The encrypted copy is gone (storage was cleared): nothing left to send.
            engine.setFileState(seq, Attachment.GONE)
            return
        }
        engine.setFileState(seq, Attachment.UPLOADING)
        val key = api.upload(source) { done, total -> report(seq, done, total) }
        engine.sendFile(seq, file.copy(key = key))
    }

    private suspend fun download(seq: Long, file: Attachment) {
        engine.setFileState(seq, Attachment.DOWNLOADING)
        dir.mkdirs()
        val part = File(dir, "$seq.part")
        try {
            api.download(file.key, part) { done, total ->
                report(seq, done, if (total > 0) total else FileCipher.encryptedSize(file.size))
            }
            // Read it through once before believing it: the relay, or anyone
            // between, could have handed over something else.
            val plainBytes = try {
                part.inputStream().buffered().use { FileCipher.decrypt(it, Discard, file.secretBytes()) }
            } catch (e: FileCipher.DamagedException) {
                throw FileException("The file arrived damaged. Ask for it to be sent again.", cause = e)
            }
            if (plainBytes != file.size) throw FileException("The file arrived damaged. Ask for it to be sent again.")
            if (!part.renameTo(stored(seq))) throw IOException("could not be moved into place")
            engine.setFileState(seq, Attachment.READY)
        } finally {
            part.delete()
        }
    }

    private fun report(seq: Long, done: Long, total: Long) {
        val fraction = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
        _progress.update { if (seq in it) it + (seq to fraction) else it }
    }

    /**
     * The picture in message [seq], scaled so that its longer side is about
     * [maxPixels]. Null when the file is not on this phone or is not a picture
     * Android can read.
     */
    suspend fun image(seq: Long, file: Attachment, maxPixels: Int): Bitmap? = withContext(Dispatchers.IO) {
        val cacheKey = "$seq@$maxPixels"
        images.get(cacheKey)?.let { return@withContext it }
        val source = stored(seq)
        if (!source.exists()) return@withContext null
        val secret = file.secretBytes()
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            open(source, secret).use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPixels) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = open(source, secret).use { BitmapFactory.decodeStream(it, null, options) }
                ?: return@withContext null
            val upright = turnUpright(decoded, open(source, secret).use(::rotationOf))
            images.put(cacheKey, upright)
            upright
        } catch (_: IOException) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    /** Writes the file of message [seq], decrypted, to the place the user picked. */
    suspend fun saveTo(seq: Long, file: Attachment, target: Uri): Boolean = withContext(Dispatchers.IO) {
        val source = stored(seq)
        if (!source.exists()) return@withContext false
        try {
            val out = resolver.openOutputStream(target) ?: return@withContext false
            out.use { open(source, file.secretBytes()).use { plain -> plain.copyTo(it) } }
            true
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun open(source: File, secret: ByteArray) = FileCipher.decrypting(source.inputStream().buffered(), secret)

    /** How far a photo has to be turned, according to what the camera noted in it. */
    private fun rotationOf(stream: java.io.InputStream): Int = try {
        when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (_: IOException) {
        0
    }

    private fun turnUpright(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /** Deletes stored files whose message is gone, and leftovers of transfers that never finished. */
    suspend fun sweep() = withContext(Dispatchers.IO) {
        val kept = dao.fileMessages().toSet()
        val old = System.currentTimeMillis() - LEFTOVER_AGE_MILLIS
        dir.listFiles()?.forEach { file ->
            val seq = file.name.substringBefore('.').toLongOrNull()
            val orphan = if (seq != null) seq !in kept else file.lastModified() < old
            if (orphan) {
                if (seq != null) jobs[seq]?.cancel()
                file.delete()
            }
        }
    }

    /** Stops every transfer and deletes every stored file. */
    suspend fun wipe() = withContext(Dispatchers.IO) {
        jobs.values.forEach { it.cancel() }
        images.evictAll()
        dir.listFiles()?.forEach { it.delete() }
    }

    private object Discard : OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }

    private companion object {
        const val TOO_LARGE = "That file is larger than 25 MB, the most Maun can send."
        const val UNREADABLE = "That file could not be read."
        const val IMAGE_CACHE_BYTES = 24 * 1024 * 1024
        const val LEFTOVER_AGE_MILLIS = 60 * 60 * 1000L
    }
}
