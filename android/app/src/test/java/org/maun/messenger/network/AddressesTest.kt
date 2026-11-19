package org.maun.messenger.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a geocoder's answer is turned into, which is the only part of it a card shows. */
class AddressesTest {

    // The answer Nominatim really gives for a village in Howrah: no name, no
    // road, just where it is and its postcode.
    private val village = """
        {
          "place_id": 250846783,
          "lat": "22.3218531",
          "lon": "88.0487218",
          "display_name": "Syampur, Shyampur - I, Howrah, West Bengal, 711314, India",
          "address": {
            "village": "Syampur",
            "county": "Shyampur - I",
            "state_district": "Howrah",
            "state": "West Bengal",
            "postcode": "711314",
            "country": "India",
            "country_code": "in"
          }
        }
    """.trimIndent()

    // And the answer for a place the map does know by name.
    private val temple = """
        {
          "name": "Balikuria Volebaba Temple",
          "display_name": "Balikuria Volebaba Temple, Manna Para Road, Shyampur - II, Howrah, 711312, India",
          "address": {
            "amenity": "Balikuria Volebaba Temple",
            "road": "Manna Para Road",
            "village": "Shyampur - II",
            "state_district": "Howrah",
            "postcode": "711312"
          }
        }
    """.trimIndent()

    @Test
    fun `a village with no name of its own is still said out loud`() {
        assertEquals("Syampur, Shyampur - I, Howrah, 711314", Addresses.addressFrom(village))
    }

    @Test
    fun `a place with a name leads with the name`() {
        assertEquals(
            "Balikuria Volebaba Temple, Manna Para Road, Shyampur - II, Howrah, 711312",
            Addresses.addressFrom(temple),
        )
    }

    @Test
    fun `a display name is the fallback when there is nothing structured`() {
        val plain = """{"display_name":"Syampur, Shyampur - I, Howrah, West Bengal, 711314, India"}"""
        assertEquals("Syampur, Shyampur - I, Howrah", Addresses.addressFrom(plain))
    }

    @Test
    fun `nothing is said when there is nothing to say`() {
        assertNull(Addresses.addressFrom("{}"))
        assertNull(Addresses.addressFrom("not json at all"))
        // A country on its own is not an address.
        assertNull(Addresses.addressFrom("""{"display_name":"India"}"""))
    }

    @Test
    fun `a name is never longer than a card can hold`() {
        val road = "Manna Para Road ".repeat(5).trim()     // 75 characters
        val village = "Balikuria ".repeat(8).trim()        // 71 characters
        val county = "Shyampur - II subdivision of Howrah" // 33 characters
        val line = Addresses.addressFrom(
            """{"address":{"road":"$road","village":"$village","county":"$county","postcode":"711312"}}""",
        )
        assertEquals(Addresses.MAX_LABEL, line!!.length)
        // Cut at the end, never at the start: the road is what a person reads first.
        assertTrue(line.startsWith("Manna Para Road"))
        assertFalse(line.endsWith(","))
    }
}
