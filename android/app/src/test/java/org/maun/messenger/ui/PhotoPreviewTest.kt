package org.maun.messenger.ui

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maun.messenger.Attachment
import org.maun.messenger.data.MessageEntity

class PhotoPreviewTest {
    private val file = Attachment(
        key = "relay-key", secret = "", name = "photo.jpg", mime = "image/jpeg",
        size = 1024, state = Attachment.READY,
    )
    private val outgoing = MessageEntity(peer = "peer", id = "photo", outgoing = true, text = "", at = 1)

    @Test
    fun `outgoing photo stays visible while the uploaded message is being finalized`() {
        assertTrue(isPictureBubble(outgoing, file, FileStep.Working))
        assertTrue(isPictureBubble(outgoing, file, FileStep.Open))
        assertFalse(isPictureBubble(outgoing, file, FileStep.Gone))
    }

    @Test
    fun `outgoing preview retries when message is visible before file is ready`() = runBlocking {
        var calls = 0
        val result = loadPictureWhenAvailable(outgoing = true) { if (++calls == 2) "picture" else null }
        assertEquals("picture", result)
        assertEquals(2, calls)
    }

    @Test
    fun `incoming preview does not retry a missing file`() = runBlocking {
        var calls = 0
        assertNull(loadPictureWhenAvailable<String>(outgoing = false) { calls++; null })
        assertEquals(1, calls)
    }
}
