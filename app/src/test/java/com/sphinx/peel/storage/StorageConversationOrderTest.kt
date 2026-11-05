package com.sphinx.peel.storage

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/// `Storage.conversations()` orders like iOS's `conversationsSQL`: by the
/// newest text/attachment/payment/call message, never by boosts or joins.
@RunWith(RobolectricTestRunner::class)
class StorageConversationOrderTest {
    private fun newStorage() = Storage(ApplicationProvider.getApplicationContext(), account = "test-${System.nanoTime()}")

    private var next = 0L

    private fun msg(chat: String, type: Int, timestamp: Long, body: String? = "hi"): MessageRow {
        next++
        return MessageRow(uuid = "u$next", serverIndex = next, type = type, chat = chat, fromMe = false, sender = null, body = body, msat = null, timestamp = timestamp, tag = null, paymentHash = null)
    }

    private fun Storage.chat(pubkey: String, alias: String) = upsertContact(pubkey, alias, null, isTribe = false, ready = true)

    @Test
    fun `newest message first`() {
        val s = newStorage()
        s.chat("a", "A"); s.chat("b", "B")
        s.insertMessages(listOf(msg("a", 0, 100), msg("b", 0, 200)))
        assertEquals(listOf("b", "a"), s.conversations().map { it.chat })
    }

    @Test
    fun `a later boost or join does not reorder`() {
        val s = newStorage()
        s.chat("a", "A"); s.chat("b", "B")
        s.insertMessages(listOf(msg("a", 0, 100), msg("b", 0, 200), msg("a", 29, 300), msg("a", 1, 400)))
        val rows = s.conversations()
        assertEquals(listOf("b", "a"), rows.map { it.chat })
        assertEquals(100L, rows[1].lastTimestamp)
    }

    @Test
    fun `chats without a message go last, by alias`() {
        val s = newStorage()
        s.chat("z", "Zed"); s.chat("m", "Mia"); s.chat("a", "Ann")
        s.insertMessages(listOf(msg("z", 0, 100), msg("m", 29, 500)))
        assertEquals(listOf("z", "a", "m"), s.conversations().map { it.chat })
    }

    @Test
    fun `equal timestamps break by alias`() {
        val s = newStorage()
        s.chat("b", "Bob"); s.chat("a", "Amy")
        s.insertMessages(listOf(msg("b", 0, 100), msg("a", 0, 100)))
        assertEquals(listOf("a", "b"), s.conversations().map { it.chat })
    }
}
