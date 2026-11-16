package com.sphinx.peel.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SvgPathTest {
    private fun p(d: String) = SvgPath.parse(d)

    @Test fun absoluteAndRelativeLinesAndClose() {
        assertEquals(listOf(Seg.Move(1f, 2f), Seg.Line(4f, 6f), Seg.Line(4f, 9f), Seg.Line(0f, 9f), Seg.Close), p("M1 2 l3 4 V9 h-4 Z"))
        assertEquals(listOf(Seg.Move(5f, 5f), Seg.Line(6f, 5f), Seg.Line(6f, 7f)), p("m5 5 h1 v2"))
    }

    @Test fun implicitLinetoAfterMoveAndSeparators() {
        assertEquals(listOf(Seg.Move(1f, 1f), Seg.Line(2f, 2f), Seg.Line(3f, 3f)), p("M1,1 2 2,3 3"))
        assertEquals(listOf(Seg.Move(1f, 1f), Seg.Line(-2.5f, .5f)), p("M1 1L-2.5.5"))
    }

    @Test fun cubicSmoothReflectsTheLastControlPoint() {
        val s = p("M0 0C1 1 2 1 3 0S5 -1 6 0")
        assertEquals(Seg.Cubic(4f, -1f, 5f, -1f, 6f, 0f), s[2])
        // Not after a cubic: the control point is the current point.
        assertEquals(Seg.Cubic(0f, 0f, 1f, 1f, 2f, 2f), p("M0 0S1 1 2 2")[1])
    }

    @Test fun quadAndSmoothQuad() {
        val s = p("M0 0Q1 2 2 0T4 0")
        assertEquals(Seg.Quad(1f, 2f, 2f, 0f), s[1])
        assertEquals(Seg.Quad(3f, -2f, 4f, 0f), s[2])
        assertEquals(Seg.Quad(0f, 0f, 2f, 2f), p("M0 0T2 2")[1])
    }

    @Test fun arcBecomesCubicsThroughTheEndpoint() {
        val s = p("M0 0A5 5 0 0 1 10 0") // a half circle: two quarter arcs
        assertEquals(3, s.size)
        val end = s.last() as Seg.Cubic
        assertEquals(10f, end.x, 1e-4f); assertEquals(0f, end.y, 1e-4f)
        val mid = s[1] as Seg.Cubic
        assertEquals(5f, mid.x, 1e-3f); assertEquals(-5f, mid.y, 1e-3f) // sweep 1 goes through the top (y down)
    }

    @Test fun arcFlagsMayRunTogether() {
        val s = p("M0 0a2 2 0 011 1")
        assertTrue(s.size >= 2)
        val end = s.last() as Seg.Cubic
        assertEquals(1f, end.x, 1e-4f); assertEquals(1f, end.y, 1e-4f)
    }

    @Test fun aCircleArcRadiusTooSmallIsScaledAndZeroRadiusIsALine() {
        val s = p("M0 0A1 1 0 0 1 10 0")
        assertEquals(10f, (s.last() as Seg.Cubic).x, 1e-3f)
        assertEquals(listOf(Seg.Move(0f, 0f), Seg.Line(3f, 3f)), p("M0 0A0 5 0 0 1 3 3"))
    }

    @Test fun malformedDataEndsAtTheLastGoodCommand() {
        assertEquals(listOf(Seg.Move(1f, 1f)), p("M1 1 L3"))
        assertEquals(emptyList<Seg>(), p(""))
        assertEquals(emptyList<Seg>(), p("garbage"))
    }

    @Test fun exponentNumbers() {
        assertEquals(listOf(Seg.Move(100f, 0.01f)), p("M1e2 1e-2"))
    }

    @Test fun closeReturnsToTheSubpathStart() {
        val s = p("M2 2l3 0 0 3zm1 1h1")
        assertEquals(Seg.Line(4f, 3f), s.last()) // the m after z is relative to the start, (2,2)
    }
}
