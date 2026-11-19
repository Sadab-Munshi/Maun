package org.maun.messenger

import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveLocationTest {

    private val share = "3f2c9a1e7b4d4c0e9a1f2b3c4d5e6f70"
    private val until = 1_794_700_000_000

    private fun point(lat: Double = 51.50731, lon: Double = -0.12772, accuracy: Double = 12.0) =
        LivePoint(lat, lon, accuracy, 1_794_699_000_000)

    @Test
    fun `a point is sealed on the phone and comes back exactly as it went`() {
        val key = LiveSeal.newKey()
        val original = point()
        val sealed = LiveSeal.seal(key, share, original)
        // A latitude, a longitude, an accuracy and a time, sealed: no longer
        // than the relay takes, and with nothing of the numbers in it to read.
        assertTrue(sealed.size in 1..512)
        assertEquals(original, LiveSeal.open(key, share, sealed))
    }

    @Test
    fun `a sealed point is not a place anybody could read out of it`() {
        val key = LiveSeal.newKey()
        val sealed = LiveSeal.seal(key, share, point())
        val body = String(sealed, Charsets.ISO_8859_1)
        assertFalse(body.contains("51.50731"))
        assertFalse(body.contains("lat"))
    }

    @Test
    fun `another key does not open it`() {
        val sealed = LiveSeal.seal(LiveSeal.newKey(), share, point())
        assertNull(LiveSeal.open(LiveSeal.newKey(), share, sealed))
    }

    @Test
    fun `a point cannot be moved from one share into another`() {
        val key = LiveSeal.newKey()
        val sealed = LiveSeal.seal(key, share, point())
        val other = "0".repeat(32)
        assertNull(LiveSeal.open(key, other, sealed))
    }

    @Test
    fun `rubbish where a point should be opens to nothing`() {
        val key = LiveSeal.newKey()
        assertNull(LiveSeal.open(key, share, ByteArray(0)))
        assertNull(LiveSeal.open(key, share, ByteArray(8)))
        assertNull(LiveSeal.open(key, share, ByteArray(200) { 7 }))
        // A key that is not even a key.
        assertNull(LiveSeal.open("not-a-key", share, ByteArray(200) { 7 }))
    }

    @Test
    fun `a share is remembered in its message and read back whole`() {
        val original = LiveShare(share, LiveSeal.newKey(), 15, until)
        assertEquals(original, LiveShare.fromLocal(original.toLocal()))
        val stopped = original.copy(state = LiveShare.STOPPED)
        assertEquals(stopped, LiveShare.fromLocal(stopped.toLocal()))
    }

    @Test
    fun `a message that is not a share reads as nothing`() {
        assertNull(LiveShare.fromLocal(""))
        assertNull(LiveShare.fromLocal("hello"))
        assertNull(LiveShare.fromLocal("{}"))
        // A share ID that is not one, and no key: neither is a share.
        assertNull(LiveShare.fromLocal("""{"share":"nope","key":"x","mins":15,"until":1}"""))
        assertNull(LiveShare.fromLocal("""{"share":"$share","key":"","mins":15,"until":1}"""))
        assertNull(LiveShare.fromLocal("""{"share":"$share","key":"x","mins":15,"until":0}"""))
    }

    @Test
    fun `a share changes what it says without the phone being told anything new`() {
        val now = 1_000_000L
        val live = LiveShare(share, LiveSeal.newKey(), 15, now + 60_000)

        // The phone that shares: it is sharing until the minute it promised.
        assertEquals(LivePhase.SHARING, live.phase(outgoing = true, now = now))
        // The other phone, before it has opened the card.
        assertEquals(LivePhase.OFFER, live.phase(outgoing = false, now = now))
        // After it has.
        assertEquals(LivePhase.WATCHING, live.copy(state = LiveShare.WATCHING).phase(outgoing = false, now = now))
        // Stopped, from either side.
        assertEquals(LivePhase.ENDED, live.copy(state = LiveShare.STOPPED).phase(outgoing = true, now = now))
        assertEquals(LivePhase.ENDED, live.copy(state = LiveShare.STOPPED).phase(outgoing = false, now = now))
    }

    @Test
    fun `the minute running out ends a share that nobody stopped`() {
        val now = 1_000_000L
        val live = LiveShare(share, LiveSeal.newKey(), 15, now)
        assertEquals(LivePhase.ENDED, live.phase(outgoing = true, now = now))
        assertEquals(LivePhase.ENDED, live.phase(outgoing = false, now = now + 1))
    }

    @Test
    fun `a point that stopped coming says so rather than a place`() {
        val now = 1_000_000L
        val fresh = LiveView(LiveState.LIVE, now + 60_000, point().copy(at = now - 20_000))
        assertFalse(fresh.stale(now))
        val old = LiveView(LiveState.LIVE, now + 60_000, point().copy(at = now - LiveView.STALE_MS - 1))
        assertTrue(old.stale(now))
        // No point at all is not a place either.
        assertTrue(LiveView(LiveState.LIVE, now + 60_000, null).stale(now))
    }

    @Test
    fun `a point survives the wire in the relay's own words`() {
        val original = point()
        assertEquals(original, LivePoint.fromJson(original.toJson()))
        // What the relay would hand back is base64 of the sealed bytes, and it
        // is the seal, not this, that keeps a place from being read.
        val sealed = LiveSeal.seal(LiveSeal.newKey(), share, original)
        assertNotNull(Base64.getEncoder().encodeToString(sealed))
    }

    @Test
    fun `a point from somewhere that does not exist is not a point`() {
        assertNull(LivePoint.fromJson(""))
        assertNull(LivePoint.fromJson("{}"))
        assertNull(LivePoint.fromJson("""{"la":91.0,"lo":0.0,"ac":5.0,"at":1}"""))
        assertNull(LivePoint.fromJson("""{"la":0.0,"lo":181.0,"ac":5.0,"at":1}"""))
        assertNull(LivePoint.fromJson("""{"la":0.0,"lo":0.0,"ac":5.0,"at":0}"""))
    }

    @Test
    fun `a share's steps survive the round trip`() {
        val id = "3f2c9a1e-0000-4000-8000-0000000000aa"
        val key = LiveSeal.newKey()
        val steps = listOf(
            Payload.LiveLocation(id, 1_794_700_000_000, share, Payload.LIVE_SHARE, key, 15, until),
            Payload.LiveLocation(id, 1_794_700_000_000, share, Payload.LIVE_SHARE, key, 60, until),
            // The two lines that only move a card from one state to the next
            // carry neither the key nor a length of time.
            Payload.LiveLocation(id, 1_794_700_000_000, share, Payload.LIVE_SEEN, minutes = 0),
            Payload.LiveLocation(id, 1_794_700_000_000, share, Payload.LIVE_STOP, minutes = 0),
        )
        for (step in steps) assertEquals(step, Payload.decode(Payload.encode(step)))
    }

    @Test
    fun `a share without the key that opens it is dropped`() {
        val id = "3f2c9a1e-0000-4000-8000-0000000000ab"
        // No key at all, a key of the wrong length, and a length of time the
        // relay would never have given — a day is the longest it will: none of
        // them is a share.
        val noKey = """{"v":1,"k":"place","id":"$id","at":1,"share":"$share","type":"share","mins":15,"until":$until}"""
        val shortKey = """{"v":1,"k":"place","id":"$id","at":1,"share":"$share","type":"share","key":"c2hvcnQ=","mins":15,"until":$until}"""
        val tooLong = """{"v":1,"k":"place","id":"$id","at":1,"share":"$share","type":"share","key":"${LiveSeal.newKey()}","mins":1441,"until":$until}"""
        for (json in listOf(noKey, shortKey, tooLong)) {
            assertEquals(Payload.Unknown, Payload.decode(Payload.pad(json.toByteArray(Charsets.UTF_8))))
        }
    }

    @Test
    fun `a share may be started for a day, because getting home at night takes longer than an hour`() {
        // The longest the relay will take, and it survives the round trip.
        val day = LiveShare(share, LiveSeal.newKey(), 24 * 60, until)
        assertEquals(day, LiveShare.fromLocal(day.toLocal()))
        val payload = Payload.LiveLocation(
            "3f2c9a1e-0000-4000-8000-0000000000ac",
            1_794_700_000_000,
            share,
            Payload.LIVE_SHARE,
            day.key,
            24 * 60,
            until,
        )
        assertEquals(payload, Payload.decode(Payload.encode(payload)))

        // Longer than a day is not a share this version knows.
        val json = JSONObject()
            .put("v", 1)
            .put("k", "place")
            .put("id", "3f2c9a1e-0000-4000-8000-0000000000ad")
            .put("at", 1)
            .put("share", share)
            .put("type", "share")
            .put("key", day.key)
            .put("mins", 5000)
            .put("until", until)
            .toString()
        assertEquals(Payload.Unknown, Payload.decode(Payload.pad(json.toByteArray(Charsets.UTF_8))))
    }

    @Test
    fun `how far away somebody is comes from two points and nothing else`() {
        val here = LivePoint(22.3218, 88.0487, 12.0, 1_794_700_000_000)
        // The same place twice is nothing at all.
        assertEquals(0.0, metresBetween(here, here), 0.001)
        // A degree of latitude is about 111.2 km anywhere on the sphere.
        val north = LivePoint(23.3218, 88.0487, 5.0, 1_794_700_000_000)
        assertEquals(111_195.0, metresBetween(here, north), 200.0)
        // And it does not care which of the two is called first.
        assertEquals(metresBetween(here, north), metresBetween(north, here), 0.001)
        // A kilometre up the road, near enough.
        val up = LivePoint(22.3308, 88.0487, 5.0, 1_794_700_000_000)
        val metres = metresBetween(here, up)
        assertTrue("expected about a kilometre, got $metres", metres in 900.0..1_100.0)
    }

    @Test
    fun `a place is sent once, and is a message rather than a share`() {
        val place = Place(22.37884, 87.98385, 12.0, 1_794_700_000_000)
        assertEquals(place, Place.fromLocal(place.toLocal()))

        // It travels as an ordinary encrypted message: no share, no key, no
        // length of time, and nothing for the relay to keep.
        val payload = Payload.Pin("3f2c9a1e-0000-4000-8000-0000000000ae", 1_794_700_000_000, place)
        assertEquals(payload, Payload.decode(Payload.encode(payload)))

        // Whatever is not a place on this earth is not one.
        assertNull(Place.fromLocal(""))
        assertNull(Place.fromLocal("{}"))
        assertNull(Place.fromLocal("""{"lat":91.0,"lon":0.0,"ac":5.0,"at":1}"""))
        assertNull(Place.fromLocal("""{"lat":0.0,"lon":181.0,"ac":5.0,"at":1}"""))
        assertNull(Place.fromLocal("""{"lat":0.0,"lon":0.0,"ac":5.0,"at":0}"""))
    }

    @Test
    fun `a place that arrived is not a live share`() {
        // The two are told apart by what the record says, so a place never
        // shows a countdown, a Stop button or anything asking the relay.
        val place = Place(22.37884, 87.98385, 12.0, 1_794_700_000_000)
        assertNull(LiveShare.fromLocal(place.toLocal()))
        assertNull(Place.fromLocal(LiveShare(share, LiveSeal.newKey(), 15, until).toLocal()))
    }

    @Test
    fun `a share's ID is the shape the relay takes, and new every time`() {
        val first = LiveSeal.newShareId()
        val second = LiveSeal.newShareId()
        assertTrue(Regex("^[0-9a-f]{32}$").matches(first))
        assertFalse(first == second)
    }
}
