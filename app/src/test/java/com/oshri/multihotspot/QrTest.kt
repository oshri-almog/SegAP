package com.oshri.multihotspot

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.oshri.multihotspot.ui.qrMatrix
import com.oshri.multihotspot.ui.wifiQrPayload
import org.junit.Assert.assertEquals
import org.junit.Test

/** Round-trips the Wi-Fi QR payload through the encoder and ZXing's own reader. */
class QrTest {

    private fun decode(content: String): String {
        val m = qrMatrix(content, 400)
        val pixels = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(m.width, m.height, pixels)))
        return QRCodeReader().decode(bitmap, mapOf(DecodeHintType.CHARACTER_SET to "UTF-8")).text
    }

    @Test fun plainNetwork() {
        val p = wifiQrPayload("TEST-MULTI", "example-pass-1", hidden = false)
        assertEquals(p, decode(p))
    }

    @Test fun specialCharactersAndUnicode() {
        val p = wifiQrPayload("בית; \"S10\", 2:4", "p@ss;w0rd,\\x", hidden = true)
        assertEquals(p, decode(p))
    }
}
