package com.kuva.vpn.core.qr

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** Renders a text payload as a QR bitmap (ZXing core, no camera dependency). */
object QrCode {

    /**
     * @param size side length in pixels (square output)
     * @param quietZone white margin around the code, in modules
     */
    fun bitmap(content: String, size: Int = 640, quietZone: Int = 2): Bitmap {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to quietZone,
        )
        val matrix = QRCodeWriter().encode(
            content, BarcodeFormat.QR_CODE, size, size, hints,
        )
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val dark = Color.rgb(6, 12, 28)          // near-black navy — matches dark theme
        val light = Color.WHITE
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) dark else light)
            }
        }
        return bmp
    }
}
