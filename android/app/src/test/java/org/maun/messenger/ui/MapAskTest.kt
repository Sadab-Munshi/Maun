package org.maun.messenger.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maun.messenger.LivePoint

/**
 * What the map says at the top of the screen, and the two dots on it: the two
 * things a person has to understand before the map is worth looking at.
 */
class MapAskTest {

    private val somewhere = LivePoint(22.35732, 87.98967, 500.0, 1_770_000_000_000L)

    @Test
    fun `the top of the map is the person, and the line under it says what it is`() {
        // A card title is a sentence; a map bar on a phone held in one hand is not.
        val ask = MapAsk(somewhere, "Sabad1's live location", "Sabad1")
        assertEquals("Sabad1", mapHeadline(ask))
        assertEquals("Live location", mapSubtitle(ask))
        assertTrue("nothing wraps in the bar", mapHeadline(ask).length < "Sabad1's live location".length)
    }

    @Test
    fun `a place with no name of its own is still called something`() {
        val ask = MapAsk(somewhere, "Where you were")
        assertEquals("Where you were", mapHeadline(ask))
        assertEquals("", mapSubtitle(ask))
    }

    @Test
    fun `a name written under a dot is short enough to leave the street readable`() {
        assertEquals("Sabad1", mapLabel("Sabad1"))
        // A card with no name at all still puts something under the dot.
        assertEquals("Their place", mapLabel("  "))
        val long = mapLabel("Sabad1's live location, somewhere out past Antila")
        assertTrue("a name that long would cover the map", long.length <= MAX_NAME)
        assertTrue("and it says that it was cut", long.endsWith("…"))
        assertFalse("cut at a space, never in the middle of a word", long.endsWith(" …"))
        // This phone's own dot is always the same word.
        assertEquals("You", MY_NAME)
    }

    @Test
    fun `the two dots are never the same size, and never the same colour`() {
        // Bigger is the other phone, smaller is this one: read once, understood.
        assertTrue(THEIR_DOT_RADIUS > MY_DOT_RADIUS)
        // And the other dot is red, which no road, water or park on the map is.
        val red = (THEIR_DOT shr 16) and 0xFF
        val green = (THEIR_DOT shr 8) and 0xFF
        val blue = THEIR_DOT and 0xFF
        assertTrue("red has to stand out", red > 0xB0)
        assertTrue("and be nothing like the pale map underneath it", red > green + 0x60 && red > blue + 0x60)
        // The app's own two greens are neither of them red.
        assertNotEquals(0xFF2F6B55.toInt(), THEIR_DOT)
        assertNotEquals(0xFF9FD8C0.toInt(), THEIR_DOT)
    }
}
