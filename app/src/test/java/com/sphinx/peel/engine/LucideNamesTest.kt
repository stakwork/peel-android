package com.sphinx.peel.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// iOS `WorkspaceAppIconTests`, over the names Android has an icon for.
class LucideNamesTest {
    @Test fun aLucideNameResolves() {
        assertEquals("lightbulb", LucideNames.resolve("lightbulb"))
        assertEquals("shield-check", LucideNames.resolve("shield-check"))
        assertEquals("message-square", LucideNames.resolve("message-square"))
        assertEquals("phone", LucideNames.resolve("phone"))
    }

    @Test fun spellingIsLooseAsOnIos() {
        assertEquals("shield-check", LucideNames.resolve("shieldCheck"))
        assertEquals("shield-check", LucideNames.resolve("  Shield-Check "))
        assertEquals("package", LucideNames.resolve("package"))
    }

    @Test fun aRenamedIconResolvesAsItsCurrentName() {
        assertEquals(LucideNames.resolve("house"), LucideNames.resolve("home"))
        assertEquals(LucideNames.resolve("chart-column"), LucideNames.resolve("bar-chart-3"))
        assertNotNull(LucideNames.resolve("check-square"))
    }

    @Test fun noNameOrAnUnknownOneIsTheGrid() {
        assertNull(LucideNames.resolve(null))
        assertNull(LucideNames.resolve(""))
        assertNull(LucideNames.resolve("not-a-lucide-icon"))
        assertNull(LucideNames.resolve("square.grid.2x2"))
        assertEquals("layout-grid", LucideNames.shape(null))
        assertEquals("layout-grid", LucideNames.shape("not-a-lucide-icon"))
        assertEquals("layout-grid", LucideNames.shape("anchor")) // a lucide icon Android has no counterpart for
        assertEquals("layout-grid", LucideNames.resolve(LucideNames.FALLBACK))
    }

    @Test fun everyNameTheOldIosTableKnewStillResolves() {
        val old = ("activity alarm-clock bar-chart bar-chart-3 bell book book-open brain briefcase building building-2 calendar camera " +
            "chart-bar chart-column chart-line chart-pie check-square circle-dollar-sign clock code cpu credit-card database dollar-sign " +
            "eye file file-text flag flask-conical flask-round folder gauge git-branch globe graduation-cap heart home house image inbox " +
            "kanban layers lightbulb line-chart list list-checks list-todo lock mail map map-pin message-circle message-square music " +
            "network newspaper package palette pen pencil pie-chart puzzle receipt scale search server settings shield shield-check " +
            "shopping-cart sparkles square-check star table tag target terminal test-tube trending-up trophy truck user users utensils " +
            "wallet workflow zap").split(" ")
        for (n in old) assertNotNull(n, LucideNames.resolve(n))
    }

    @Test fun everyAliasLandsOnASupportedName() {
        for (n in listOf("home", "edit-2", "trash-2", "wallet-2", "stars", "fork-knife")) assertTrue(n, LucideNames.resolve(n) in LucideNames.SUPPORTED)
    }
}
