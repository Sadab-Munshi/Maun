package org.maun.messenger

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PayloadTest {

    @Test
    fun `a text message survives the round trip`() {
        val original = Payload.Text("3f2c9a1e-0000-4000-8000-000000000001", 1_794_700_000_000, "नमस्ते 👋 hello\nsecond line")
        assertEquals(original, Payload.decode(Payload.encode(original)))
    }

    @Test
    fun `the steps of a call survive the round trip`() {
        val id = "3f2c9a1e-0000-4000-8000-00000000000c"
        val steps = listOf(
            Payload.Call(id, Payload.CALL_OFFER, sdp = "v=0\r\no=- 46117 2 IN IP4 127.0.0.1\r\n"),
            Payload.Call(id, Payload.CALL_ANSWER, sdp = "v=0\r\n"),
            Payload.Call(id, Payload.CALL_ICE, candidate = Payload.Candidate("0", 0, "candidate:1 1 udp 41885439 198.51.100.7 50000 typ relay")),
            Payload.Call(id, Payload.CALL_HANGUP, reason = "declined"),
            Payload.Call(id, Payload.CALL_HANGUP),
            Payload.Call(id, Payload.CALL_BUSY),
            Payload.Call(id, Payload.CALL_VIDEO, reason = "on"),
            Payload.Call(id, Payload.CALL_VIDEO, reason = "off"),
        )
        for (step in steps) assertEquals(step, Payload.decode(Payload.encode(step)))
    }

    @Test
    fun `a video call's long description travels packed and comes out the same`() {
        val id = "3f2c9a1e-0000-4000-8000-00000000000d"
        // Shaped like a real one: many near-identical lines about codecs.
        val sdp = buildString {
            append("v=0\r\no=- 4611731400430051336 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\na=group:BUNDLE 0 1\r\n")
            for (payload in 96..127) {
                append("a=rtpmap:$payload H264/90000\r\na=rtcp-fb:$payload goog-remb\r\na=rtcp-fb:$payload transport-cc\r\n")
                append("a=rtcp-fb:$payload ccm fir\r\na=rtcp-fb:$payload nack\r\na=rtcp-fb:$payload nack pli\r\n")
                append("a=fmtp:$payload level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e0${payload.toString(16)}\r\n")
            }
        }
        assertTrue(sdp.length in 7000..Payload.MAX_SDP_CHARS)
        val offer = Payload.Call(id, Payload.CALL_OFFER, sdp = sdp, video = true)
        val sent = Payload.encode(offer)
        assertEquals(offer, Payload.decode(sent))
        // Small enough for one message to the relay, with room for the encryption around it.
        assertTrue("it was ${sent.size} bytes", sent.size < 3000)
        // A voice call's short description stays as it was, so older versions still read it.
        val voice = Payload.Call(id, Payload.CALL_OFFER, sdp = "v=0\r\n".repeat(100))
        assertTrue(String(Payload.encode(voice)).contains("\"sdp\""))
        assertEquals(voice, Payload.decode(Payload.encode(voice)))
    }

    @Test
    fun `a packed description that is broken or enormous is not taken`() {
        val id = "3f2c9a1e-0000-4000-8000-00000000000d"
        fun sent(packed: String): Payload {
            val json = org.json.JSONObject().put("v", 1).put("k", "call").put("call", id).put("type", "offer").put("sdpz", packed)
            return Payload.decode(Payload.pad(json.toString().toByteArray()))
        }
        assertEquals(Payload.Unknown, sent("not base64 !!"))
        assertEquals(Payload.Unknown, sent("AAAA"))
        // A few hundred bytes that unpack into far more than any description.
        val bomb = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.DeflaterOutputStream(out).use { it.write(ByteArray(2_000_000)) }
        }.toByteArray()
        assertEquals(Payload.Unknown, sent(java.util.Base64.getEncoder().encodeToString(bomb)))
    }

    @Test
    fun `call steps that say nothing, or too much, are not taken`() {
        val id = "3f2c9a1e-0000-4000-8000-00000000000c"
        fun sent(change: org.json.JSONObject.() -> Unit): Payload {
            val json = org.json.JSONObject().put("v", 1).put("k", "call").put("call", id).put("type", "offer").put("sdp", "v=0")
            json.change()
            return Payload.decode(Payload.pad(json.toString().toByteArray()))
        }
        assertTrue(sent { } is Payload.Call)
        assertEquals(Payload.Unknown, sent { remove("sdp") })
        assertEquals(Payload.Unknown, sent { put("sdp", "x".repeat(Payload.MAX_SDP_CHARS + 1)) })
        assertEquals(Payload.Unknown, sent { put("type", "explode") })
        assertEquals(Payload.Unknown, sent { put("call", "not an id!") })
        assertEquals(Payload.Unknown, sent { put("type", "ice").remove("sdp") })
        assertTrue(sent { put("video", true) }.let { it is Payload.Call && it.video })
    }

    @Test
    fun `receipts survive the round trip`() {
        val delivered = Payload.Receipt(delivered = listOf("a", "b"))
        val read = Payload.Receipt(read = listOf("c"))
        assertEquals(delivered, Payload.decode(Payload.encode(delivered)))
        assertEquals(read, Payload.decode(Payload.encode(read)))
    }

    @Test
    fun `a reaction survives the round trip, and only known emoji travel`() {
        val id = "3f2c9a1e-0000-4000-8000-00000000000e"
        val on = Payload.Reaction(id, Payload.REACTIONS.first(), 1_794_700_000_000)
        assertEquals(on, Payload.decode(Payload.encode(on)))
        // Taking one off travels as an empty emoji.
        val off = Payload.Reaction(id, "", 1_794_700_000_001)
        assertEquals(off, Payload.decode(Payload.encode(off)))

        // Anything else is from a newer Maun, or from someone using a reaction
        // as a way to send text: dropped, and saying nothing.
        fun raw(emoji: String): Payload {
            val json = org.json.JSONObject()
                .put("v", 1).put("k", "reaction").put("of", id).put("emoji", emoji).put("at", 5L)
            return Payload.decode(Payload.pad(json.toString().toByteArray(Charsets.UTF_8)))
        }
        assertEquals(Payload.Unknown, raw("hello"))
        assertEquals(Payload.Unknown, raw("\uD83D\uDE00".repeat(300)))
        // A mark on no message at all is dropped too.
        assertEquals(Payload.Unknown, raw2(of = "not a message id"))
    }

    private fun raw2(of: String): Payload {
        val json = org.json.JSONObject().put("v", 1).put("k", "reaction").put("of", of)
            .put("emoji", Payload.REACTIONS.first()).put("at", 5L)
        return Payload.decode(Payload.pad(json.toString().toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `length is hidden in steps of 160 bytes`() {
        fun size(text: String) = Payload.encode(Payload.Text("id", 0, text)).size
        assertEquals(size("a"), size("a much longer message than that"))
        assertEquals(0, size("a") % 160)
        assertEquals(0, size("x".repeat(1000)) % 160)
        assertTrue(size("x".repeat(1000)) > size("a"))
        // Even text that exactly fills a block gets its marker, in a new block.
        for (length in 0..400) {
            val padded = Payload.pad(ByteArray(length) { 7 })
            assertEquals(0, padded.size % 160)
            assertTrue(padded.size > length)
            assertArrayEquals(ByteArray(length) { 7 }, Payload.unpad(padded))
        }
    }

    @Test
    fun `padding that ends in zero bytes is not confused with content`() {
        val content = byteArrayOf(1, 0, 0)
        assertArrayEquals(content, Payload.unpad(Payload.pad(content)))
        assertNull(Payload.unpad(ByteArray(160)))
        assertNull(Payload.unpad(ByteArray(0)))
    }

    @Test
    fun `anything unreadable is unknown, never an error`() {
        val unknowns = listOf(
            ByteArray(0),
            ByteArray(160),
            Payload.pad("not json".toByteArray()),
            Payload.pad("""{"v":1,"k":"call-offer","sdp":"..."}""".toByteArray()),
            Payload.pad("""{"v":1,"k":"text","id":"ok","at":1}""".toByteArray()),
            Payload.pad("""{"v":1,"k":"text","id":"bad id!","at":1,"text":"x"}""".toByteArray()),
            Payload.pad("""{"v":1,"k":"text","id":"ok","text":"x"}""".toByteArray()),
            Payload.pad("[1,2,3]".toByteArray()),
        )
        unknowns.forEach { assertEquals(Payload.Unknown, Payload.decode(it)) }
    }

    @Test
    fun `receipts drop ids that could not be ours and stop at the limit`() {
        val many = (1..80).joinToString(",") { "\"id$it\"" }
        val decoded = Payload.decode(
            Payload.pad("""{"v":1,"k":"receipt","delivered":["fine","not fine",7,""],"read":[$many]}""".toByteArray()),
        ) as Payload.Receipt
        assertEquals(listOf("fine", "7"), decoded.delivered)
        assertEquals(Payload.MAX_RECEIPT_IDS, decoded.read.size)
        assertFalse(Payload.isMessageId("x".repeat(65)))
    }

    @Test
    fun `a profile picture record travels with its version, key and size`() {
        val picture = Payload.Avatar(
            version = "0123456789abcdef",
            key = Base64.getEncoder().encodeToString(ByteArray(32) { 3 }),
            size = 40_000,
        )
        assertEquals(picture, Payload.decode(Payload.encode(picture)))

        // The question "which picture do you have?", and the answer "none".
        assertEquals(Payload.Avatar(Payload.AVATAR_ASK), Payload.decode(Payload.encode(Payload.Avatar(Payload.AVATAR_ASK))))
        assertEquals(
            Payload.Avatar(Payload.AVATAR_NONE),
            Payload.decode(Payload.encode(Payload.Avatar(Payload.AVATAR_NONE))),
        )
    }

    @Test
    fun `a picture record that could not be used is unknown, not a picture`() {
        val bad = listOf(
            // Not a version the relay would ever give.
            """{"v":1,"k":"avatar","ver":"nope","key":"AAAA","size":10}""",
            // A picture with no key opens nothing.
            """{"v":1,"k":"avatar","ver":"0123456789abcdef","size":10}""",
            // Bigger than a picture can be, so it did not come this way.
            """{"v":1,"k":"avatar","ver":"0123456789abcdef","key":"AAAA","size":900000}""",
        )
        bad.forEach { assertEquals(Payload.Unknown, Payload.decode(Payload.pad(it.toByteArray()))) }
        assertTrue(Payload.isAvatarVersion("0123456789abcdef"))
        assertFalse(Payload.isAvatarVersion("0123456789ABCDEF"))
    }
}
