package com.sphinx.peel.engine

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/// One absolute path segment: what [SvgPath.parse] turns every SVG path command into (H/V → Line, S → Cubic, T → Quad,
/// A → Cubics, relative → absolute). Pure; `ui/apps/LucideVectors.kt` maps it to Compose's `PathNode`s.
sealed interface Seg {
    data class Move(val x: Float, val y: Float) : Seg
    data class Line(val x: Float, val y: Float) : Seg
    data class Cubic(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x: Float, val y: Float) : Seg
    data class Quad(val x1: Float, val y1: Float, val x: Float, val y: Float) : Seg
    data object Close : Seg
}

/// SVG path data (the `d` of a lucide icon) as absolute segments. Written for lucide's output, which is well formed:
/// anything malformed ends the path at the last good command instead of throwing (an icon is never worth a crash).
object SvgPath {
    fun parse(d: String): List<Seg> {
        val out = ArrayList<Seg>()
        val s = Scanner(d)
        var cx = 0f; var cy = 0f // current point
        var sx = 0f; var sy = 0f // subpath start
        var lcx = 0f; var lcy = 0f // last control point (for S and T)
        var prev = ' '
        var cmd = ' '
        while (true) {
            s.skipSeparators()
            if (s.done()) break
            val c = s.peek()
            if (c.isLetter()) { cmd = c; s.next() } else if (cmd == ' ' || cmd == 'Z' || cmd == 'z') break
            // After a moveto, extra coordinate pairs are linetos.
            val rel = cmd.isLowerCase()
            val ox = if (rel) cx else 0f
            val oy = if (rel) cy else 0f
            when (cmd.uppercaseChar()) {
                'Z' -> { out += Seg.Close; cx = sx; cy = sy; prev = 'Z'; continue }
                'M' -> {
                    val x = s.number() ?: break; val y = s.number() ?: break
                    cx = ox + x; cy = oy + y; sx = cx; sy = cy
                    out += Seg.Move(cx, cy)
                    cmd = if (rel) 'l' else 'L'; prev = 'M'
                }
                'L' -> {
                    val x = s.number() ?: break; val y = s.number() ?: break
                    cx = ox + x; cy = oy + y; out += Seg.Line(cx, cy); prev = 'L'
                }
                'H' -> { val x = s.number() ?: break; cx = ox + x; out += Seg.Line(cx, cy); prev = 'L' }
                'V' -> { val y = s.number() ?: break; cy = oy + y; out += Seg.Line(cx, cy); prev = 'L' }
                'C' -> {
                    val a = s.numbers(6) ?: break
                    val x1 = ox + a[0]; val y1 = oy + a[1]; val x2 = ox + a[2]; val y2 = oy + a[3]
                    cx = ox + a[4]; cy = oy + a[5]
                    out += Seg.Cubic(x1, y1, x2, y2, cx, cy); lcx = x2; lcy = y2; prev = 'C'
                }
                'S' -> {
                    val a = s.numbers(4) ?: break
                    val x1 = if (prev == 'C') 2 * cx - lcx else cx
                    val y1 = if (prev == 'C') 2 * cy - lcy else cy
                    val x2 = ox + a[0]; val y2 = oy + a[1]
                    cx = ox + a[2]; cy = oy + a[3]
                    out += Seg.Cubic(x1, y1, x2, y2, cx, cy); lcx = x2; lcy = y2; prev = 'C'
                }
                'Q' -> {
                    val a = s.numbers(4) ?: break
                    val x1 = ox + a[0]; val y1 = oy + a[1]
                    cx = ox + a[2]; cy = oy + a[3]
                    out += Seg.Quad(x1, y1, cx, cy); lcx = x1; lcy = y1; prev = 'Q'
                }
                'T' -> {
                    val a = s.numbers(2) ?: break
                    val x1 = if (prev == 'Q') 2 * cx - lcx else cx
                    val y1 = if (prev == 'Q') 2 * cy - lcy else cy
                    cx = ox + a[0]; cy = oy + a[1]
                    out += Seg.Quad(x1, y1, cx, cy); lcx = x1; lcy = y1; prev = 'Q'
                }
                'A' -> {
                    val rx = s.number() ?: break; val ry = s.number() ?: break; val rot = s.number() ?: break
                    val large = s.flag() ?: break; val sweep = s.flag() ?: break
                    val x = s.number() ?: break; val y = s.number() ?: break
                    val ex = ox + x; val ey = oy + y
                    arc(out, cx, cy, rx, ry, rot, large, sweep, ex, ey)
                    cx = ex; cy = ey; prev = 'A'
                }
                else -> break
            }
        }
        return out
    }

