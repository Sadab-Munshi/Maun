package org.maun.messenger.push

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.maun.messenger.SoundSettings
import org.robolectric.RobolectricTestRunner

/**
 * A notification channel's sound is fixed the moment Android makes it, and the
 * only way to change it would be to throw the channel away and make a new one
 * — which would also throw away whatever the user did to it in Android's own
 * settings. Maun therefore keeps one channel per choice and makes each once.
 */
@RunWith(RobolectricTestRunner::class)
class NotificationsChannelsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun manager() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val messageChannelIds = SoundSettings.Channel.values().map { it.id }

    @Test
    fun `there is one message channel for each choice`() {
        Notifications(context).createChannels()
        for (id in messageChannelIds) {
            assertNotNull("no channel for $id", manager().getNotificationChannel(id))
        }
    }

    @Test
    fun `the channel from before the choice is gone`() {
        Notifications(context).createChannels()
        assertNull(manager().getNotificationChannel("messages"))
    }

    @Test
    fun `the silent channel has no sound and the others have one`() {
        Notifications(context).createChannels()
        assertNull(manager().getNotificationChannel(SoundSettings.Channel.SILENT.id).sound)
        assertNotNull(manager().getNotificationChannel(SoundSettings.Channel.MAUN.id).sound)
    }

    @Test
    fun `a channel the user has silenced is left silenced`() {
        Notifications(context).createChannels()
        val id = SoundSettings.Channel.MAUN.id
        // As Android's own settings would do it.
        manager().getNotificationChannel(id).setSound(null, null)

        // Every start of the app runs this again; it must not put the sound back.
        Notifications(context).createChannels()
        Notifications(context).createChannels()

        assertNull(manager().getNotificationChannel(id).sound)
    }

    @Test
    fun `each channel says what it is, and never what the message says`() {
        Notifications(context).createChannels()
        for (id in messageChannelIds) {
            val channel = manager().getNotificationChannel(id)
            assertTrue(channel.name.isNotBlank())
            assertTrue((channel.description ?: "").isNotBlank())
        }
        // The channel carries the arrival, not the contents.
        val maun = manager().getNotificationChannel(SoundSettings.Channel.MAUN.id)
        assertTrue(maun.description.contains("Never shows the message"))
        assertEquals(3, messageChannelIds.size)
    }
}
