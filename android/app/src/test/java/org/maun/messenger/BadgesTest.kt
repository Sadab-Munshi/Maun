package org.maun.messenger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ticks are decided by the relay, so all this phone does is remember what
 * it was last told and fold the next answer in carefully: a tick that has been
 * taken away has to go, and one that could not be asked about has to stay.
 */
class BadgesTest {

    @Test
    fun `nothing stored reads as nothing known`() {
        assertTrue(Badges.parse(null).isEmpty())
        assertTrue(Badges.parse("").isEmpty())
        assertTrue(Badges.parse("   ").isEmpty())
    }

    @Test
    fun `a store that cannot be read is not a crash and not a tick`() {
        assertTrue(Badges.parse("not json").isEmpty())
        assertTrue(Badges.parse("{").isEmpty())
    }

    @Test
    fun `what was stored comes back`() {
        val stored = Badges.encode(mapOf("aa" to true, "bb" to false, "cc" to true))
        assertEquals(mapOf("aa" to true, "cc" to true), Badges.parse(stored))
    }

    @Test
    fun `only the accounts that have the tick are written out`() {
        val stored = Badges.encode(mapOf("aa" to true, "bb" to false))
        assertTrue(stored.contains("aa"))
        assertTrue(!stored.contains("bb"))
    }

    @Test
    fun `a tick that has been taken away goes`() {
        val merged = Badges.merge(
            known = mapOf("aa" to true, "bb" to true),
            checked = mapOf("aa" to true, "bb" to false),
            stillContacts = listOf("aa", "bb"),
        )
        assertEquals(mapOf("aa" to true), merged)
    }

    @Test
    fun `a tick that was given arrives`() {
        val merged = Badges.merge(
            known = mapOf("aa" to true),
            checked = mapOf("aa" to true, "bb" to true),
            stillContacts = listOf("aa", "bb"),
        )
        assertEquals(mapOf("aa" to true, "bb" to true), merged)
    }

    @Test
    fun `an answer that could not be had leaves what was known alone`() {
        // The relay was not reached for "bb" at all, so nothing is assumed about it.
        val merged = Badges.merge(
            known = mapOf("bb" to true),
            checked = mapOf("aa" to false),
            stillContacts = listOf("aa", "bb"),
        )
        assertEquals(mapOf("bb" to true), merged)
    }

    @Test
    fun `someone who is no longer a contact keeps no tick`() {
        val merged = Badges.merge(
            known = mapOf("gone" to true, "here" to true),
            checked = mapOf("here" to true),
            stillContacts = listOf("here"),
        )
        assertEquals(mapOf("here" to true), merged)
    }

    @Test
    fun `a lookup never carries more ids than the relay takes`() {
        assertEquals(64, Badges.LOOKUP_MAX)
    }
}
