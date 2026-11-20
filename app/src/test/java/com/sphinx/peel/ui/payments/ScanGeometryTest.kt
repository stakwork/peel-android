package com.sphinx.peel.ui.payments

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanGeometryTest {
    private fun assertPoint(x: Float, y: Float, p: ScanPoint, tolerance: Float = 0.01f) {
        assertEquals("x", x, p.x, tolerance)
        assertEquals("y", y, p.y, tolerance)
    }

    // --- corners ---

    @Test
    fun `the fourth corner completes the parallelogram and the quad grows by half a finder`() {
        // Finder centres of a 25-module code drawn at 10 px a module: 18 modules apart.
        val c = ScanGeometry.corners(ScanPoint(35f, 215f), ScanPoint(35f, 35f), ScanPoint(215f, 35f), 25)
        // 3.5 modules = 35 px outward.
        assertPoint(0f, 0f, c[0])
        assertPoint(250f, 0f, c[1])
        assertPoint(250f, 250f, c[2])
        assertPoint(0f, 250f, c[3])
    }

    @Test
    fun `a tilted code keeps its shape`() {
        val c = ScanGeometry.corners(ScanPoint(0f, 18f), ScanPoint(0f, 0f), ScanPoint(18f, 0f), 25)
        assertPoint(18f + 3.5f, 18f + 3.5f, c[2])
        assertPoint(-3.5f, -3.5f, c[0])
    }

    // --- mapping ---

    @Test
    fun `rotation zero fills the view and crops the overflow`() {
        // A 400x200 frame in a 200x200 view: scale 1, the sides cropped by 100 each.
        assertPoint(0f, 0f, ScanGeometry.toView(ScanPoint(100f, 0f), 400, 200, 0, 200f, 200f))
        assertPoint(100f, 100f, ScanGeometry.toView(ScanPoint(200f, 100f), 400, 200, 0, 200f, 200f))
        assertPoint(200f, 200f, ScanGeometry.toView(ScanPoint(300f, 200f), 400, 200, 0, 200f, 200f))
        // Scaled up: 400x200 into 800x800 is x4.
        assertPoint(400f, 400f, ScanGeometry.toView(ScanPoint(200f, 100f), 400, 200, 0, 800f, 800f))
    }

    @Test
    fun `each rotation stands the frame upright`() {
        // A 40x20 frame, a point at its top-left corner (0,0).
        val origin = ScanPoint(0f, 0f)
        // 90 clockwise: the top-left goes to the top-right of a 20x40 image.
        assertPoint(20f, 0f, ScanGeometry.toView(origin, 40, 20, 90, 20f, 40f))
        // 180: to the bottom-right of the 40x20.
        assertPoint(40f, 20f, ScanGeometry.toView(origin, 40, 20, 180, 40f, 20f))
        // 270: to the bottom-left of the 20x40.
        assertPoint(0f, 40f, ScanGeometry.toView(origin, 40, 20, 270, 20f, 40f))
        // 360 is 0, and negative turns count round.
        assertPoint(0f, 0f, ScanGeometry.toView(origin, 40, 20, 360, 40f, 20f))
        assertPoint(0f, 40f, ScanGeometry.toView(origin, 40, 20, -90, 20f, 40f))
    }

    @Test
    fun `a rotated frame is cropped by the upright size`() {
        // Upright 20x40 in a 20x20 view: scale 1, the top and bottom cropped by 10 each.
        // Frame (20,10) turned 90 is (10,20) upright, which is the view's centre.
        assertPoint(10f, 10f, ScanGeometry.toView(ScanPoint(20f, 10f), 40, 20, 90, 20f, 20f))
    }

    @Test
    fun `mirroring flips left to right`() {
        assertPoint(180f, 20f, ScanGeometry.toView(ScanPoint(20f, 20f), 200, 100, 0, 200f, 100f, mirrored = true))
        // Mirror after rotation: 90 turns (0,0) to the right edge, the mirror to the left.
        assertPoint(0f, 0f, ScanGeometry.toView(ScanPoint(0f, 0f), 40, 20, 90, 20f, 40f, mirrored = true))
    }

    // --- a real code ---

    /// A luminance frame holding a QR of [text]: [px] a module, a white quiet zone of [quiet] modules,
    /// the code's top-left module corner at (offsetX, offsetY) in a [width] x [height] frame.
    private class Frame(val data: ByteArray, val width: Int, val height: Int, val left: Int, val top: Int, val size: Int)

    private fun frame(text: String, px: Int = 6, offsetX: Int = 40, offsetY: Int = 30, width: Int = 360, height: Int = 300): Frame {
        val matrix = QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0),
        )
        val data = ByteArray(width * height) { 255.toByte() }
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (!matrix[x, y]) continue
            for (dy in 0 until px) for (dx in 0 until px) data[(offsetY + y * px + dy) * width + offsetX + x * px + dx] = 0
        }
        return Frame(data, width, height, offsetX, offsetY, matrix.width * px)
    }

    private fun rotated(f: Frame): Frame {
        // Clockwise 90: (x, y) -> (height - 1 - y, x) in a height x width image.
        val out = ByteArray(f.data.size)
        for (y in 0 until f.height) for (x in 0 until f.width) {
            out[x * f.height + (f.height - 1 - y)] = f.data[y * f.width + x]
        }
        return Frame(out, f.height, f.width, f.height - f.top - f.size, f.left, f.size)
    }

    private fun assertCorners(f: Frame, hit: QrHit) {
        assertEquals(4, hit.corners.size)
        // Within about a module and a half of the true outer corners (top-left clockwise).
        val tolerance = 10f
        val l = f.left.toFloat()
        val t = f.top.toFloat()
        val s = f.size.toFloat()
        // The reader may start at any corner of a rotated code, so match by set.
        val want = listOf(ScanPoint(l, t), ScanPoint(l + s, t), ScanPoint(l + s, t + s), ScanPoint(l, t + s))
        for (w in want) {
            val nearest = hit.corners.minOf { abs(it.x - w.x) + abs(it.y - w.y) }
            assertTrue("no corner near $w in ${hit.corners}", nearest < tolerance * 2)
        }
    }

    @Test
    fun `a code in a frame is read with its corners`() {
        val f = frame("https://example.com/hello?x=1")
        val hit = QrCode.detect(f.data, f.width, f.height)
        assertNotNull(hit)
        assertEquals("https://example.com/hello?x=1", hit!!.text)
        assertCorners(f, hit)
    }

    @Test
    fun `a code turned a quarter is read and its corners found`() {
        val f = rotated(frame("peel://strut/link?url=https%3A%2F%2Fbox.example.com&challenge=abcdef"))
        val hit = QrCode.detect(f.data, f.width, f.height)
        assertNotNull(hit)
        assertEquals("peel://strut/link?url=https%3A%2F%2Fbox.example.com&challenge=abcdef", hit!!.text)
        assertCorners(f, hit)
    }

    @Test
    fun `an empty frame has no code`() {
        assertEquals(null, QrCode.detect(ByteArray(100 * 100) { 255.toByte() }, 100, 100))
    }
}
