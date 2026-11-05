package com.sphinx.peel.storage

import androidx.test.core.app.ApplicationProvider
import com.sphinx.peel.engine.ReadToggle
import com.sphinx.peel.engine.SendRetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/// Mute, Mark unread and the resend writes (`Storage.v10`).
@RunWith(RobolectricTestRunner::class)
class StorageChatActionsTest {
    private fun newStorage() = Storage(ApplicationProvider.getApplicationContext(), account = "test-${System.nanoTime()}")

    private fun body(text: String) = org.json.JSONObject().put("content", text).toString()

    private fun incoming(uuid: String, index: Long, chat: String = "alice", body: String = "hi", type: Int = 0) =
        MessageRow(uuid = uuid, serverIndex = index, type = type, chat = chat, fromMe = false, sender = null, body = body, msat = null, timestamp = index * 100, tag = null, paymentHash = null)

    private fun sent(uuid: String, status: String?, timestamp: Long, tag: String? = "tag-$uuid", msat: Long? = null, chat: String = "alice") =
        MessageRow(uuid = uuid, serverIndex = null, type = 0, chat = chat, fromMe = true, sender = null, body = "yo", msat = msat, timestamp = timestamp, tag = tag, paymentHash = null, status = status)

    @Test
    fun `mute persists, keeps the read marker and hides the badge`() {
        val storage = newStorage()
        storage.upsertContact("alice", "Alice", null, isTribe = false, ready = true)
        storage.insertMessages(listOf(incoming("1", 10), incoming("2", 11)))
        storage.setRead("alice", 10, pending = false)

        storage.setMute("alice", 2)
        val row = storage.conversations().single()
        assertEquals(2, row.muteLevel)
        assertEquals(1L, row.unread)
        assertEquals(0L, row.shownUnread)
        assertEquals(10L, storage.lastRead("alice"))

        storage.setMute("alice", 0)
        assertEquals(1L, storage.conversations().single().shownUnread)
    }

    @Test
    fun `mentions only shows the unread mentions in a tribe`() {
        val storage = newStorage()
        storage.apply(Storage.Commit(settings = mapOf(Setting.ALIAS to "Me")))
        storage.upsertContact("tribe", "Tribe", null, isTribe = true, ready = true)
        storage.insertMessages(listOf(incoming("1", 1, "tribe", body("hello")), incoming("2", 2, "tribe", body("hey @Me look")), incoming("3", 3, "tribe", body("more"))))
        storage.setMute("tribe", 1)

        val row = storage.conversations().single()
        assertEquals(3L, row.unread)
        assertEquals(1L, row.mentions)
        assertEquals(1L, row.shownUnread)

        storage.setRead("tribe", 2, pending = false)
        storage.setMute("tribe", 1)
        assertEquals(0L, storage.conversations().single().shownUnread)
    }

    @Test
    fun `mark unread puts the marker below the newest incoming message and survives a reopen`() {
        val name = "test-${System.nanoTime()}"
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val storage = Storage(context, account = name)
        storage.upsertContact("alice", "Alice", null, isTribe = false, ready = true)
        storage.insertMessages(listOf(incoming("1", 10), incoming("2", 11), sent("3", "DELIVERED", 5_000).copy(serverIndex = 12)))
        storage.setRead("alice", 12, pending = false)

        val before = storage.conversations().single()
        assertEquals(0L, before.unread)
        assertEquals(ReadToggle.MARK_UNREAD, before.readToggle)
        assertEquals(11L, storage.newestIncomingIndex("alice"))

        // Moves back, which a plain read never does.
        assertFalse(storage.setRead("alice", 5, pending = false))
        assertTrue(storage.setRead("alice", 10, pending = false, allowBack = true))
        val after = Storage(context, account = name).conversations().single()
        assertEquals(1L, after.unread)
        assertEquals(ReadToggle.MARK_READ, after.readToggle)

        // Opening the chat clears it.
        storage.setRead("alice", storage.highestServerIndex("alice")!!, pending = false)
        assertEquals(0L, storage.conversations().single().unread)
    }

    @Test
    fun `a chat nobody wrote in offers no read toggle`() {
        val storage = newStorage()
        storage.upsertContact("alice", "Alice", null, isTribe = false, ready = true)
        storage.insertMessages(listOf(sent("1", "DELIVERED", 100).copy(serverIndex = 3)))
        assertNull(storage.conversations().single().readToggle)
        assertNull(storage.newestIncomingIndex("alice"))
    }

