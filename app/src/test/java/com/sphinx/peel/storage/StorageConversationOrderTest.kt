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
    fun `a later join does not reorder, a boost does`() {
        val s = newStorage()
        s.chat("a", "A"); s.chat("b", "B")
        s.insertMessages(listOf(msg("a", 0, 100), msg("b", 0, 200), msg("a", 29, 300), msg("a", 1, 400)))
        val rows = s.conversations()
        assertEquals(listOf("a", "b"), rows.map { it.chat })
        assertEquals(300L, rows[0].lastTimestamp)
    }

    private fun Storage.mine(chat: String, uuid: String, timestamp: Long) {
        next++
        insertMessages(listOf(MessageRow(uuid = uuid, serverIndex = next, type = 0, chat = chat, fromMe = true, sender = null, body = "mine", msat = null, timestamp = timestamp, tag = null, paymentHash = null)))
    }

    private fun Storage.boost(chat: String, target: String, timestamp: Long, fromMe: Boolean = false) {
        next++
        insertMessages(
            listOf(
                MessageRow(
                    uuid = "b$next", serverIndex = next, type = 29, chat = chat, fromMe = fromMe, sender = """{"alias":"Paul"}""",
                    body = """{"replyUuid":"$target"}""", msat = 5000, timestamp = timestamp, tag = null, paymentHash = null,
                ),
            ),
        )
    }

    @Test
    fun `a boost on my message becomes the preview and moves the row`() {
        val s = newStorage()
        s.chat("a", "A"); s.chat("b", "B")
        s.mine("a", "m1", 100)
        s.insertMessages(listOf(msg("b", 0, 200)))
        s.boost("a", "m1", 300)
        val rows = s.conversations()
        assertEquals(listOf("a", "b"), rows.map { it.chat })
        assertEquals(1L, rows[0].unread)
        assertEquals(300L, rows[0].lastTimestamp)
        assertEquals("Paul boosted your message: 5 sats", rows[0].previewLine)
    }

    @Test
    fun `a boost on someone else's message is the preview and unread, my own boost is not unread`() {
        val s = newStorage()
        s.chat("a", "A")
        s.mine("a", "m1", 100)
        s.insertMessages(listOf(msg("a", 0, 150).copy(uuid = "theirs")))
        s.boost("a", "theirs", 300)
        val other = s.conversations().single()
        assertEquals(300L, other.lastTimestamp)
        assertEquals(2L, other.unread) // their text and their boost (on theirs, still counted)
        assertEquals("Paul boosted a message: 5 sats", other.previewLine)
        s.boost("a", "m1", 400, fromMe = true)
        val mine = s.conversations().single()
        assertEquals(400L, mine.lastTimestamp)
        assertEquals(2L, mine.unread) // our own boost adds none
        assertEquals("You boosted a message: 5 sats", mine.previewLine)
    }

    @Test
    fun `any boost moves the row`() {
        val s = newStorage()
        s.chat("a", "A"); s.chat("b", "B")
        s.insertMessages(listOf(msg("a", 0, 100), msg("b", 0, 200)))
        s.boost("a", "nothing", 300)
        assertEquals(listOf("a", "b"), s.conversations().map { it.chat })
    }

    @Test
    fun `a deleted boost is not the last message`() {
        val s = newStorage()
        s.chat("a", "A")
        s.mine("a", "m1", 100)
        s.boost("a", "m1", 300)
        val id = s.conversations().single()
        assertEquals(300L, id.lastTimestamp)
        s.insertMessages(listOf(msg("a", 0, 50)))
        val db = s.javaClass.getDeclaredField("helper").also { it.isAccessible = true }.get(s) as android.database.sqlite.SQLiteOpenHelper
        db.writableDatabase.execSQL("UPDATE message SET deleted = 1 WHERE type = 29")
        val row = s.conversations().single()
        assertEquals(100L, row.lastTimestamp)
        assertEquals("Message deleted", row.previewLine)
    }

    @Test
    fun `chats without a message go last, by alias`() {
        val s = newStorage()
        s.chat("z", "Zed"); s.chat("m", "Mia"); s.chat("a", "Ann")
        s.insertMessages(listOf(msg("z", 0, 100), msg("m", 29, 500)))
        assertEquals(listOf("m", "z", "a"), s.conversations().map { it.chat })
    }

    @Test
    fun `equal timestamps break by alias`() {
        val s = newStorage()
        s.chat("b", "Bob"); s.chat("a", "Amy")
        s.insertMessages(listOf(msg("b", 0, 100), msg("a", 0, 100)))
        assertEquals(listOf("a", "b"), s.conversations().map { it.chat })
    }
}
