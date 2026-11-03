package com.sphinx.peel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardUtilTest {
    @Test fun clearsOnlyWhenUnchanged() {
        assertTrue(ClipboardUtil.shouldClear("abc", "abc"))
        assertFalse(ClipboardUtil.shouldClear("other", "abc"))
        assertFalse(ClipboardUtil.shouldClear(null, "abc"))
    }
}
