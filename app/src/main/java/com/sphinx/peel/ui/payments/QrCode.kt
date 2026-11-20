package com.sphinx.peel.ui.payments

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.EncodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/// Drawing and reading QR codes with ZXing's own pure-Java core (no Play
/// Services). The iPhone draws one with CoreImage at correction level M
/// (`Apps/Shared/QRCode.swift`); this matches.
object QrCode {
    /// The code for `text` as a bitmap, black on white (a QR must be dark
    /// on light to scan, whatever the app's theme), one pixel per module
    /// plus a quiet zone of two; null if it cannot be encoded.
    fun bitmap(text: String): Bitmap? = runCatching {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        for (x in 0 until matrix.width) for (y in 0 until matrix.height) {
            bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
        }
        bitmap
    }.getOrNull()

    /// The text of a QR code in an 8-bit luminance (Y) plane, as CameraX's
    /// analysis hands it over, or null when there is none in this frame.
    fun decode(luminance: ByteArray, width: Int, height: Int): String? = try {
        val source = PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false)
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    } catch (e: NotFoundException) {
        null
    } catch (e: com.google.zxing.ChecksumException) {
        null
    } catch (e: com.google.zxing.FormatException) {
        null
    }

    /// Like [decode], but also where the code is: its corners from the three
    /// finder patterns ZXing reports ([ScanGeometry.corners]). Null when there
    /// is no code; a code ZXing read but gave too few points for is still a hit,
    /// with no corners.
    fun detect(luminance: ByteArray, width: Int, height: Int): QrHit? {
        val bitmap = BinaryBitmap(HybridBinarizer(PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false)))
        val result = try {
            QRCodeReader().decode(bitmap)
        } catch (e: com.google.zxing.ReaderException) {
            return null
        }
        val points = result.resultPoints.orEmpty()
        if (points.size < 3) return QrHit(result.text, emptyList())
        // The code's size in modules sets how far the finder centres sit inside its edge.
        val modules = runCatching { com.google.zxing.qrcode.detector.Detector(bitmap.blackMatrix).detect().bits.width }.getOrNull()
        val corners = ScanGeometry.corners(
            ScanPoint(points[0].x, points[0].y), ScanPoint(points[1].x, points[1].y), ScanPoint(points[2].x, points[2].y), modules,
        )
        return QrHit(result.text, corners)
    }
}

/// A QR code read from a camera frame: its text, and where it sits in the frame.
/// [corners] are the code's outer corners (top-left, top-right, bottom-right,
/// bottom-left) in the frame's own pixel coordinates, before any rotation.
class QrHit(val text: String, val corners: List<ScanPoint>)

/// A QR code of `text`, upper-cased by the caller when it is an invoice
/// (the denser alphanumeric mode).
@Composable
fun QrCodeImage(text: String, size: Dp = 240.dp, contentDescription: String = "QR code") {
    val bitmap = remember(text) { QrCode.bitmap(text)?.asImageBitmap() } ?: return
    Image(
        bitmap = bitmap,
        contentDescription = contentDescription,
        filterQuality = FilterQuality.None,
        modifier = Modifier.size(size).background(androidx.compose.ui.graphics.Color.White).padding(4.dp),
    )
}