    /// An SVG elliptical arc (endpoint form, SVG 1.1 appendix F.6) as cubic Béziers, one per quarter turn at most.
    private fun arc(out: MutableList<Seg>, x0: Float, y0: Float, rxIn: Float, ryIn: Float, rotDeg: Float, large: Boolean, sweep: Boolean, x: Float, y: Float) {
        if (x0 == x && y0 == y) return
        var rx = abs(rxIn.toDouble()); var ry = abs(ryIn.toDouble())
        if (rx == 0.0 || ry == 0.0) { out += Seg.Line(x, y); return }
        val phi = Math.toRadians(rotDeg.toDouble()); val cp = cos(phi); val sp = sin(phi)
        val dx = (x0 - x) / 2.0; val dy = (y0 - y) / 2.0
        val x1p = cp * dx + sp * dy; val y1p = -sp * dx + cp * dy
        val lambda = x1p * x1p / (rx * rx) + y1p * y1p / (ry * ry)
        if (lambda > 1) { val k = sqrt(lambda); rx *= k; ry *= k }
        val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        var coef = if (den == 0.0) 0.0 else sqrt(maxOf(0.0, num / den))
        if (large == sweep) coef = -coef
        val cxp = coef * rx * y1p / ry; val cyp = -coef * ry * x1p / rx
        val ccx = cp * cxp - sp * cyp + (x0 + x) / 2.0; val ccy = sp * cxp + cp * cyp + (y0 + y) / 2.0
        val th1 = atan2((y1p - cyp) / ry, (x1p - cxp) / rx)
        var dth = atan2((-y1p - cyp) / ry, (-x1p - cxp) / rx) - th1
        if (sweep && dth < 0) dth += 2 * Math.PI else if (!sweep && dth > 0) dth -= 2 * Math.PI
        val n = maxOf(1, ceil(abs(dth) / (Math.PI / 2) - 1e-9).toInt())
        val step = dth / n
        val t = 4.0 / 3.0 * tan(step / 4)
        var a = th1
        for (i in 0 until n) {
            val b = a + step
            val ca = cos(a); val sa = sin(a); val cb = cos(b); val sb = sin(b)
            // Unit-circle control points, then scale, rotate, translate.
            fun px(ux: Double, uy: Double) = (cp * rx * ux - sp * ry * uy + ccx).toFloat()
            fun py(ux: Double, uy: Double) = (sp * rx * ux + cp * ry * uy + ccy).toFloat()
            val last = i == n - 1
            out += Seg.Cubic(
                px(ca - t * sa, sa + t * ca), py(ca - t * sa, sa + t * ca),
                px(cb + t * sb, sb - t * cb), py(cb + t * sb, sb - t * cb),
                if (last) x else px(cb, sb), if (last) y else py(cb, sb),
            )
            a = b
        }
    }

    private class Scanner(private val s: String) {
        var i = 0
        fun done() = i >= s.length
        fun peek() = s[i]
        fun next() = s[i++]
        fun skipSeparators() { while (i < s.length && (s[i] == ' ' || s[i] == ',' || s[i] == '\n' || s[i] == '\t' || s[i] == '\r')) i++ }
        /// An arc flag is one character, `0` or `1`, with or without a separator after it (`a1 1 0 011 1`).
        fun flag(): Boolean? { skipSeparators(); if (done()) return null; return when (s[i]) { '0' -> { i++; false }; '1' -> { i++; true }; else -> null } }
        fun numbers(n: Int): FloatArray? { val a = FloatArray(n); for (k in 0 until n) a[k] = number() ?: return null; return a }
        fun number(): Float? {
            skipSeparators()
            val st = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            var digits = false
            while (i < s.length && s[i].isDigit()) { i++; digits = true }
            if (i < s.length && s[i] == '.') { i++; while (i < s.length && s[i].isDigit()) { i++; digits = true } }
            if (!digits) { i = st; return null }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                val save = i; i++
                if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
                if (i < s.length && s[i].isDigit()) { while (i < s.length && s[i].isDigit()) i++ } else i = save
            }
            return s.substring(st, i).toFloatOrNull()
        }
    }
}
