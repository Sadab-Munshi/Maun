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
import org.robolectric.annotation.Config

/**
 * A notification channel's sound is fixed the moment Android makes it, and the
 * only way to change it would be to throw the channel away and make a new one
 * — which would also throw away whatever the user did to it in Android's own
 * settings. Maun therefore keeps one channel per choice and makes each once.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one starts background work that needs a phone.
@Config(application = android.app.Application::class)
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
    fun `the message sound is named, not numbered`() {
        // A channel keeps the URI it was made with for ever. A number in it
        // would point at whatever holds that number in a later build.
        assertEquals("android.resource://org.maun.messenger/raw/incoming_message_ringtone1", Notifications.maunSoundUri("org.maun.messenger"))
        Notifications(context).createChannels()
        val sound = manager().getNotificationChannel(SoundSettings.Channel.MAUN.id).sound
        assertEquals(Notifications.maunSoundUri(context.packageName), sound.toString())
        assertTrue("a resource number is not stable across builds", sound.toString().contains("/raw/"))
    }

    @Test
    fun `a channel pointing at a sound that cannot be opened is made again`() {
        // What an install carried through several versions can end up with: a
        // channel whose sound is a file that is no longer there. A file path
        // is used rather than a resource number so that the test says what it
        // means on any machine - what matters is that the sound cannot be
        // opened, not which kind of address it is.
        val id = SoundSettings.Channel.MAUN.id
        val gone = "file:///nowhere/incoming_message_ringtone1.ogg"
        val attributes = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
            .build()
        fun makeSilentChannel() = manager().createNotificationChannel(
            android.app.NotificationChannel(id, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(android.net.Uri.parse(gone), attributes)
            },
        )
        makeSilentChannel()

        Notifications(context).createChannels()

        assertEquals(Notifications.maunSoundUri(context.packageName), manager().getNotificationChannel(id).sound.toString())

        // And only once: a phone whose channels cannot be opened for some
        // other reason must not have them thrown away on every start.
        manager().deleteNotificationChannel(id)
        makeSilentChannel()
        Notifications(context).createChannels()
        assertEquals("already dealt with, this one is left alone", gone, manager().getNotificationChannel(id).sound.toString())
    }

    @Test
    fun `a good channel is not thrown away, and the phone sound is never touched`() {
        Notifications(context).createChannels()
        val id = SoundSettings.Channel.MAUN.id
        val before = manager().getNotificationChannel(id).sound.toString()
        Notifications(context).createChannels()
        assertEquals(before, manager().getNotificationChannel(id).sound.toString())
        // The ringing channel is the phone's own ringtone, which is the user's to choose.
        assertNotNull(manager().getNotificationChannel("incoming_calls").sound)
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
