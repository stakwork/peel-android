package com.sphinx.peel.storage

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StorageTest {
    private fun newStorage() = Storage(ApplicationProvider.getApplicationContext(), account = "test-${System.nanoTime()}")

    @Test
    fun `coreState round trips through a commit`() {
        val storage = newStorage()
        storage.apply(Storage.Commit(state = mapOf("my_scid" to byteArrayOf(1, 2, 3))))

        assertEquals(byteArrayOf(1, 2, 3).toList(), storage.loadCoreState()["my_scid"]?.toList())
    }

    @Test
    fun `a later commit overwrites the same key`() {
        val storage = newStorage()
        storage.apply(Storage.Commit(state = mapOf("k" to byteArrayOf(1))))
        storage.apply(Storage.Commit(state = mapOf("k" to byteArrayOf(2))))

        assertEquals(byteArrayOf(2).toList(), storage.loadCoreState()["k"]?.toList())
    }

    @Test
    fun `deleteState removes the key`() {
        val storage = newStorage()
        storage.apply(Storage.Commit(state = mapOf("gone" to byteArrayOf(9))))
        storage.apply(Storage.Commit(deleteState = listOf("gone")))

        assertNull(storage.loadCoreState()["gone"])
    }

    @Test
    fun `setting round trips, unset is null`() {
        val storage = newStorage()
        assertNull(storage.value(Setting.ALIAS))

        storage.apply(Storage.Commit(settings = mapOf(Setting.ALIAS to "Tom")))
        assertEquals("Tom", storage.value(Setting.ALIAS))

        storage.apply(Storage.Commit(settings = mapOf(Setting.ALIAS to "Tomas")))
        assertEquals("Tomas", storage.value(Setting.ALIAS))
    }

    @Test
    fun `a single commit applies state, deletes and settings together`() {
        val storage = newStorage()
        storage.apply(Storage.Commit(state = mapOf("a" to byteArrayOf(1), "b" to byteArrayOf(2))))

        storage.apply(
            Storage.Commit(
                state = mapOf("c" to byteArrayOf(3)),
                deleteState = listOf("a"),
                settings = mapOf(Setting.WORDS_CONFIRMED to "1"),
            ),
        )

        val state = storage.loadCoreState()
        assertNull(state["a"])
        assertEquals(byteArrayOf(2).toList(), state["b"]?.toList())
        assertEquals(byteArrayOf(3).toList(), state["c"]?.toList())
        assertEquals("1", storage.value(Setting.WORDS_CONFIRMED))
    }

    @Test
    fun `deleteAccount wipes state, and the same instance starts clean afterwards`() {
        // "Forget this account" (HomeScreen) relies on exactly this: the
        // next account made on this device must not inherit the previous
        // one's coreState through the same Storage instance.
        val storage = newStorage()
        storage.apply(
            Storage.Commit(state = mapOf("my_scid" to byteArrayOf(1)), settings = mapOf(Setting.ALIAS to "Tom")),
        )

        storage.deleteAccount()

        assertEquals(emptyMap<String, ByteArray>(), storage.loadCoreState())
        assertNull(storage.value(Setting.ALIAS))

        storage.apply(Storage.Commit(settings = mapOf(Setting.ALIAS to "Ann")))
        assertEquals("Ann", storage.value(Setting.ALIAS))
    }
}
