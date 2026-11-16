package org.maun.messenger

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.maun.messenger.crypto.Identity
import org.maun.messenger.crypto.SignalSessions
import org.maun.messenger.data.MessageEntity
import org.maun.messenger.data.MessageKind
import org.maun.messenger.network.FileApi
import org.maun.messenger.push.Background
import org.maun.messenger.support.FakeDb
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MediaAndBackgroundTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `stay connected is off by default and legacy preference is cleared on upgrade`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.edit().clear().putBoolean("stay_connected", true).commit()

        assertFalse(Background.stayConnected(context))
        Background.enforceDefaultOffOnUpgrade(context)
        assertFalse(prefs.contains("stay_connected"))
        assertFalse(Background.stayConnected(context))

        Background.setStayConnected(context, true)
        assertTrue(Background.stayConnected(context))
        Background.setStayConnected(context, false)
        assertFalse(Background.stayConnected(context))
    }

    @Test
    fun `incoming media auto-save defaults on and chat pattern defaults off`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()

        assertTrue(MediaSaver.saveIncomingMediaEnabled(context))
        assertFalse(MediaSaver.chatPatternEnabled(context))
        MediaSaver.setChatPatternEnabled(context, true)
        assertTrue(MediaSaver.chatPatternEnabled(context))

        MediaSaver.setSaveIncomingMediaEnabled(context, false)
        MediaSaver.setChatPatternEnabled(context, false)
        assertFalse(MediaSaver.saveIncomingMediaEnabled(context))
        assertFalse(MediaSaver.chatPatternEnabled(context))
    }

    @Test
    fun `savedToGallery flag prevents duplicate saves on repeat taps`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = FakeDb()
        val self = Identity.generate()
        val engine = ChatEngine(
            selfId = self.id,
            signal = SignalSessions(self, 1, db),
            dao = db,
            tx = db,
            scope = scope,
        )
        val transfers = FileTransfers(
            context = context,
            engine = engine,
            dao = db,
            api = FileApi("https://maun.example.workers.dev".toHttpUrl(), self.id, { "tok" }, onBanned = {}),
            scope = scope,
        )

        val secret = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
        val photo = Attachment(
            key = "ab".repeat(32),
            secret = secret,
            name = "Sunset.jpg",
            mime = "image/jpeg",
            size = 1024,
            state = Attachment.READY,
        )
        val seq = db.insertMessage(
            MessageEntity(
                peer = "cd".repeat(32),
                id = "m1",
                outgoing = false,
                kind = MessageKind.FILE,
                text = photo.toLocal(),
                at = 1_000L,
                savedToGallery = false,
            ),
        )
        assertFalse(db.message(seq)!!.savedToGallery)
        db.markSavedToGallery(seq)
        assertTrue(db.message(seq)!!.savedToGallery)

        // Even with no underlying file left on disk, savedToGallery short-circuits duplicate creation.
        File(context.filesDir, "media/$seq.enc").delete()
        assertEquals("Already saved to Gallery", transfers.saveToDevice(seq, photo))
    }
}
