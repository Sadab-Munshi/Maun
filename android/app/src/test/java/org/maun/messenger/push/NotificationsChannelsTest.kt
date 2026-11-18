package org.maun.messenger.push

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `a channel is only made again when its sound is there and cannot be opened`() {
        // A channel keeps its sound for ever, so an install that has been
        // through several versions can end up pointing at a sound that no
        // longer exists: that one is made again, once.
        val sound = android.net.Uri.parse(Notifications.maunSoundUri("org.maun.messenger"))
        assertTrue(Notifications.needsRepair(sound, readable = false))
        // A sound that opens is not touched.
        assertFalse(Notifications.needsRepair(sound, readable = true))
        // No sound at all is a choice — the silent channel, or the user's own —
        // and is never overridden.
        assertFalse(Notifications.needsRepair(null, readable = false))
        assertFalse(Notifications.needsRepair(null, readable = true))
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
