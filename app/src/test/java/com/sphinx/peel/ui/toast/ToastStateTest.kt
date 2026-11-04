package com.sphinx.peel.ui.toast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToastStateTest {
    @Test fun `defaults to a warning that lasts five seconds`() {
        val state = ToastState()
        state.show("hi")
        assertEquals(ToastType.Warning, state.current?.type)
        assertEquals(5000L, state.current?.durationMs)
    }

    @Test fun `null duration is persistent`() {
        val state = ToastState()
        state.show("hi", durationMs = null)
        assertNull(state.current?.durationMs)
    }

    @Test fun `show replaces and a stale timer cannot close the new toast`() {
        val state = ToastState()
        state.show("one")
        val first = state.current!!.id
        state.show("two", ToastType.Error)
        assertNotEquals(first, state.current!!.id)
        state.dismiss(first)
        assertEquals("two", state.current?.message)
        state.dismiss(state.current!!.id)
        assertNull(state.current)
    }

    @Test fun `dismiss clears`() {
        val state = ToastState()
        state.show("x")
        state.dismiss()
        assertNull(state.current)
    }
}
