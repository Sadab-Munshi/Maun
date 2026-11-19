package org.maun.messenger.webrtc

import android.app.Application
import android.app.Notification
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.maun.messenger.push.Notifications
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * What a start of [RingService] may do to the notification that is already
 * there.
 *
 * The relay looks for a phone that has not come twice more after a call wake
 * (8 s, then 20 s), and each of those says "a call" again. So "get ready for a
 * call" is repeated while the call is ringing. It must be harmless: the
 * ringing notification is the one thing standing between a locked phone and a
 * missed call, and a second get-ready that replaces it with the silent one
 * takes the ring away.
 *
 * What this cannot check is whether Android still makes a sound — that is the
 * device test. It checks the notification the service is showing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RingServiceTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** What a wake-up that says "a call" starts: the service, with no action. */
    private fun getReady(): Intent = Intent(context, RingService::class.java)

    /** What the call itself starts when it has been fetched. */
    private fun ring(): Intent = Intent(context, RingService::class.java).setAction(RingService.ACTION_RING)

    /** A service behind an incoming call that is ringing, unless a test says otherwise. */
    private fun begin(ringing: Boolean = true): ServiceController<RingService> =
        Robolectric.buildService(RingService::class.java, getReady()).create().also {
            it.get().callStillRinging = { ringing }
        }

    /** The notification the service is showing, as Android would show it. */
    private fun shown(controller: ServiceController<RingService>): Notification? =
        shadowOf(controller.get()).lastForegroundNotification

    private fun isRinging(notification: Notification): Boolean =
        notification.flags and Notification.FLAG_INSISTENT != 0

    private fun text(notification: Notification): String? =
        notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    @Test
    fun `a call that is ready to ring rings`() {
        val controller = begin()
        controller.withIntent(getReady()).startCommand(0, 0)
        controller.withIntent(ring()).startCommand(0, 0)

        val notification = shown(controller)
        assertNotNull("nothing was shown", notification)
        assertTrue("the phone was not told to ring", isRinging(notification!!))
    }

    @Test
    fun `a second get-ready while the call is ringing leaves the ringing notification in place`() {
        val controller = begin()
        controller.withIntent(getReady()).startCommand(0, 0)
        controller.withIntent(ring()).startCommand(0, 0)
        // The relay's own look, 8 s later, saying "a call" again.
        controller.withIntent(getReady()).startCommand(0, 0)

        val notification = shown(controller)
        assertNotNull("nothing was shown", notification)
        assertTrue("the second get-ready took the ringing away", isRinging(notification!!))
        assertEquals(
            "the call stopped ringing and was announced as coming instead",
            Notifications.ID_RINGING,
            shadowOf(controller.get()).lastForegroundNotificationId,
        )
    }

    @Test
    fun `a get-ready after the ringing does not put its own words on the call`() {
        val controller = begin()
        controller.withIntent(getReady()).startCommand(0, 0)
        controller.withIntent(ring()).startCommand(0, 0)
        val ringing = shown(controller)
        assertNotNull(ringing)

        controller.withIntent(getReady()).startCommand(0, 0)
        val after = shown(controller)
        assertNotNull(after)
        assertEquals("the notification on screen was replaced", text(ringing!!), text(after!!))
        assertNotEquals("the call was announced as still coming", "Incoming call…", text(after))
    }

    @Test
    fun `a call that has stopped ringing takes the service away`() {
        val controller = begin(ringing = false)
        controller.withIntent(ring()).startCommand(0, 0)
        assertTrue("the service stayed for a call that was over", shadowOf(controller.get()).isStoppedBySelf)
    }
}
