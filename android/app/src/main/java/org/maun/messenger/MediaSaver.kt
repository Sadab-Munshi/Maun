package org.maun.messenger

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.edit
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Writes decrypted media and documents straight into the phone's Gallery
 * (`Pictures/Maun`) or Downloads (`Download/Maun`) without opening a folder picker.
 */
object MediaSaver {
    private const val SETTINGS = "settings"
    private const val KEY_SAVE_INCOMING_MEDIA = "save_incoming_media"
    private const val KEY_CHAT_PATTERN = "chat_wallpaper_pattern"
    private const val ALBUM_SUBDIR = "Maun"

    fun saveIncomingMediaEnabled(context: Context): Boolean =
        context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).getBoolean(KEY_SAVE_INCOMING_MEDIA, true)

    fun setSaveIncomingMediaEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_SAVE_INCOMING_MEDIA, enabled)
        }
    }

    fun chatPatternEnabled(context: Context): Boolean =
        context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).getBoolean(KEY_CHAT_PATTERN, false)

    fun setChatPatternEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_CHAT_PATTERN, enabled)
        }
    }

    fun savedNotice(file: Attachment, alreadySaved: Boolean = false): String =
        if (file.isGalleryMedia) {
            if (alreadySaved) "Already saved to Gallery" else "Saved to Gallery"
        } else {
            if (alreadySaved) "Already saved to Downloads/Maun" else "Saved to Downloads/Maun"
        }

    /**
     * Saves [file] directly to `Pictures/Maun` (images and videos) or
     * `Download/Maun` (documents and other files). Returns true when stored.
     */
    fun saveToDevice(context: Context, file: Attachment, openSource: () -> InputStream?): Boolean {
        val app = context.applicationContext
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(app, file, openSource)
        } else {
            saveLegacyExternal(app, file, openSource)
        }
    }

    private fun saveViaMediaStore(context: Context, file: Attachment, openSource: () -> InputStream?): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val resolver = context.contentResolver
        val collection = when {
            file.isImage -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            file.isVideo -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val relativePath = if (file.isGalleryMedia) {
            "${Environment.DIRECTORY_PICTURES}/$ALBUM_SUBDIR"
        } else {
            "${Environment.DIRECTORY_DOWNLOADS}/$ALBUM_SUBDIR"
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, Attachment.cleanName(file.name))
            put(MediaStore.MediaColumns.MIME_TYPE, file.mime.ifBlank { "application/octet-stream" })
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = try {
            resolver.insert(collection, values)
        } catch (_: RuntimeException) {
            null
        } ?: return false

        return try {
            val input = openSource() ?: run {
                resolver.delete(uri, null, null)
                return false
            }
            val out = resolver.openOutputStream(uri) ?: run {
                input.close()
                resolver.delete(uri, null, null)
                return false
            }
            input.use { src -> out.use { dst -> src.copyTo(dst) } }
            val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, ready, null, null)
            true
        } catch (_: IOException) {
            runCatching { resolver.delete(uri, null, null) }
            false
        } catch (_: SecurityException) {
            runCatching { resolver.delete(uri, null, null) }
            false
        } catch (_: RuntimeException) {
            runCatching { resolver.delete(uri, null, null) }
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun saveLegacyExternal(context: Context, file: Attachment, openSource: () -> InputStream?): Boolean {
        val rootType = if (file.isGalleryMedia) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOWNLOADS
        val baseDir = File(Environment.getExternalStoragePublicDirectory(rootType), ALBUM_SUBDIR)
        if (!baseDir.exists() && !baseDir.mkdirs()) return false
        val target = uniqueFile(baseDir, Attachment.cleanName(file.name))
        return try {
            val input = openSource() ?: return false
            input.use { src -> target.outputStream().buffered().use { dst -> src.copyTo(dst) } }
            MediaScannerConnection.scanFile(
                context,
                arrayOf(target.absolutePath),
                arrayOf(file.mime.ifBlank { "application/octet-stream" }),
                null,
            )
            true
        } catch (_: IOException) {
            target.delete()
            false
        } catch (_: SecurityException) {
            target.delete()
            false
        }
    }

    private fun uniqueFile(dir: File, cleanName: String): File {
        val first = File(dir, cleanName)
        if (!first.exists()) return first
        val dot = cleanName.lastIndexOf('.')
        val stem = if (dot > 0) cleanName.substring(0, dot) else cleanName
        val ext = if (dot > 0) cleanName.substring(dot) else ""
        for (i in 1..999) {
            val candidate = File(dir, "$stem ($i)$ext")
            if (!candidate.exists()) return candidate
        }
        return File(dir, "${System.currentTimeMillis()}-$cleanName")
    }
}
