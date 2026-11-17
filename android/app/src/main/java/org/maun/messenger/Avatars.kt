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
 * Pictures for people: one's own, and the pictures their owners have chosen.
 *
 * Two kinds live here, and they are kept apart on purpose:
 *
 *  - the *real* picture of an account (one's own, or a contact's), which its
 *    owner set. It is whatever arrived from the relay, kept for speed and
 *    for use without a connection. Only its owner can change it.
 *  - a *local* picture for a contact (`local:*`), which only this phone has.
 *    It is a note to oneself about what somebody looks like, and nobody is
 *    told about it. It wins over the real picture on this phone only.
 *
 * Both are stored in Maun's private storage, one file per person, named after
 * their ID so that no name is written down.
 */
object Avatars {
    /** The key that stands for this phone's own picture. */
    const val SELF = "self"

    private const val DIR = "avatars"
    private const val MAX_PIXELS = 512

    /** A picture travels to the relay, so a smaller file is one a phone sends quickly. */
    private const val QUALITY = 88
    private const val MAX_BYTES = 256 * 1024

    /**
     * Pictures that were read since the app started, so that a list of
     * conversations draws without touching the disk on every frame.
     */
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Distinguishes the picture that is drawn from any other read of the same person. */
    private const val SHARED = "shared:"

    private fun directory(context: Context) = File(context.applicationContext.filesDir, DIR)

    /** One file per person, named after their ID so that no name is written down. */
    fun file(context: Context, id: String): File = named(context, "real:$id")

    /** A picture kept for a contact on this phone only, which nobody else knows about. */
    fun localFile(context: Context, id: String): File = named(context, "local:$id")

    private fun named(context: Context, key: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        return File(directory(context), digest.joinToString("") { "%02x".format(it) } + ".jpg")
    }

    /** A number that changes when the picture does, so that a screen can be redrawn for it. */
    fun version(context: Context, id: String): Long = shown(context, id).let { if (it.exists()) it.lastModified() else 0L }

    /** Whether there is a picture for [id], of either kind. */
    fun has(context: Context, id: String): Boolean = shown(context, id).exists()

    /** Whether a picture for [id] was chosen on this phone, and for this phone only. */
    fun hasLocal(context: Context, id: String): Boolean = localFile(context, id).exists()

    /** The file that is drawn for [id]: the one set on this phone if there is one, else the real one. */
    fun shown(context: Context, id: String): File =
        localFile(context, id).takeIf { it.exists() } ?: file(context, id)

    /**
     * The picture that was actually shared with this phone by [id]'s own
     * phone, which is the only one Maun draws. A photo once kept here as a
     * note to oneself is left on disk and read by nothing: a person's picture
     * is theirs, and the app no longer offers to put anything in its place.
     */
    private fun drawn(context: Context, id: String): File = file(context, id)

    /** Whether [id] shared a picture that this phone has. */
    fun hasShared(context: Context, id: String): Boolean = drawn(context, id).exists()

    /** The picture of [id], once, without touching the disk again. */
    fun cached(id: String): Bitmap? = cache.get(id)

    /** The shared picture of [id] as already read, without touching the disk. */
    fun cachedShared(id: String): Bitmap? = cache.get(SHARED + id)

    fun load(context: Context, id: String): Bitmap? = read(cacheKey = id, source = shown(context, id))

    /** The shared picture of [id], read once and kept. */
    fun loadShared(context: Context, id: String): Bitmap? =
        read(cacheKey = SHARED + id, source = drawn(context, id))

    private fun read(cacheKey: String, source: File): Bitmap? {
        cache.get(cacheKey)?.let { return it }
        if (!source.exists()) return null
        return try {
            BitmapFactory.decodeFile(source.path)?.also { cache.put(cacheKey, it) }
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    /**
     * Keeps a small copy of the picture at [uri] for [id]. The picture is
     * shrunk while it is read, so a phone-sized photo never sits in memory in
     * full. Returns false when the picture could not be read.
     */
    suspend fun save(context: Context, id: String, uri: Uri): Boolean =
        saveTo(context, file(context, id), id, uri, drawn = true)

    /** Keeps a picture of [id] on this phone only, without telling anyone. */
    suspend fun saveLocal(context: Context, id: String, uri: Uri): Boolean =
        saveTo(context, localFile(context, id), id, uri, drawn = false)

    private suspend fun saveTo(
        context: Context,
        target: File,
        id: String,
        uri: Uri,
        drawn: Boolean,
    ): Boolean = withContext(Dispatchers.IO) {
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

        write(app, target, id, picture, drawn)
    }

    /**
     * Keeps a picture that arrived from its owner. It is decoded and shrunk
     * again rather than trusted, so that whatever comes back is a picture this
     * phone can draw and nothing else. Returns false when it is not a picture.
     */
    suspend fun store(context: Context, id: String, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return@withContext false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext false
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_PIXELS) sample *= 2
        val picture = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (_: OutOfMemoryError) {
            null
        } ?: return@withContext false
        write(context.applicationContext, file(context, id), id, picture, drawn = true)
    }

    /** Writes [picture] to [target] as a JPEG, in one step so that a half-written picture is never drawn. */
    private fun write(app: Context, target: File, id: String, picture: Bitmap, drawn: Boolean): Boolean {
        directory(app).mkdirs()
        val temp = File(directory(app), "${target.name}.tmp")
        val written = try {
            temp.outputStream().buffered().use { out -> picture.compress(Bitmap.CompressFormat.JPEG, QUALITY, out) }
        } catch (_: IOException) {
            false
        }
        cache.put(id, picture)
        // Only the picture that is actually drawn answers to the drawn name.
        if (drawn) cache.put(SHARED + id, picture)
        if (!written || !temp.renameTo(target)) {
            temp.delete()
            forget(id)
            return false
        }
        return true
    }

    /** Forgets the real picture of [id]. Only its owner's phone does this, on purpose. */
    fun remove(context: Context, id: String) {
        file(context, id).delete()
        forget(id)
    }

    /** Forgets a picture kept on this phone only. */
    fun removeLocal(context: Context, id: String) {
        localFile(context, id).delete()
        forget(id)
    }

    /** Drops what was read of [id], under every name it is kept under. */
    private fun forget(id: String) {
        cache.remove(id)
        cache.remove(SHARED + id)
    }

    /** The bytes of the real picture of [id], to be sealed and sent. Null when there is none. */
    fun bytes(context: Context, id: String): ByteArray? = try {
        file(context, id).takeIf { it.exists() }?.readBytes()
    } catch (_: IOException) {
        null
    }

}
