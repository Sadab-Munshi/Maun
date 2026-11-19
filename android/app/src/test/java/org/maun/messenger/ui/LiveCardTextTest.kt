package org.maun.messenger.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maun.messenger.LivePoint
import org.maun.messenger.LiveSeal
import org.maun.messenger.LiveShare
import org.maun.messenger.data.ChatSummary
import org.maun.messenger.data.MessageKind

/** The words on a live-location card, which are the only thing most people will read. */
class LiveCardTextTest {

    private val share = "3f2c9a1e7b4d4c0e9a1f2b3c4d5e6f70"

    @Test
    fun `what is left of a share is said in minutes`() {
        assertEquals("15 min", left(15 * 60_000L))
        assertEquals("14 min", left(14 * 60_000L - 1))
        assertEquals("1 min", left(60_000L))
        assertEquals("under a minute", left(20_000L))
        assertEquals("no time", left(-1L))
    }

    @Test
    fun `how long a share is started for is said in hours, up to a day`() {
        // A walk home is not over in a quarter of an hour: the offers run to a day.
        assertEquals(listOf(15, 60, 240, 480, 1440), LiveShare.CHOICES)
        assertEquals("15 minutes", howLong(15))
        assertEquals("1 hour", howLong(60))
        assertEquals("1 h 30 min", howLong(90))
        assertEquals("4 hours", howLong(240))
        assertEquals("8 hours", howLong(480))
        assertEquals("24 hours", howLong(1440))
        for (minutes in LiveShare.CHOICES) {
            assertFalse("a share is never offered for nothing", howLong(minutes).contains("0 minutes"))
        }
    }

    @Test
    fun `how long ago a point came is said in the plainest way`() {
        val now = 1_000_000L
        assertEquals("nothing yet", since(null, now))
        assertEquals("just now", since(now - 2_000, now))
        assertEquals("20s ago", since(now - 20_000, now))
        assertEquals("3 min ago", since(now - 150_000, now))
    }

    @Test
    fun `a place is written so that it can be found again`() {
        val point = LivePoint(51.50731, -0.12772, 12.0, 1_794_699_000_000)
        assertEquals("51.50731, -0.12772 (±12 m)", place(point))
        // A phone that would not say how sure it was says nothing about it.
        assertEquals("51.50731, -0.12772", place(point.copy(accuracy = 0.0)))
    }

    @Test
    fun `the list of conversations calls a location a location, not its record`() {
        val live = LiveShare(share, LiveSeal.newKey(), 15, System.currentTimeMillis() + 60_000)
        assertEquals("Live location", previewOf(summary(live.toLocal())))
        val stopped = live.copy(state = LiveShare.STOPPED)
        assertEquals("Live location ended", previewOf(summary(stopped.toLocal())))
        val over = live.copy(until = System.currentTimeMillis() - 1)
        assertEquals("Live location ended", previewOf(summary(over.toLocal())))
        // A record this version cannot read is still a location, not JSON on screen.
        assertEquals("Live location", previewOf(summary("{}")))
    }

    @Test
    fun `a distance is said the way a person would say it`() {
        assertEquals("0 m away", howFar(0.0))
        assertEquals("180 m away", howFar(180.4))
        assertEquals("949 m away", howFar(949.0))
        assertEquals("1.0 km away", howFar(1_000.0))
        assertEquals("1.4 km away", howFar(1_430.0))
        assertEquals("9.9 km away", howFar(9_940.0))
        assertEquals("10 km away", howFar(10_200.0))
        assertEquals("131 km away", howFar(131_400.0))
        assertEquals("an unknown distance away", howFar(Double.NaN))
        assertEquals("an unknown distance away", howFar(-5.0))
    }

    @Test
    fun `a place sent once says so in the list, and does not pretend to be a share`() {
        val place = org.maun.messenger.Place(22.37884, 87.98385, 12.0, System.currentTimeMillis())
        assertEquals("Location", previewOf(summary(place.toLocal())))
        // Nothing in it can be mistaken for a share that ends at a minute.
        assertNull(org.maun.messenger.LiveShare.fromLocal(place.toLocal()))
    }

    @Test
    fun `a location's record never appears in the list`() {
        val live = LiveShare(share, LiveSeal.newKey(), 15, System.currentTimeMillis() + 60_000)
        val preview = previewOf(summary(live.toLocal()))
        assertFalse(preview.contains("share"))
        assertFalse(preview.contains("{"))
        assertTrue(preview.startsWith("Live location"))
    }

    private fun summary(text: String) = ChatSummary(
        peer = "a".repeat(64),
        seq = 1,
        kind = MessageKind.LOCATION,
        text = text,
        outgoing = true,
        status = 1,
        at = 1_794_700_000_000,
        unread = 0,
    )
}
