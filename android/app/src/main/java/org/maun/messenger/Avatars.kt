package org.maun.messenger

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pictures for people: one's own, and one for each contact.
 *
 * They are kept on this phone only, in Maun's private storage, and are never
 * sent anywhere. That is on purpose: a picture that travelled with a message
 * would have to be kept on the relay, and nothing about a conversation does.
 */
object Avatars {
    /** The key that stands for this phone's own picture. */
    const val SELF = "self"

    private const val DIR = "avatars"
    private const val MAX_PIXELS = 512
    private const val QUALITY = 88

    /**
     * Pictures that were read since the app started, so that a list of
     * conversations draws without touching the disk on every frame.
     */
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private fun directory(context: Context) = File(context.applicationContext.filesDir, DIR)

    /** One file per person, named after their ID so that no name is written down. */
    fun file(context: Context, id: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
        return File(directory(context), digest.joinToString("") { "%02x".format(it) } + ".jpg")
    }

    /** A number that changes when the picture does, so that a screen can be redrawn for it. */
    fun version(context: Context, id: String): Long = file(context, id).let { if (it.exists()) it.lastModified() else 0L }

    fun has(context: Context, id: String): Boolean = file(context, id).exists()

    /** The picture of [id], once, without touching the disk again. */
    fun cached(id: String): Bitmap? = cache.get(id)

    fun load(context: Context, id: String): Bitmap? {
        cache.get(id)?.let { return it }
        val source = file(context, id)
        if (!source.exists()) return null
        return try {
            BitmapFactory.decodeFile(source.path)?.also { cache.put(id, it) }
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    /**
     * Keeps a small copy of the picture at [uri] for [id]. The picture is
     * shrunk while it is read, so a phone-sized photo never sits in memory in
     * full. Returns false when the picture could not be read.
     */
    suspend fun save(context: Context, id: String, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        } catch (_: IOException) {
            return@withContext false
        } catch (_: SecurityException) {
            return@withContext false
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext false
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_PIXELS) sample *= 2
        val picture = try {
            app.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        } catch (_: IOException) {
            null
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: SecurityException) {
            null
        } ?: return@withContext false

        val target = file(app, id)
        directory(app).mkdirs()
        val temp = File(directory(app), "${target.name}.tmp")
        val written = try {
            temp.outputStream().buffered().use { out -> picture.compress(Bitmap.CompressFormat.JPEG, QUALITY, out) }
        } catch (_: IOException) {
            false
        }
        cache.put(id, picture)
        if (!written || !temp.renameTo(target)) {
            temp.delete()
            cache.remove(id)
            return@withContext false
        }
        true
    }

    fun remove(context: Context, id: String) {
        file(context, id).delete()
        cache.remove(id)
    }

}
