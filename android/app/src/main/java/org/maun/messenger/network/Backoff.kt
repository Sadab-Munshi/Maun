package org.maun.messenger.network

import kotlin.random.Random

/**
 * How long to wait before reconnecting: doubling from [baseMillis] up to
 * [maxMillis], with random spread so that many phones cut off at the same
 * moment do not all come back at the same moment.
 */
class Backoff(
    private val baseMillis: Long = 1_000,
    private val maxMillis: Long = 60_000,
    private val random: Random = Random.Default,
) {
    /**
     * [failures] is how many attempts in a row have failed before this wait.
     * When the relay said "too many requests" the wait starts at the maximum.
     */
    fun delayMillis(failures: Int, rateLimited: Boolean = false): Long {
        val doubled = baseMillis shl failures.coerceIn(0, 20)
        val target = if (rateLimited) maxMillis else doubled.coerceAtMost(maxMillis)
        val spread = 0.75 + random.nextDouble() * 0.5 // 75% to 125%
        return (target * spread).toLong()
    }
}
