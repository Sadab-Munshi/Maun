package org.maun.messenger.push

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The trace exists so that a delay can be placed on the map without anything
 * private being written down: a random name for the event, and timestamps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class DeliveryTraceTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `nothing has been traced before the first wake-up`() {
        assertNull(DeliveryTrace.last(context))
        // And a step with no event to belong to is simply not written.
        DeliveryTrace.stepped(context, DeliveryTrace.SHOWN)
        assertNull(DeliveryTrace.last(context))
    }

    @Test
    fun `a wake-up names the event it starts`() {
        val id = DeliveryTrace.began(context, DeliveryTrace.WAKE)
        assertEquals(8, id.length)
        assertEquals(id, DeliveryTrace.last(context))
        assertTrue(id.all { it.isLetterOrDigit() || it == '-' })
    }

    @Test
    fun `each wake-up is a new event`() {
        val first = DeliveryTrace.began(context, DeliveryTrace.WAKE)
        val second = DeliveryTrace.began(context, DeliveryTrace.CALL)
        assertTrue(first != second)
        assertEquals(second, DeliveryTrace.last(context))
    }

    @Test
    fun `the names of the steps say what happened and nothing more`() {
        assertEquals(
            listOf("wake", "call", "fetch-start", "fetch-done", "fetch-late", "shown"),
            listOf(
                DeliveryTrace.WAKE,
                DeliveryTrace.CALL,
                DeliveryTrace.FETCH_START,
                DeliveryTrace.FETCH_DONE,
                DeliveryTrace.FETCH_LATE,
                DeliveryTrace.SHOWN,
            ),
        )
    }
}
