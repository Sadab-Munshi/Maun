package org.maun.messenger.network

import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Test

class BackoffTest {

    @Test
    fun `waits double and then level off at the maximum`() {
        val backoff = Backoff(baseMillis = 1_000, maxMillis = 60_000, random = Random(1))
        val expected = listOf(1_000L, 2_000, 4_000, 8_000, 16_000, 32_000, 60_000, 60_000, 60_000)
        expected.forEachIndexed { failures, target ->
            repeat(50) {
                val wait = backoff.delayMillis(failures)
                assertTrue("failures=$failures wait=$wait", wait >= target * 3 / 4 && wait <= target * 5 / 4)
            }
        }
    }

    @Test
    fun `a huge failure count does not overflow`() {
        val backoff = Backoff(random = Random(2))
        for (failures in listOf(21, 63, 64, 1_000, Int.MAX_VALUE)) {
            val wait = backoff.delayMillis(failures)
            assertTrue("failures=$failures wait=$wait", wait in 45_000..75_000)
        }
        assertTrue(backoff.delayMillis(-5) in 750..1_250)
    }

    @Test
    fun `being rate limited waits the maximum straight away`() {
        val backoff = Backoff(random = Random(3))
        repeat(50) { assertTrue(backoff.delayMillis(0, rateLimited = true) in 45_000..75_000) }
    }

    @Test
    fun `waits are spread out, not identical`() {
        val backoff = Backoff(random = Random(4))
        assertTrue((1..20).map { backoff.delayMillis(3) }.toSet().size > 10)
    }
}
