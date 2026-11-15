package org.maun.messenger.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodecTest {

    private val code = ContactCode("0123456789abcdef".repeat(4), "maun.example.workers.dev").encode()

    /**
     * Paints a QR code into a greyscale image the way a camera would deliver
     * it: [scale] pixels per module, a white border, and rows padded to
     * [stride] bytes with junk that must be ignored.
     */
    private fun paint(text: String, scale: Int, border: Int, extraStride: Int): Triple<ByteArray, Int, Int> {
        val matrix = QrCodec.encode(text)
        val side = (matrix.width + 2 * border) * scale
        val stride = side + extraStride
        val luma = ByteArray(stride * side) { 0x55 }
        for (y in 0 until side) {
            for (x in 0 until side) {
                val moduleX = x / scale - border
                val moduleY = y / scale - border
                val dark = moduleX in 0 until matrix.width && moduleY in 0 until matrix.height &&
                    matrix[moduleX, moduleY]
                luma[y * stride + x] = if (dark) 0x10.toByte() else 0xF0.toByte()
            }
        }
        return Triple(luma, stride, side)
    }

    @Test
    fun `a painted code is read back`() {
        val (luma, stride, side) = paint(code, scale = 4, border = 4, extraStride = 0)
        assertEquals(code, QrCodec.decode(luma, stride, side, side))
    }

    @Test
    fun `row padding is ignored`() {
        val (luma, stride, side) = paint(code, scale = 3, border = 6, extraStride = 13)
        assertEquals(code, QrCodec.decode(luma, stride, side, side))
    }

    @Test
    fun `the last row may stop at the image width`() {
        // Camera buffers often leave the padding off the final row.
        val (luma, stride, side) = paint(code, scale = 3, border = 6, extraStride = 13)
        val trimmed = luma.copyOf(stride * (side - 1) + side)
        assertEquals(code, QrCodec.decode(trimmed, stride, side, side))
    }

    @Test
    fun `an image without a code gives null`() {
        assertNull(QrCodec.decode(ByteArray(200 * 200) { 0x7F }, 200, 200, 200))
    }

    @Test
    fun `impossible sizes give null instead of crashing`() {
        assertNull(QrCodec.decode(ByteArray(10), 100, 100, 100))
        assertNull(QrCodec.decode(ByteArray(100), 5, 10, 10))
        assertNull(QrCodec.decode(ByteArray(0), 0, 0, 0))
    }

    @Test
    fun `the code is small enough to scan easily`() {
        // 41 modules is QR version 6. Bigger codes need a steadier hand.
        assertTrue(QrCodec.encode(code).width <= 41)
    }
}
