package com.sphinx.peel.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushKeyStoreTest {
    @Test fun `valid key`() = assertTrue(PushKeyStore.isValid("0123456789abcdef".repeat(4)))
    @Test fun `generated key is valid`() = assertTrue(PushKeyStore.isValid(PushKeyStore.generate()))
    @Test fun `wrong length`() {
        assertFalse(PushKeyStore.isValid("ab".repeat(31)))
        assertFalse(PushKeyStore.isValid("ab".repeat(33)))
        assertFalse(PushKeyStore.isValid(""))
    }
    @Test fun `uppercase is invalid`() = assertFalse(PushKeyStore.isValid("ABCDEF0123456789".repeat(4)))
    @Test fun `non hex is invalid`() = assertFalse(PushKeyStore.isValid("g".repeat(64)))
    @Test fun `app id is the android one`() {
        assertEquals("com.sphinx.peel.android", PushApp.APP_ID)
        assertFalse(PushApp.APP_ID == "sphinx")
        assertFalse(PushApp.APP_ID == "com.sphinx.peel")
        assertEquals("production", PushApp.ENVIRONMENT)
    }
}
