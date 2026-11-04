package com.sphinx.peel.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeclaredAmountTest {
    @Test
    fun `a boost body's top-level amount is read`() {
        assertEquals(21000L, declaredAmountMsat("""{"content":"","amount":21000,"replyUuid":"r"}"""))
        assertEquals(0L, declaredAmountMsat("""{"amount":0}"""))
    }

    @Test
    fun `a missing, negative, fractional or non-numeric amount is null`() {
        assertNull(declaredAmountMsat("""{"content":"hi"}"""))
        assertNull(declaredAmountMsat("""{"amount":-5}"""))
        assertNull(declaredAmountMsat("""{"amount":1.5}"""))
        assertNull(declaredAmountMsat("""{"amount":"21000"}"""))
    }

    @Test
    fun `a body that is not JSON is null`() {
        assertNull(declaredAmountMsat("just text"))
        assertNull(declaredAmountMsat(null))
    }

    @Test
    fun `a huge amount is clamped to Long max`() {
        assertEquals(Long.MAX_VALUE, declaredAmountMsat("""{"amount":1e30}"""))
    }
}
