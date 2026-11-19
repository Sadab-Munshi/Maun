package org.maun.messenger.push

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.maun.messenger.network.PushAddress
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What Maun does with the answers from what wakes this phone.
 *
 * An endpoint it cannot use is not a reason to forget the address that works:
 * the relay keeps waking this phone at the address it already has, and the
 * phone goes on receiving messages and calls while the push app sorts itself
 * out. Only a push app saying it has unregistered for good takes it away.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WakeServiceTest {

    /** What the app was told, in the order it was told. */
    private class Told : WakeReporter {
        val addresses = mutableListOf<PushAddress?>()
        val problems = mutableListOf<String>()

        override fun address(address: PushAddress?) {
            addresses += address
        }

        override fun problem(reason: String) {
            problems += reason
        }
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun service(told: Told): WakeService =
        Robolectric.buildService(WakeService::class.java).create().get().also { it.reporter = told }

    private val working = PushAddress("https://push.example.org/up1", "a2V5", "YXV0aA")

    @Test
    fun `an endpoint Maun cannot use keeps the address that works`() {
        val told = Told()
        service(told).took(null)

        assertTrue("the working address was taken away", told.addresses.isEmpty())
        assertEquals("the user was not told", 1, told.problems.size)
    }

    @Test
    fun `an endpoint Maun can use replaces the address`() {
        val told = Told()
        service(told).took(working)

        assertEquals(1, told.addresses.size)
        assertEquals(working, told.addresses[0])
        assertTrue("a working address was reported as a problem", told.problems.isEmpty())
    }

    @Test
    fun `a push app that says it is unregistered takes the address away`() {
        val told = Told()
        service(told).onUnregistered("default")

        assertEquals("the relay will go on waking a phone that cannot be woken", 1, told.addresses.size)
        assertNull(told.addresses[0])
    }
}
