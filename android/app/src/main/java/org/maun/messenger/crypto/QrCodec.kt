package org.maun.messenger.crypto

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Makes and reads QR codes on the phone itself (ZXing); nothing is sent anywhere. */
object QrCodec {

    /** One cell per module, no border. The caller draws it and adds the quiet zone. */
    fun encode(text: String): BitMatrix =
        QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            0,
            0,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0),
        )

    /**
     * Looks for a QR code in a greyscale image. [luma] holds one brightness
     * byte per pixel in rows of [stride] bytes, which is what a camera's Y
     * plane looks like. Returns the code's text, or null when none is found.
     */
    fun decode(luma: ByteArray, stride: Int, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0 || stride < width) return null
        if (luma.size < stride * (height - 1) + width) return null
        val source = PlanarYUVLuminanceSource(luma, stride, height, 0, 0, width, height, false)
        val reader = QRCodeReader()
        return try {
            reader.decode(BinaryBitmap(HybridBinarizer(source)), DECODE_HINTS).text
        } catch (_: ReaderException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: ArrayIndexOutOfBoundsException) {
            // ZXing can run off the edge on a damaged or partial code.
            null
        }
    }

    private val DECODE_HINTS = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )
}