    @Test
    fun `server answers do not undo a change made here since`() {
        val storage = newStorage()
        storage.upsertContact("alice", "A", null, false, true)
        storage.upsertContact("bob", "B", null, false, true)
        storage.setMute("alice", 2)
        storage.applyServerMutes(mapOf("alice" to 0, "bob" to 2), except = setOf("alice"))
        assertEquals(mapOf("alice" to 2, "bob" to 2), storage.conversations().associate { it.chat to it.muteLevel })
    }

    @Test
    fun `resend reuses the row and is idempotent`() {
        val storage = newStorage()
        storage.insertMessages(listOf(sent("old", "FAILED", 1_000)))
        val id = storage.messages("alice").single().id

        assertTrue(storage.commitResend(id, "alice", "new", "new-tag", null, null, now = 50_000, auto = false))
        val row = storage.messages("alice").single()
        assertEquals(id, row.id)
        assertEquals("new", row.uuid)
        assertEquals("new-tag", row.tag)
        assertEquals("PENDING", row.status)
        assertEquals(50_000L, row.timestamp)
        assertEquals(1_000L, row.firstSentAt)
        assertNull(row.autoResentAt)

        // A second, concurrent Retry finds a fresh pending row: refused.
        assertFalse(storage.commitResend(id, "alice", "third", "third-tag", null, null, now = 50_001, auto = false))
        assertEquals("new", storage.messages("alice").single().uuid)
        assertEquals(1, storage.messages("alice").size)

        // Its echo marks the one row delivered; no second message.
        storage.insertMessages(listOf(sent("new", "PENDING", 50_000, tag = "new-tag").copy(serverIndex = 9)))
        assertEquals(listOf("DELIVERED"), storage.messages("alice").map { it.status })
    }

    @Test
    fun `resend refuses delivered rows and pending ones that may have paid`() {
        val storage = newStorage()
        storage.insertMessages(
            listOf(sent("d", "DELIVERED", 1_000), sent("tribePending", "PENDING", 1_000, msat = 3_000), sent("tribeFailed", "FAILED", 1_000, msat = 3_000)),
        )
        val byUuid = storage.messages("alice").associateBy { it.uuid }
        assertFalse(storage.commitResend(byUuid.getValue("d").id, "alice", "x", "x", null, null, 90_000, false))
        assertFalse(storage.commitResend(byUuid.getValue("tribePending").id, "alice", "y", "y", null, null, 90_000, false))
        assertTrue(storage.commitResend(byUuid.getValue("tribeFailed").id, "alice", "z", "z", null, 3_000, 90_000, true))
        assertEquals(90_000L, storage.message(byUuid.getValue("tribeFailed").id)!!.autoResentAt)
    }

    @Test
    fun `undelivered sends for a reconnect skip tribes, paid, already auto-resent and old ones`() {
        val storage = newStorage()
        storage.upsertContact("tribe", "T", null, isTribe = true, ready = true)
        storage.insertMessages(
            listOf(
                sent("ok", "FAILED", 5_000),
                sent("pending", "PENDING", 6_000),
                sent("delivered", "DELIVERED", 5_500),
                sent("old", "FAILED", 100),
                sent("paid", "FAILED", 5_100, msat = 3_000),
                sent("inTribe", "FAILED", 5_200, chat = "tribe"),
            ),
        )
        val resent = storage.messages("alice").first { it.uuid == "pending" }
        storage.commitResend(resent.id, "alice", "pending2", "t2", null, null, now = 100_000, auto = true)

        assertEquals(listOf("ok"), storage.undeliveredSends(since = 1_000).map { it.uuid })
    }

    @Test
    fun `discard drops only an undelivered message`() {
        val storage = newStorage()
        storage.insertMessages(listOf(sent("f", "FAILED", 1_000), sent("d", "DELIVERED", 1_000)))
        val byUuid = storage.messages("alice").associateBy { it.uuid }
        assertFalse(storage.discardUndelivered(byUuid.getValue("d").id))
        assertTrue(storage.discardUndelivered(byUuid.getValue("f").id))
        assertEquals(listOf("d"), storage.messages("alice").map { it.uuid })
        // A fresh pending send is not yet discardable (it may still arrive).
        storage.insertMessages(listOf(sent("p", "PENDING", System.currentTimeMillis())))
        assertFalse(storage.discardUndelivered(storage.messages("alice").first { it.uuid == "p" }.id))
        assertEquals(true, SendRetry.isDiscardable(storage.messages("alice").first { it.uuid == "d" }.copy(status = "FAILED"), 0))
    }
}
