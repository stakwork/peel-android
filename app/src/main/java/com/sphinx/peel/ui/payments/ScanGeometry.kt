package com.sphinx.peel.ui.payments

/// A point, in whatever space the caller says (pure, so the geometry is JVM-testable).
data class ScanPoint(val x: Float, val y: Float) {
    operator fun plus(o: ScanPoint) = ScanPoint(x + o.x, y + o.y)
    operator fun minus(o: ScanPoint) = ScanPoint(x - o.x, y - o.y)
    operator fun times(k: Float) = ScanPoint(x * k, y * k)
}

/// Where a QR code's corner brackets go: the code's corners from ZXing's finder
/// patterns, and the map from the camera frame to the preview on screen.
object ScanGeometry {
    /// A finder pattern is seven modules wide; its centre sits half of that inside the code's edge.
    const val FINDER_HALF = 3.5f

    /// Used when the code's size is not known: a version 2 code.
    private const val DEFAULT_MODULES = 25

    /// The code's outer corners, top-left first and clockwise (top-left, top-right, bottom-right,
    /// bottom-left), from the three finder-pattern centres ZXing reports. The fourth corner is
    /// `bottomLeft + topRight - topLeft`, and the quad is pushed outward by half a finder pattern
    /// (3.5 modules) so the brackets sit on the code's edge rather than inside it. [modules] is the
    /// code's width in modules (its centres are `modules - 7` apart), or null when not known.
    fun corners(bottomLeft: ScanPoint, topLeft: ScanPoint, topRight: ScanPoint, modules: Int? = null): List<ScanPoint> {
        val span = ((modules ?: DEFAULT_MODULES) - 7).coerceAtLeast(1).toFloat()
        val across = (topRight - topLeft) * (FINDER_HALF / span)
        val down = (bottomLeft - topLeft) * (FINDER_HALF / span)
        val bottomRight = bottomLeft + topRight - topLeft
        return listOf(
            topLeft - across - down,
            topRight + across - down,
            bottomRight + across + down,
            bottomLeft - across + down,
        )
    }

    /// A frame point mapped to a preview that shows the frame the way PreviewView's FILL_CENTER does
    /// (the fallback when CameraX's own `CoordinateTransform` is not ready). [rotation] is the
    /// frame's `rotationDegrees`: how far it must turn clockwise to stand upright. The upright image
    /// is then mirrored left to right when [mirrored] (a front camera), scaled to cover the view
    /// and centred, the overflow cropped.
    fun toView(
        point: ScanPoint,
        frameWidth: Int,
        frameHeight: Int,
        rotation: Int,
        viewWidth: Float,
        viewHeight: Float,
        mirrored: Boolean = false,
    ): ScanPoint {
        val w = frameWidth.toFloat()
        val h = frameHeight.toFloat()
        val turns = ((rotation % 360) + 360) % 360
        val upright = when (turns) {
            90 -> ScanPoint(h - point.y, point.x)
            180 -> ScanPoint(w - point.x, h - point.y)
            270 -> ScanPoint(point.y, w - point.x)
            else -> point
        }
        val sideways = turns == 90 || turns == 270
        val uw = if (sideways) h else w
        val uh = if (sideways) w else h
        val x = if (mirrored) uw - upright.x else upright.x
        val scale = maxOf(viewWidth / uw, viewHeight / uh)
        return ScanPoint(
            (x - uw / 2) * scale + viewWidth / 2,
            (upright.y - uh / 2) * scale + viewHeight / 2,
        )
    }
}
