package org.maun.messenger

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The two sound choices are kept in the app's own settings file, so they have
 * to be there after a restart, and an unfamiliar value (an old version, a
 * changed file) must fall back to something the app can actually play.
 */
@RunWith(RobolectricTestRunner::class)
class SoundSettingsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `both start on Maun's own sounds`() {
        assertEquals(SoundSettings.SENT_MAUN, SoundSettings.sent(context))
        assertEquals(SoundSettings.INCOMING_MAUN, SoundSettings.incoming(context))
    }

    @Test
    fun `the sent sound is remembered`() {
        SoundSettings.setSent(context, SoundSettings.SENT_SILENT)
        assertEquals(SoundSettings.SENT_SILENT, SoundSettings.sent(context))
        SoundSettings.setSent(context, SoundSettings.SENT_MAUN)
        assertEquals(SoundSettings.SENT_MAUN, SoundSettings.sent(context))
    }

    @Test
    fun `the incoming sound is remembered`() {
        for (choice in listOf(
            SoundSettings.INCOMING_SYSTEM,
            SoundSettings.INCOMING_SILENT,
            SoundSettings.INCOMING_MAUN,
        )) {
            SoundSettings.setIncoming(context, choice)
            assertEquals(choice, SoundSettings.incoming(context))
        }
    }

    @Test
    fun `a value from nowhere is not a sound the app tries to play`() {
        SoundSettings.setSent(context, "sandwich")
        SoundSettings.setIncoming(context, "sandwich")
        assertEquals(SoundSettings.SENT_MAUN, SoundSettings.sent(context))
        assertEquals(SoundSettings.INCOMING_MAUN, SoundSettings.incoming(context))
    }

    @Test
    fun `each choice names the channel the notification arrives on`() {
        assertEquals(SoundSettings.Channel.MAUN.id, channelFor(SoundSettings.INCOMING_MAUN))
        assertEquals(SoundSettings.Channel.SYSTEM.id, channelFor(SoundSettings.INCOMING_SYSTEM))
        assertEquals(SoundSettings.Channel.SILENT.id, channelFor(SoundSettings.INCOMING_SILENT))
    }

    @Test
    fun `the three channels have three different ids`() {
        val ids = SoundSettings.Channel.values().map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    private fun channelFor(choice: String): String {
        SoundSettings.setIncoming(context, choice)
        return SoundSettings.incomingChannel(context)
    }
}
