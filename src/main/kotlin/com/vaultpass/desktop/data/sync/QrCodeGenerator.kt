package com.vaultpass.desktop.data.sync

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.client.j2se.MatrixToImageConfig
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.EnumMap

/**
 * Generates Compose ImageBitmaps for QR codes used in device pairing.
 */
object QrCodeGenerator {

    fun generateQrBitmap(content: String, size: Int = 280): ImageBitmap {
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
            put(EncodeHintType.CHARACTER_SET, "UTF-8")
            put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M)
            put(EncodeHintType.MARGIN, 1)
        }

        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val config = MatrixToImageConfig(0xFF000000.toInt(), 0xFFFFFFFF.toInt())
        val bufferedImage = MatrixToImageWriter.toBufferedImage(bitMatrix, config)
        return bufferedImage.toComposeImageBitmap()
    }
}
