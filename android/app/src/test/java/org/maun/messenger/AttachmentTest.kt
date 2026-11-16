package org.maun.messenger

import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentTest {

    private val secret = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
    private val file = Attachment(key = "ab".repeat(32), secret = secret, name = "Trip photo.jpg", mime = "image/jpeg", size = 123_456)

    @Test
    fun `a file message survives the round trip`() {
        val original = Payload.File("3f2c9a1e-0000-4000-8000-000000000001", 1_794_700_000_000, file)
        assertEquals(original, Payload.decode(Payload.encode(original)))
    }

    @Test
    fun `what is sent says nothing about where the file is on this phone`() {
        val sent = file.copy(state = Attachment.UPLOADING).toWire()
        assertFalse(sent.has("state"))
        // The receiver always starts with "not fetched yet".
        assertEquals(Attachment.REMOTE, Attachment.fromWire(sent)!!.state)
    }

    @Test
    fun `the record kept on this phone remembers its state`() {
        val kept = file.copy(state = Attachment.READY)
        assertEquals(kept, Attachment.fromLocal(kept.toLocal()))
        // Before the upload there is no name on the relay yet.
        val waiting = file.copy(key = "", state = Attachment.UPLOADING)
        assertEquals(waiting, Attachment.fromLocal(waiting.toLocal()))
        assertNull(Attachment.fromLocal("not json"))
    }

    @Test
    fun `a picture's size travels with it, so the other phone can keep room for it`() {
        val photo = file.copy(width = 4000, height = 3000)
        assertEquals(photo, Attachment.fromWire(photo.toWire()))
        assertEquals(photo.copy(state = Attachment.READY), Attachment.fromLocal(photo.copy(state = Attachment.READY).toLocal()))
        // Nonsense sizes are not believed.
        val odd = Attachment.fromWire(file.toWire().put("w", -5).put("h", 9_999_999))!!
        assertEquals(0, odd.width)
        assertEquals(50_000, odd.height)
        // A file without a size says nothing about one.
        assertFalse(file.toWire().has("w"))
    }

    @Test
    fun `a voice message says how long it is, and only sound counts as one`() {
        val voice = file.copy(name = "Voice message.ogg", mime = Attachment.VOICE_MIME, duration = 12_345)
        assertTrue(voice.isVoice)
        assertEquals(voice, Attachment.fromWire(voice.toWire()))
        assertEquals(voice.copy(state = Attachment.READY), Attachment.fromLocal(voice.copy(state = Attachment.READY).toLocal()))
        // A picture that claims a length is still a picture, and a length out of range is cut down.
        assertFalse(file.copy(duration = 5_000).isVoice)
        assertFalse(file.copy(mime = Attachment.VOICE_MIME).isVoice)
        assertEquals(Attachment.MAX_VOICE_MILLIS, Attachment.fromWire(voice.toWire().put("dur", 999_999_999))!!.duration)
        assertEquals(0, Attachment.fromWire(voice.toWire().put("dur", -5))!!.duration)
        assertFalse(file.toWire().has("dur"))
    }

    @Test
    fun `records that cannot be a file are refused`() {
        fun wire(change: JSONObject.() -> Unit) = Attachment.fromWire(file.toWire().apply(change))
        assertTrue(wire { } != null)
        assertNull(wire { put("key", "../../etc/passwd") })
        assertNull(wire { put("key", "AB".repeat(32)) })
        assertNull(wire { put("secret", "short") })
        assertNull(wire { put("secret", "not base64 !!") })
        assertNull(wire { put("size", -1) })
        assertNull(wire { put("size", Attachment.MAX_BYTES + 1) })
        assertNull(wire { remove("size") })
    }

    @Test
    fun `a name cannot point outside or hide control characters`() {
        assertEquals("passwd", Attachment.cleanName("../../etc/passwd"))
        assertEquals("evil.exe", Attachment.cleanName("C:\\Users\\me\\evil.exe"))
        assertEquals("ab", Attachment.cleanName("a\u0000\nb"))
        assertEquals("file", Attachment.cleanName("   "))
        assertEquals(120, Attachment.cleanName("x".repeat(500)).length)
        val received = Attachment.fromWire(file.toWire().put("name", "/sdcard/secret/../a.txt").put("mime", ""))!!
        assertEquals("a.txt", received.name)
        assertEquals("application/octet-stream", received.mime)
    }

    @Test
    fun `only picture types Android can show count as pictures`() {
        assertTrue(file.isImage)
        assertTrue(file.copy(mime = "image/png").isImage)
        assertFalse(file.copy(mime = "image/svg+xml").isImage)
        assertFalse(file.copy(mime = "application/pdf").isImage)
    }

    @Test
    fun `gallery media covers photos and videos while documents go to Downloads`() {
        assertTrue(file.isGalleryMedia)
        assertFalse(file.isVideo)

        val video = file.copy(name = "clip.mp4", mime = "video/mp4")
        assertTrue(video.isVideo)
        assertTrue(video.isGalleryMedia)
        assertEquals("Saved to Gallery", MediaSaver.savedNotice(file))
        assertEquals("Saved to Gallery", MediaSaver.savedNotice(video))
        assertEquals("Already saved to Gallery", MediaSaver.savedNotice(video, alreadySaved = true))

        val doc = file.copy(name = "Tickets.pdf", mime = "application/pdf")
        assertFalse(doc.isGalleryMedia)
        assertEquals("Saved to Downloads/Maun", MediaSaver.savedNotice(doc))
        assertEquals("Already saved to Downloads/Maun", MediaSaver.savedNotice(doc, alreadySaved = true))
    }
}
