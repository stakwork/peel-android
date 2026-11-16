package com.sphinx.peel.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/// Over the real bundled asset (`org.json` is Android's, so Robolectric).
@RunWith(RobolectricTestRunner::class)
class LucideSetTest {
    companion object {
        val file = File("src/main/assets/lucide/icons.json")
        val set: LucideSet by lazy { LucideSet.parse(file.readText()) }
    }

    private fun counts(name: String): Map<String, Int> =
        set.exact(name)!!.paths.flatMap { it.segs }.groupingBy { it::class.simpleName!! }.eachCount()

    @Test fun theAssetIsTheWholeOfLucideAndSmall() {
        assertTrue("size ${file.length()}", file.length() < 600_000)
        assertTrue(set.names.size >= 2000)
        assertTrue(File("src/main/assets/lucide/LICENSE").readText().startsWith("ISC License"))
    }

    @Test fun everyIconParsesToDrawablePathsInsideTheViewBox() {
        for (n in set.names) {
            val shape = set.exact(n)
            assertNotNull(n, shape)
            assertTrue("$n has no path", shape!!.paths.isNotEmpty())
            for (p in shape.paths) {
                assertTrue("$n empty path", p.segs.isNotEmpty())
                assertTrue("$n starts without a move", p.segs.first() is Seg.Move)
                for (s in p.segs) for (v in coords(s)) assertTrue("$n out of bounds $v", v > -1.5f && v < 25.5f)
            }
        }
    }

    private fun coords(s: Seg): List<Float> = when (s) {
        is Seg.Move -> listOf(s.x, s.y); is Seg.Line -> listOf(s.x, s.y)
        is Seg.Cubic -> listOf(s.x1, s.y1, s.x2, s.y2, s.x, s.y); is Seg.Quad -> listOf(s.x1, s.y1, s.x, s.y); Seg.Close -> emptyList()
    }

    @Test fun everyIosAliasLandsOnAnIcon() {
        for ((a, b) in LucideNames.ALIASES) {
            assertTrue("$a -> $b", b in set.names)
            assertNotNull(a, LucideNames.resolve(a, set.index))
        }
    }

    @Test fun goldenHouse() {
        // M15 21v-8 a1 1 0 0 0-1-1 h-4 a1 1 0 0 0-1 1 v8 ; M3 10 a2 2 … (one house outline and a door)
        val c = counts("house")
        assertEquals(2, set.exact("house")!!.paths.size)
        assertEquals(2, c["Move"]); assertTrue((c["Cubic"] ?: 0) >= 6); assertEquals(1, c["Close"])
    }

    @Test fun goldenChartBarCircleUserAndSettings() {
        val bar = counts("chart-bar") // an L-shaped axis with a corner arc, three bars
        assertEquals(4, set.exact("chart-bar")!!.paths.size)
        assertEquals(4, bar["Move"]); assertEquals(5, bar["Line"]); assertEquals(1, bar["Cubic"])
        val user = set.exact("circle-user")!!
        assertEquals(3, user.paths.size) // ring, head, shoulders
        assertEquals(6, user.paths[0].segs.size)
        val s = set.exact("settings")!!
        assertEquals(2, s.paths.size) // the cog and its hub circle
        assertEquals(6, s.paths.last().segs.size)
    }

    @Test fun primitivesBecomePaths() {
        val circle = LucidePrimitives.path(listOf("c", 12.0, 12.0, 10.0, 0.0))
        assertEquals(6, circle.segs.size) // move, two half circles of two quarter arcs each, close
        assertFalse(circle.filled)
        assertTrue(LucidePrimitives.path(listOf("c", 1.0, 1.0, 1.0, 1.0)).filled)
        assertEquals(listOf(Seg.Move(1f, 2f), Seg.Line(3f, 4f)), LucidePrimitives.path(listOf("l", 1.0, 2.0, 3.0, 4.0)).segs)
        assertEquals(listOf(Seg.Move(0f, 0f), Seg.Line(1f, 1f), Seg.Line(2f, 0f), Seg.Close), LucidePrimitives.path(listOf("pg", "0 0 1 1 2 0")).segs)
        assertEquals(listOf(Seg.Move(0f, 0f), Seg.Line(1f, 1f)), LucidePrimitives.path(listOf("pl", "0 0 1 1")).segs)
        val rect = LucidePrimitives.path(listOf("r", 3.0, 3.0, 18.0, 18.0, 0.0, 0.0))
        assertEquals(Seg.Move(3f, 3f), rect.segs.first()); assertEquals(Seg.Close, rect.segs.last()); assertEquals(5, rect.segs.size)
        val rounded = LucidePrimitives.path(listOf("r", 3.0, 3.0, 18.0, 18.0, 2.0, 2.0))
        assertEquals(Seg.Move(5f, 3f), rounded.segs.first())
        assertTrue(rounded.segs.count { it is Seg.Cubic } == 4)
        assertTrue(LucidePrimitives.path(listOf("e", 12.0, 5.0, 9.0, 3.0)).segs.size > 3)
    }

