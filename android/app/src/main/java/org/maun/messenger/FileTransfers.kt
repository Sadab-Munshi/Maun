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
import java.io.InputStream
import java.security.MessageDigest
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
 * it. That encrypted form goes to the relay and is stored in `filesDir/media`,
 * while a decrypted copy is cached in Maun's private sandbox (`cacheDir/media`)
 * for instant chat display and playback, and incoming photos/videos are saved
 * straight to Gallery (`Pictures/Maun`) when enabled in Settings.
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
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val dir = File(appContext.filesDir, "media")
    private val plainDir = File(appContext.cacheDir, "media")
    private val cameraDir = File(appContext.cacheDir, "camera")

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

    /** App-private decrypted cache copy for instant display, voice playback, and one-tap saving. */
    private fun plain(seq: Long) = File(plainDir, "$seq.bin")

    /** A small copy of a picture, kept so that opening a conversation does not decode huge photos. */
    private fun thumb(seq: Long) = File(dir, "$seq.thumb")

    /** The small copy has a key of its own, made from the file's: one key never seals two files. */
    private fun thumbKey(secret: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").run {
            update("maun:thumb:v1".toByteArray(Charsets.UTF_8))
            digest(secret)
        }

    /** The picture of message [seq] at about [maxPixels], if it has been shown since the app started. */
    fun cachedImage(seq: Long, maxPixels: Int): Bitmap? = images.get("$seq@$maxPixels")

    /** The size a picture will be shown at, or null when [uri] is not a picture Android can read. */
    private fun pictureSize(uri: Uri): Pair<Int, Int>? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val turned = resolver.openInputStream(uri)?.use(::rotationOf) ?: 0
        when {
            bounds.outWidth <= 0 || bounds.outHeight <= 0 -> null
            turned == 90 || turned == 270 -> bounds.outHeight to bounds.outWidth
            else -> bounds.outWidth to bounds.outHeight
        }
    } catch (_: IOException) {
        null
    } catch (_: RuntimeException) {
        null
    }

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
        val mime = resolver.getType(uri)?.lowercase() ?: guessMimeFromName(name)
        val result = encryptAndQueue(peer, name, mime, open = { resolver.openInputStream(uri) }) { record ->
            val picture = if (record.isImage) pictureSize(uri) else null
            if (picture == null) record else record.copy(width = picture.first, height = picture.second)
        }
        // Clean up temporary quick-camera captures from Maun's cache once encrypted.
        if (uri.authority?.endsWith(".fileprovider") == true) {
            runCatching { resolver.delete(uri, null, null) }
            cameraDir.listFiles()?.firstOrNull { it.name == name }?.delete()
        }
        result
    }

    private fun guessMimeFromName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }

    /**
     * Sends a voice message that was just recorded into [recording], which is
     * deleted whatever happens: the encrypted copy is the only one kept.
     */
    suspend fun sendVoice(peer: String, recording: File, millis: Long): String? = withContext(Dispatchers.IO) {
        try {
            encryptAndQueue(peer, "Voice message.ogg", Attachment.VOICE_MIME, open = { recording.inputStream() }) {
                it.copy(duration = millis.coerceIn(1, Attachment.MAX_VOICE_MILLIS))
            }
        } finally {
            recording.delete()
        }
    }

    /** Encrypts what [open] gives into the app's folder, adds the message, and starts the upload. */
    private suspend fun encryptAndQueue(
        peer: String,
        name: String,
        mime: String,
        open: () -> InputStream?,
        describe: (Attachment) -> Attachment,
    ): String? {
        dir.mkdirs()
        val secret = FileCipher.newKey()
        val temp = File(dir, "out-${UUID.randomUUID()}.tmp")
        val plainBytes = try {
            val input = open() ?: return UNREADABLE
            input.use { source ->
                temp.outputStream().buffered().use { FileCipher.encrypt(source, it, secret, Attachment.MAX_BYTES) }
            }
        } catch (_: FileCipher.TooLargeException) {
            temp.delete()
            return TOO_LARGE
        } catch (_: IOException) {
            temp.delete()
            return UNREADABLE
        } catch (_: SecurityException) {
            temp.delete()
            return UNREADABLE
        }

        val file = describe(
            Attachment(
                key = "",
                secret = Base64.getEncoder().encodeToString(secret),
                name = Attachment.cleanName(name),
                mime = mime,
                size = plainBytes,
                state = Attachment.UPLOADING,
            ),
        )
        val message = try {
            engine.addFile(peer, file)
        } catch (_: IllegalStateException) {
            temp.delete()
            return "Add this person as a contact before sending a file."
        }
        if (!temp.renameTo(stored(message.seq))) {
            temp.delete()
            engine.deleteMessage(message.seq)
            return UNREADABLE
        }
        ensurePlainCache(message.seq, secret)
        start(message.seq)
        return null
    }

    /** Opens a decrypted stream for [seq], preferring the fast private cache when present. */
    private fun openDecrypted(seq: Long, secret: ByteArray): InputStream? {
        val cached = ensurePlainCache(seq, secret)
        if (cached != null && cached.exists()) {
            return try {
                cached.inputStream().buffered()
            } catch (_: IOException) {
                null
            }
        }
        val source = stored(seq)
        if (!source.exists()) return null
        return try {
            open(source, secret)
        } catch (_: IOException) {
            null
        }
    }

    /** Materializes the decrypted copy into Maun's private cache directory if not already cached. */
    private fun ensurePlainCache(seq: Long, secret: ByteArray): File? {
        val target = plain(seq)
        if (target.exists() && target.length() > 0L) return target
        val source = stored(seq)
        if (!source.exists()) return null
        plainDir.mkdirs()
        val temp = File(plainDir, "$seq-${UUID.randomUUID()}.tmp")
        return try {
            source.inputStream().buffered().use { encrypted ->
                temp.outputStream().buffered().use { out -> FileCipher.decrypt(encrypted, out, secret) }
            }
            if (temp.renameTo(target)) target else {
                temp.delete()
                null
            }
        } catch (_: IOException) {
            temp.delete()
            null
        } catch (_: FileCipher.DamagedException) {
            temp.delete()
            null
        }
    }

    /** The whole file of message [seq], decrypted into memory. For small things only: a voice message. */
    suspend fun bytes(seq: Long, file: Attachment, maxBytes: Long): ByteArray? = withContext(Dispatchers.IO) {
        if (file.size > maxBytes) return@withContext null
        try {
            openDecrypted(seq, file.secretBytes())?.use { it.readBytes() }
        } catch (_: IOException) {
            null
        }
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
        plainDir.mkdirs()
        val part = File(dir, "$seq.part")
        val plainPart = File(plainDir, "$seq-${UUID.randomUUID()}.tmp")
        try {
            api.download(file.key, part) { done, total ->
                report(seq, done, if (total > 0) total else FileCipher.encryptedSize(file.size))
            }
            val secret = file.secretBytes()
            // Read and decrypt into our private cache once before believing it:
            // verifies integrity and gives instant display + MediaStore saving.
            val plainBytes = try {
                part.inputStream().buffered().use { src ->
                    plainPart.outputStream().buffered().use { dst ->
                        FileCipher.decrypt(src, dst, secret)
                    }
                }
            } catch (e: FileCipher.DamagedException) {
                throw FileException("The file arrived damaged. Ask for it to be sent again.", cause = e)
            }
            if (plainBytes != file.size) throw FileException("The file arrived damaged. Ask for it to be sent again.")
            if (!part.renameTo(stored(seq))) throw IOException("could not be moved into place")
            if (!plainPart.renameTo(plain(seq))) plainPart.delete()
            engine.setFileState(seq, Attachment.READY)
            maybeAutoSaveIncomingMedia(seq, file, secret)
        } finally {
            part.delete()
            plainPart.delete()
        }
    }

    private fun maybeAutoSaveIncomingMedia(seq: Long, file: Attachment, secret: ByteArray) {
        if (!file.isGalleryMedia || file.isVoice) return
        if (!MediaSaver.saveIncomingMediaEnabled(appContext)) return
        val row = dao.message(seq) ?: return
        if (row.outgoing || row.savedToGallery) return
        if (MediaSaver.saveToDevice(appContext, file) { openDecrypted(seq, secret) }) {
            dao.markSavedToGallery(seq)
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
        val secret = file.secretBytes()
        val small = maxPixels <= THUMB_PIXELS
        if (small) {
            // The small copy made the first time this picture was shown.
            val kept = thumb(seq)
            if (kept.exists()) {
                val bitmap = try {
                    open(kept, thumbKey(secret)).use { BitmapFactory.decodeStream(it) }
                } catch (_: IOException) {
                    null
                }
                if (bitmap != null) {
                    images.put(cacheKey, bitmap)
                    return@withContext bitmap
                }
                kept.delete()
            }
        }
        if (!stored(seq).exists() && !plain(seq).exists()) return@withContext null
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            openDecrypted(seq, secret)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return@withContext null
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPixels) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = openDecrypted(seq, secret)?.use { BitmapFactory.decodeStream(it, null, options) }
                ?: return@withContext null
            val degrees = openDecrypted(seq, secret)?.use(::rotationOf) ?: 0
            val upright = turnUpright(decoded, degrees)
            images.put(cacheKey, upright)
            if (small) keepThumb(seq, upright, secret)
            upright
        } catch (_: IOException) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    /**
     * Saves the file of message [seq] straight to Gallery (`Pictures/Maun`) or
     * `Download/Maun` via MediaStore, skipping duplicates tracked by `savedToGallery`.
     * Returns the confirmation message to show, or null if saving failed.
     */
    suspend fun saveToDevice(seq: Long, file: Attachment): String? = withContext(Dispatchers.IO) {
        val row = dao.message(seq)
        if (row?.savedToGallery == true) {
            return@withContext MediaSaver.savedNotice(file, alreadySaved = true)
        }
        if (!stored(seq).exists() && !plain(seq).exists()) return@withContext null
        val secret = file.secretBytes()
        val ok = MediaSaver.saveToDevice(appContext, file) { openDecrypted(seq, secret) }
        if (!ok) return@withContext null
        dao.markSavedToGallery(seq)
        MediaSaver.savedNotice(file, alreadySaved = false)
    }

    /** Writes the file of message [seq], decrypted, to a specific [target] URI if ever needed. */
    suspend fun saveTo(seq: Long, file: Attachment, target: Uri): Boolean = withContext(Dispatchers.IO) {
        if (!stored(seq).exists() && !plain(seq).exists()) return@withContext false
        try {
            val out = resolver.openOutputStream(target) ?: return@withContext false
            val input = openDecrypted(seq, file.secretBytes()) ?: return@withContext false
            input.use { plainStream -> out.use { plainStream.copyTo(it) } }
            true
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    /** Stores a small encrypted copy of [bitmap] for the next time the conversation is opened. */
    private fun keepThumb(seq: Long, bitmap: Bitmap, secret: ByteArray) {
        val temp = File(dir, "thumb-${UUID.randomUUID()}.tmp")
        try {
            val jpeg = java.io.ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, jpeg)) return
            temp.outputStream().buffered().use { FileCipher.encrypt(jpeg.toByteArray().inputStream(), it, thumbKey(secret)) }
            if (!temp.renameTo(thumb(seq))) temp.delete()
        } catch (_: IOException) {
            temp.delete()
        }
    }

    private fun open(source: File, secret: ByteArray) = FileCipher.decrypting(source.inputStream().buffered(), secret)

    /** How far a photo has to be turned, according to what the camera noted in it. */
    private fun rotationOf(stream: InputStream): Int = try {
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
        plainDir.listFiles()?.forEach { file ->
            val seq = file.name.substringBefore('.').toLongOrNull()
            val orphan = if (seq != null) seq !in kept else file.lastModified() < old
            if (orphan) file.delete()
        }
        cameraDir.listFiles()?.forEach { file ->
            if (file.lastModified() < old) file.delete()
        }
    }

    /** Stops every transfer and deletes every stored file. */
    suspend fun wipe() = withContext(Dispatchers.IO) {
        jobs.values.forEach { it.cancel() }
        images.evictAll()
        dir.listFiles()?.forEach { it.delete() }
        plainDir.listFiles()?.forEach { it.delete() }
        cameraDir.listFiles()?.forEach { it.delete() }
    }

    private companion object {
        const val TOO_LARGE = "That file is larger than 25 MB, the most Maun can send."
        const val UNREADABLE = "That file could not be read."
        const val IMAGE_CACHE_BYTES = 32 * 1024 * 1024
        /** Pictures asked for at this size or smaller are kept as a small copy. */
        const val THUMB_PIXELS = 720
        const val THUMB_QUALITY = 85
        const val LEFTOVER_AGE_MILLIS = 60 * 60 * 1000L
    }
}
