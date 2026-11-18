package org.maun.messenger.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a finished fetch leaves behind.
 *
 * The rule these hold to: a wake-up is worth a few quick tries, a phone that
 * still has something waiting after them gets one fetch for the moment a
 * network exists, and nothing arranges work for ever.
 */
class FetchStepTest {

    private val max = 3

    /**
     * Follows what the worker would do with each step: a retry comes back with
     * the next attempt number and without the waiting mark, a wait-for-network
     * fetch comes back as the first attempt of a fetch that waited. Returns the
     * steps taken until one stops.
     */
    private fun follow(caughtUp: Boolean, unsent: Int, waitingForNetwork: Boolean): List<FetchStep> {
        val steps = mutableListOf<FetchStep>()
        var attempt = 0
        var waiting = waitingForNetwork
        repeat(10) {
            val step = nextStep(caughtUp, unsent, attempt, max, waiting)
            steps += step
            when (step) {
                FetchStep.Again -> {
                    attempt++
                    waiting = false
                }
                FetchStep.WaitForNetwork -> {
                    attempt = 0
                    waiting = true
                }
                FetchStep.Stop -> return steps
            }
        }
        return steps
    }

    @Test
    fun `a fetch that caught up leaves nothing behind`() {
        assertEquals(listOf(FetchStep.Stop), follow(caughtUp = true, unsent = 0, waitingForNetwork = false))
        // Also when it was the fetch that had waited for a network.
        assertEquals(listOf(FetchStep.Stop), follow(caughtUp = true, unsent = 0, waitingForNetwork = true))
    }

    @Test
    fun `a phone that is still behind tries again quickly`() {
        assertEquals(FetchStep.Again, nextStep(caughtUp = false, unsent = 0, attempt = 0, maxAttempts = max, waitingForNetwork = false))
    }

    @Test
    fun `the last try leaves one fetch for the moment there is a network`() {
        assertEquals(
            FetchStep.WaitForNetwork,
            nextStep(caughtUp = false, unsent = 0, attempt = max - 1, maxAttempts = max, waitingForNetwork = false),
        )
    }

    @Test
    fun `a fetch that still has to send something is not finished either`() {
        assertEquals(
            FetchStep.WaitForNetwork,
            nextStep(caughtUp = true, unsent = 2, attempt = max - 1, maxAttempts = max, waitingForNetwork = false),
        )
    }

    @Test
    fun `a relay that cannot be reached is given up on, not fetched for ever`() {
        // Never caught up, never sent: the worst case. One round of quick
        // tries, one fetch that waits for a network, and then nothing.
        val steps = follow(caughtUp = false, unsent = 4, waitingForNetwork = false)
        assertEquals(FetchStep.Stop, steps.last())
        assertEquals(1, steps.count { it == FetchStep.WaitForNetwork })
        assertTrue("a fetch must not keep arranging work: $steps", steps.size <= 2 * max)
    }

    @Test
    fun `the fetch that waited for a network does not arrange another once its tries are over`() {
        val last = nextStep(caughtUp = false, unsent = 4, attempt = max - 1, maxAttempts = max, waitingForNetwork = true)
        assertEquals(FetchStep.Stop, last)
    }
}
