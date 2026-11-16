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
    private fun store() = RememberedPIN(wall, file, backoffMs = 0)
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
        file.writeBytes(ByteArray(40) { 7 }) // not sealed by the key: the tag fails
        assertNull(store().pin(1))
        assertFalse(file.exists()) // dropped, so the PIN is asked
    }

    @Test fun `a transient failure once is retried and the pin returned`() {
        val s = store()
        s.keep("123456", 0, 24)
        wall.unwrapError = java.security.KeyStoreException("busy")
        wall.failures = 1
        assertEquals("123456", s.pin(1))
        assertTrue(file.exists())
    }

    @Test fun `a persistent transient failure keeps the file and reports`() {
        val s = store()
        val problems = mutableListOf<Pair<String, String?>>()
        s.onProblem = { r, c -> problems += r to c }
        s.keep("123456", 0, 24)
        wall.unwrapError = java.security.KeyStoreException("down")
        assertNull(s.pin(1))
        assertTrue(file.exists())
        assertEquals(listOf("unreadable" to "KeyStoreException"), problems)
        wall.unwrapError = null
        assertEquals("123456", s.pin(1))
    }

    @Test fun `a bad tag deletes`() {
        val s = store()
        s.keep("123456", 0, 24)
        wall.unwrapError = javax.crypto.AEADBadTagException("tag")
        assertNull(s.pin(1))
        assertFalse(file.exists())
    }

    @Test fun `a missing key deletes`() {
        val s = store()
        s.keep("123456", 0, 24)
        wall.unwrapError = KeyMissingException("alias")
        assertNull(s.pin(1))
        assertFalse(file.exists())
    }

    @Test fun `a bad format deletes`() {
        val s = store()
        s.keep("123456", 0, 24)
        wall.unwrapError = null
        file.writeBytes(wall.wrap("only-one-part".toByteArray(), "peel.remembered".toByteArray()))
        assertNull(s.pin(1))
        assertFalse(file.exists())
    }

    @Test fun `a failing write leaves the existing file intact`() {
        val s = store()
        s.keep("123456", 0, 24)
        val before = file.readBytes()
        wall.wrapError = java.security.KeyStoreException("down")
        s.touch(5)
        s.timeoutChanged(4)
        s.pinChanged("654321")
        s.keep("111111", 0, 24)
        assertTrue(before.contentEquals(file.readBytes()))
        wall.wrapError = null
        assertEquals("123456", s.pin(1))
    }

    @Test fun `keep with an unknown timeout preserves an existing never`() {
        val s = store()
        s.keep("123456", 0, PinTimeout.NEVER)
        s.keep("654321", 10, null)
        assertEquals("654321", s.pin(Long.MAX_VALUE / 2))
    }

    @Test fun `keep with an unknown timeout and no entry uses the default`() {
        val s = store()
        s.keep("123456", 0, null)
        assertEquals("123456", s.pin(24 * hour))
        assertNull(s.pin(24 * hour + 1))
    }

    @Test fun `validity rule`() {
        assertTrue(RememberedPIN.isValid(RememberedPIN.Entry("1", 0, -1), 1_000_000))
        assertFalse(RememberedPIN.isValid(RememberedPIN.Entry("1", 0, 0), 0))
    }
}
