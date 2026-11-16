package org.maun.messenger

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.maun.messenger.crypto.FileCipher
import org.maun.messenger.crypto.Identity
import org.maun.messenger.crypto.SignalSessions
import org.maun.messenger.network.FileApi
import org.maun.messenger.support.FakeDb
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The two things a file bubble does that are easy to get wrong: showing the
 * picture, and handing the file to another app on the phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FileOpenAndPreviewTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun transfers(context: Context): FileTransfers {
        val self = Identity.generate()
        val db = FakeDb()
        val engine = ChatEngine(
            selfId = self.id,
            signal = SignalSessions(self, 1, db),
            dao = db,
            tx = db,
            scope = scope,
        )
        return FileTransfers(
            context = context,
            engine = engine,
            dao = db,
            api = FileApi("https://maun.example.workers.dev".toHttpUrl(), self.id, { "tok" }, onBanned = {}),
            scope = scope,
        )
    }

    private fun attachment(mime: String, name: String, size: Long): Attachment = Attachment(
        key = "ab".repeat(32),
        secret = Base64.getEncoder().encodeToString(ByteArray(32) { 7 }),
        name = name,
        mime = mime,
        size = size,
        state = Attachment.READY,
    )

    @Test
    fun `a photo that is on this phone is decoded for its bubble`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val picture = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        val jpeg = ByteArrayOutputStream().also { picture.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        val key = FileCipher.newKey()
        val seq = 41L
        val media = File(context.filesDir, "media").apply { mkdirs() }
        File(media, "$seq.enc").outputStream().use { out ->
            FileCipher.encrypt(jpeg.inputStream(), out, key)
        }
        val file = attachment("image/jpeg", "Sunset.jpg", jpeg.size.toLong())
            .copy(secret = Base64.getEncoder().encodeToString(key))

        // Reading the picture is what a bubble does; before, the measuring pass
        // was mistaken for a failure and every photo stayed an empty box.
        assertNotNull(transfers(context).image(seq, file, 720))
    }

    @Test
    fun `a file on this phone can be handed to another app`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val seq = 42L
        val cache = File(context.cacheDir, "media").apply { mkdirs() }
        File(cache, "$seq.bin").writeBytes("a small statement".toByteArray())
        val pdf = attachment("application/pdf", "Statement.pdf", 17)

        val uri = transfers(context).openTarget(seq, pdf)
        assertNotNull(uri)
        assertEquals("content", uri!!.scheme)
        val read = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
        assertEquals("a small statement", read)
    }

    @Test
    fun `a file that is not on this phone cannot be opened`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertNull(transfers(context).openTarget(9_999L, attachment("application/pdf", "Away.pdf", 10)))
    }

    @Test
    fun `documents are saved the same way pictures are`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pdf = attachment("application/pdf", "Statement.pdf", 10)
        assertEquals("Saved to Downloads/Maun", MediaSaver.savedNotice(pdf))
        assertEquals("Already saved to Downloads/Maun", MediaSaver.savedNotice(pdf, alreadySaved = true))
        assertTrue(MediaSaver.saveIncomingFilesEnabled(context))
    }
}
