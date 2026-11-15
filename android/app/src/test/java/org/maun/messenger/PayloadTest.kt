package org.maun.messenger

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
        )
        for (step in steps) assertEquals(step, Payload.decode(Payload.encode(step)))
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
        // The largest description still fits in one message to the relay.
        val largest = Payload.encode(Payload.Call(id, Payload.CALL_OFFER, sdp = "x".repeat(Payload.MAX_SDP_CHARS)))
        assertTrue(largest.size < 6000)
    }

    @Test
    fun `receipts survive the round trip`() {
        val delivered = Payload.Receipt(delivered = listOf("a", "b"))
        val read = Payload.Receipt(read = listOf("c"))
        assertEquals(delivered, Payload.decode(Payload.encode(delivered)))
        assertEquals(read, Payload.decode(Payload.encode(read)))
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
}
