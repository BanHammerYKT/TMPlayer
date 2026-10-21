package com.tmplayer.data

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** The browser build's own QR encoder, read back by ZXing, the decoder the apps already trust. */
class QrCodeTest {

    private fun decode(code: QrCode, scale: Int = 4, quiet: Int = 4): String {
        val side = (code.size + quiet * 2) * scale
        val pixels = IntArray(side * side) { i ->
            val x = (i % side) / scale - quiet
            val y = (i / side) / scale - quiet
            if (code[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels)))
        return QRCodeReader().decode(bitmap, mapOf(DecodeHintType.PURE_BARCODE to false)).text
    }

    @Test
    fun telegramLoginLinkRoundTrips() {
        val link = "tg://login?token=AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyAhIiMkJSYnKCkqKywtLi8w"
        val code = QrCode.encode(link)
        assertNotNull(code)
        assertEquals(link, decode(code!!))
    }

    @Test
    fun everyLevelAndManyLengthsRoundTrip() {
        for (ecc in QrCode.Ecc.entries) {
            for (length in listOf(1, 10, 17, 32, 60, 100, 154, 230, 400, 700)) {
                val text = buildString { repeat(length) { append("abcXYZ0189-_/:?=éü"[(it * 7 + length) % 18]) } }
                val code = QrCode.encode(text, ecc) ?: error("no code for $length at $ecc")
                assertEquals("$ecc $length", text, decode(code))
            }
        }
    }

    @Test
    fun smallestVersionThatFits() {
        assertEquals(21, QrCode.encode("hello", QrCode.Ecc.Medium)!!.size)
    }
}
