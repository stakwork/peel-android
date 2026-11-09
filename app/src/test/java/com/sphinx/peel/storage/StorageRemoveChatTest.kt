package com.sphinx.peel.storage

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/// Leaving or deleting a tribe takes its chat off the device: contact, messages and all.
@RunWith(RobolectricTestRunner::class)
class StorageRemoveChatTest {
    private fun newStorage() = Storage(ApplicationProvider.getApplicationContext(), account = "test-${System.nanoTime()}")

    private fun message(uuid: String, chat: String) =
        MessageRow(uuid = uuid, serverIndex = null, type = 0, chat = chat, fromMe = false, sender = null, body = "hi", msat = null, timestamp = 1, tag = null, paymentHash = null)

    @Test
    fun `removing a chat leaves the others alone`() {
        val storage = newStorage()
        storage.upsertContact("tribe", null, null, isTribe = true, ready = true)
        storage.upsertContact("alice", "Alice", null, isTribe = false, ready = true)
        storage.insertMessages(listOf(message("m1", "tribe"), message("m2", "alice")))

        storage.removeChat("tribe")

        assertNull(storage.contact("tribe"))
        assertTrue(storage.messages("tribe").isEmpty())
        assertEquals(listOf("alice"), storage.conversations().map { it.chat })
        assertEquals(1, storage.messages("alice").size)
    }
}
