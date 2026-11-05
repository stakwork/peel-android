package com.sphinx.peel.push

import com.sphinx.peel.account.FakeWall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PushNameStoreTest {
    @Test fun `sealed round trip and clear`() {
        val file = File.createTempFile("names", ".bin").also { it.delete() }
        val store = PushNameStore(file, FakeWall())
        assertNull(store.read())
        val dir = PushNameDirectory(contacts = mapOf(1uL to "Zed"), contactChats = mapOf(1uL to "02ff"))
        assertTrue(store.write(dir))
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("Zed"))
        assertEquals(dir, store.read())
        store.clear()
        assertNull(store.read())
        assertFalse(file.exists())
    }
}
