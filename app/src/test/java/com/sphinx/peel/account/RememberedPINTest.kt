package com.sphinx.peel.account

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RememberedPINTest {
    private val dir = Files.createTempDirectory("peel-rem").toFile()
    private val file = dir.resolve("remembered")
    private val wall = FakeWall()
    private fun store() = RememberedPIN(wall, file)
    private val hour = 3600L

    @Test fun `valid within the window, inclusive, expired removed`() {
        val s = store()
        s.keep("123456", 1000, 4)
        assertEquals("123456", s.pin(1000 + 4 * hour))
        assertNull(s.pin(1000 + 4 * hour + 1))
        assertFalse(file.exists())
        assertNull(s.pin(1000))
    }

    @Test fun `never is always valid, zero forgets`() {
        val s = store()
        s.keep("123456", 0, PinTimeout.NEVER)
        assertEquals("123456", s.pin(Long.MAX_VALUE / 2))
        s.keep("123456", 0, 0)
        assertFalse(file.exists())
    }

    @Test fun `a clock set back counts as no time passed`() {
        val s = store()
        s.keep("123456", 10_000, 1)
        assertEquals("123456", s.pin(5))
    }

    @Test fun `touch extends the window`() {
        val s = store()
        s.keep("123456", 0, 1)
        s.touch(hour)
        assertEquals("123456", s.pin(2 * hour))
        assertNull(s.pin(2 * hour + 1))
    }

    @Test fun `touch without a kept pin keeps nothing`() {
        val s = store()
        s.touch(5)
        assertFalse(file.exists())
    }

    @Test fun `timeoutChanged rewrites the window or removes at zero`() {
        val s = store()
        s.keep("123456", 0, 1)
        s.timeoutChanged(4)
        assertEquals("123456", s.pin(3 * hour))
        s.timeoutChanged(0)
        assertNull(s.pin(1))
        assertFalse(file.exists())
    }

    @Test fun `pinChanged replaces the pin and forget removes it`() {
        val s = store()
        s.keep("123456", 0, 24)
        s.pinChanged("654321")
        assertEquals("654321", s.pin(1))
        s.forget()
        assertNull(s.pin(1))
    }

    @Test fun `the file is ciphertext and unreadable without the key`() {
        val s = store()
        s.keep("123456", 0, 24)
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("123456"))
        wall.broken = true
        assertNull(store().pin(1))
        assertFalse(file.exists()) // dropped, so the PIN is asked
    }

    @Test fun `validity rule`() {
        assertTrue(RememberedPIN.isValid(RememberedPIN.Entry("1", 0, -1), 1_000_000))
        assertFalse(RememberedPIN.isValid(RememberedPIN.Entry("1", 0, 0), 0))
    }
}
