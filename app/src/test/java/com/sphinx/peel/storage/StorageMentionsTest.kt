package com.sphinx.peel.storage

import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/// `Storage.mentions`/`unreadMentions` — PeelCore's `mentions()` and
/// `unreadMentions`, as a query plus a Kotlin filter (no `mentionsMe` column).
@RunWith(RobolectricTestRunner::class)
class StorageMentionsTest {
    private fun newStorage() = Storage(ApplicationProvider.getApplicationContext(), account = "test-${System.nanoTime()}")

    private fun message(uuid: String, chat: String, text: String, index: Long, fromMe: Boolean = false, type: Int = 0) = MessageRow(
        uuid = uuid, serverIndex = index, type = type, chat = chat, fromMe = fromMe, sender = null,
        body = JSONObject().put("content", text).toString(), msat = null, timestamp = index * 100, tag = null, paymentHash = null,
    )

    private fun withTribe(storage: Storage) {
        storage.upsertContact("tribe", "Tribe", null, isTribe = true)
        storage.upsertContact("alice", "Alice", null, isTribe = false)
    }

    @Test
    fun `lists only other people's mentions of the alias in tribes, newest first`() {
        val storage = newStorage()
        withTribe(storage)
        storage.insertMessages(
            listOf(
                message("a", "tribe", "hi @Tom_Smith", 1),
                message("b", "tribe", "hi @all", 2),
                message("c", "tribe", "hello @tom_smith!", 3),
                message("d", "tribe", "me @Tom_Smith", 4, fromMe = true),
                message("e", "alice", "dm @Tom_Smith", 5),
                message("f", "tribe", "mail@Tom_Smith", 6),
            ),
        )

        assertEquals(listOf("c", "a"), storage.mentions("Tom Smith").map { it.uuid })
        assertEquals(listOf("c"), storage.mentions("Tom Smith", limit = 1).map { it.uuid })
    }

    @Test
    fun `unread mentions count past the read marker per chat`() {
        val storage = newStorage()
        withTribe(storage)
        storage.insertMessages(
            listOf(
                message("a", "tribe", "@Tom_Smith one", 1),
                message("b", "tribe", "@Tom_Smith two", 2),
                message("c", "tribe", "@Tom_Smith three", 3),
            ),
        )
        assertEquals(3L, storage.unreadMentions("Tom Smith"))

        storage.setRead("tribe", 2, pending = false)
        assertEquals(1L, storage.unreadMentions("Tom Smith"))
    }
}