    @Test fun anAliasEntryIsTheSameIconAsItsTarget() {
        val j = LucideSet.parse("""{"a":[["p","M0 0L1 1"]],"b":"@a"}""")
        assertEquals(j.exact("a")!!.paths[0].segs, j.exact("b")!!.paths[0].segs)
        assertNull(j.exact("c"))
    }

    // Names
    @Test fun aLucideNameResolves() {
        for (n in listOf("lightbulb", "shield-check", "anchor", "chart-bar", "circle-user", "settings"))
            assertEquals(n, LucideNames.resolve(n, set.index))
    }

    @Test fun spellingIsLooseAsOnIos() {
        assertEquals("shield-check", LucideNames.resolve("shieldCheck", set.index))
        assertEquals("shield-check", LucideNames.resolve("  Shield-Check ", set.index))
        assertEquals("arrow-right", LucideNames.resolve("arrowRight", set.index))
    }

    @Test fun aRenamedIconResolvesAsItsCurrentName() {
        assertEquals("house", LucideNames.resolve("home", set.index))
        assertEquals("chart-column", LucideNames.resolve("bar-chart-3", set.index))
        assertEquals("circle-alert", LucideNames.resolve("alert-circle", set.index))
        assertSame(set.exact("house")!!.paths[0].segs.size, set.shape("home").paths[0].segs.size)
    }

    @Test fun noNameOrAnUnknownOneIsTheGrid() {
        assertNull(LucideNames.resolve(null, set.index))
        assertNull(LucideNames.resolve("", set.index))
        assertNull(LucideNames.resolve("not-a-lucide-icon", set.index))
        assertNull(LucideNames.resolve("square.grid.2x2", set.index))
        assertEquals("layout-grid", LucideNames.shape(null, set.index))
        assertEquals("layout-grid", LucideNames.shape("not-a-lucide-icon", set.index))
        assertEquals(set.exact("layout-grid")!!.paths.size, set.shape("nonsense").paths.size)
        assertEquals("layout-grid", LucideNames.resolve(LucideNames.FALLBACK, set.index))
    }

    @Test fun everyNameTheOldMaterialTableKnewStillResolves() {
        val old = ("activity alarm-clock bar-chart bar-chart-3 bell book book-open brain briefcase building building-2 calendar camera " +
            "chart-bar chart-column chart-line chart-pie check-square circle-dollar-sign clock code cpu credit-card database dollar-sign " +
            "eye file file-text flag flask-conical flask-round folder gauge git-branch globe graduation-cap heart home house image inbox " +
            "kanban layers lightbulb line-chart list list-checks list-todo lock mail map map-pin message-circle message-square music " +
            "network newspaper package palette pen pencil pie-chart puzzle receipt scale search server settings shield shield-check " +
            "shopping-cart sparkles square-check star table tag target terminal test-tube trending-up trophy truck user users utensils " +
            "wallet workflow zap").split(" ")
        for (n in old) assertNotNull(n, LucideNames.resolve(n, set.index))
    }

    @Test fun lruKeepsTheMostRecentlyUsed() {
        val l = Lru<String, Int>(2)
        l["a"] = 1; l["b"] = 2; l["a"]; l["c"] = 3
        assertEquals(listOf("a", "c"), l.keys())
        assertNull(l["b"]); assertEquals(2, l.size)
    }
}
