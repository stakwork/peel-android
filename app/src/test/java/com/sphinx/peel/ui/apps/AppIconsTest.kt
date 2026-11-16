package com.sphinx.peel.ui.apps

import com.sphinx.peel.engine.LucideNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AppIconsTest {
    @Test fun theTableHasAnIconForExactlyTheSupportedNames() {
        assertEquals(LucideNames.SUPPORTED, AppIcons.table.keys)
    }

    @Test fun noNameIsTheGrid() {
        assertSame(AppIcons.table["layout-grid"], AppIcons.vector(null))
        assertSame(AppIcons.table["layout-grid"], AppIcons.vector("nonsense"))
        assertSame(AppIcons.table["house"], AppIcons.vector("home"))
    }
}
