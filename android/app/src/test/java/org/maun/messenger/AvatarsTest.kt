package org.maun.messenger

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Two kinds of picture are kept apart: the one a person chose on their own
 * phone, which nobody here can change, and a photo kept on this phone only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AvatarsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val contact = "5d".repeat(32)
    private var folder: File = File(context.cacheDir, "picked").apply { mkdirs() }

    @After
    fun clean() {
        Avatars.remove(context, contact)
        Avatars.removeLocal(context, contact)
        Avatars.remove(context, Avatars.SELF)
        folder.deleteRecursively()
    }

    private fun pick(name: String): Uri {
        val bitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
        val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        return Uri.fromFile(File(folder, name).apply { writeBytes(jpeg) })
    }

    @Test
    fun `a picture that arrived is the real one, and a local photo is kept beside it`() = runBlocking {
        // A contact's own picture, as it would arrive from the relay.
        assertTrue(Avatars.store(context, contact, File(pick("theirs.jpg").path).readBytes()))
        assertTrue(Avatars.has(context, contact))
        assertFalse(Avatars.hasLocal(context, contact))

        // A photo chosen here is a note to oneself, and wins on this phone only.
        assertTrue(Avatars.saveLocal(context, contact, pick("mine.jpg")))
        assertTrue(Avatars.hasLocal(context, contact))
        assertTrue(Avatars.shown(context, contact) == Avatars.localFile(context, contact))
        assertNotEquals(Avatars.file(context, contact).length(), 0L)
        assertTrue("their own picture is untouched", Avatars.file(context, contact).exists())

        Avatars.removeLocal(context, contact)
        assertFalse(Avatars.hasLocal(context, contact))
        assertTrue("their picture is shown again", Avatars.shown(context, contact) == Avatars.file(context, contact))
    }

    @Test
    fun `removing the real picture leaves a local one alone`() = runBlocking {
        assertTrue(Avatars.store(context, contact, File(pick("theirs.jpg").path).readBytes()))
        assertTrue(Avatars.saveLocal(context, contact, pick("mine.jpg")))

        Avatars.remove(context, contact)
        assertFalse(Avatars.file(context, contact).exists())
        assertTrue(Avatars.has(context, contact))
        assertTrue(Avatars.hasLocal(context, contact))
    }

    @Test
    fun `the same ID under each kind is stored in a file of its own`() {
        assertNotEquals(Avatars.file(context, contact).name, Avatars.localFile(context, contact).name)
        assertNotEquals(Avatars.file(context, Avatars.SELF).name, Avatars.file(context, contact).name)
    }

    @Test
    fun `nothing too small or too large is kept as a picture`() = runBlocking {
        assertFalse(Avatars.store(context, contact, ByteArray(0)))
        // The relay takes 256 KB and no more, so nothing larger is a picture.
        assertFalse(Avatars.store(context, contact, ByteArray(256 * 1024 + 1)))
        assertFalse(Avatars.has(context, contact))
    }
}
