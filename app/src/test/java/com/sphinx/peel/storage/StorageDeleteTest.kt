package com.sphinx.peel.storage

import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/// A type-17 message deletes the message it names by `replyUuid`, for
/// everyone — `Storage.applyDeletes`, PeelCore's own `applyDelete` ported
/// (minus the tribe-owner bypass this port can't check, no role table).
@RunWith(RobolectricTestRunner::class)
class StorageDeleteTest {
    private fun newStorage() = Storage(ApplicationProvider.getApplicationContext(), account = "test-${System.nanoTime()}")

    private fun sender(alias: String) = JSONObject().put("alias", alias).toString()

    private fun deleteBody(targetUuid: String) = JSONObject().put("content", "").put("replyUuid", targetUuid).toString()

    @Test
    fun `a DM delete from the sender applies`() {
        val storage = newStorage()
        storage.insertMessages(
            listOf(
                MessageRow(uuid = "m1", serverIndex = 1, type = 0, chat = "alice", fromMe = false, sender = null, body = "hi", msat = null, timestamp = 100, tag = null, paymentHash = null),
                MessageRow(uuid = "d1", serverIndex = 2, type = 17, chat = "alice", fromMe = false, sender = null, body = deleteBody("m1"), msat = null, timestamp = 200, tag = null, paymentHash = null),
            ),
        )

        val messages = storage.messages("alice")
        val target = messages.first { it.uuid == "m1" }
        assertTrue(target.deleted)
        assertNull(target.body)
    }

    @Test
    fun `a DM delete cannot target a message that was not the deleter's own`() {
        val storage = newStorage()
        storage.insertMessages(
            listOf(
                MessageRow(uuid = "mine", serverIndex = 1, type = 0, chat = "alice", fromMe = true, sender = null, body = "hey", msat = null, timestamp = 100, tag = null, paymentHash = null),
                MessageRow(uuid = "d1", serverIndex = 2, type = 17, chat = "alice", fromMe = false, sender = null, body = deleteBody("mine"), msat = null, timestamp = 200, tag = null, paymentHash = null),
            ),
        )

        val target = storage.messages("alice").first { it.uuid == "mine" }
        assertFalse(target.deleted)
        assertEquals("hey", target.body)
    }

    @Test
    fun `this device's own delete applies once confirmed, not while still pending`() {
        val storage = newStorage()
        storage.insertMessages(
            listOf(
                MessageRow(uuid = "mine", serverIndex = 1, type = 0, chat = "alice", fromMe = true, sender = null, body = "hey", msat = null, timestamp = 100, tag = null, paymentHash = null),
            ),
        )
        storage.insertMessages(
            listOf(
                MessageRow(
                    uuid = "d1", serverIndex = null, type = 17, chat = "alice", fromMe = true, sender = null,
                    body = deleteBody("mine"), msat = null, timestamp = 200, tag = "del-tag", paymentHash = null, status = "PENDING",
                ),
            ),
        )
        // Not yet confirmed: the target is untouched.
        assertFalse(storage.messages("alice").first { it.uuid == "mine" }.deleted)

        // The echo arrives, resolving the uuid conflict to DELIVERED in
        // the same insertMessages call that re-checks every delete in
        // the batch.
        storage.insertMessages(
            listOf(
                MessageRow(uuid = "d1", serverIndex = 2, type = 17, chat = "alice", fromMe = true, sender = null, body = deleteBody("mine"), msat = null, timestamp = 200, tag = "del-tag", paymentHash = null),
            ),
        )

        val target = storage.messages("alice").first { it.uuid == "mine" }
        assertTrue(target.deleted)
        assertNull(target.body)
    }

    @Test
    fun `a tribe delete applies only when the deleter's own alias matches the target's`() {
        val storage = newStorage()
        storage.upsertContact("tribe1", null, null, isTribe = true)
        storage.insertMessages(
            listOf(
                MessageRow(uuid = "m1", serverIndex = 1, type = 0, chat = "tribe1", fromMe = false, sender = sender("Alice"), body = "hi", msat = null, timestamp = 100, tag = null, paymentHash = null),
                MessageRow(uuid = "m2", serverIndex = 2, type = 0, chat = "tribe1", fromMe = false, sender = sender("Bob"), body = "yo", msat = null, timestamp = 150, tag = null, paymentHash = null),
                // Bob tries to delete Alice's message: refused, alias mismatch.
                MessageRow(uuid = "d1", serverIndex = 3, type = 17, chat = "tribe1", fromMe = false, sender = sender("Bob"), body = deleteBody("m1"), msat = null, timestamp = 200, tag = null, paymentHash = null),
                // Alice deletes her own message: applies, alias matches.
                MessageRow(uuid = "d2", serverIndex = 4, type = 17, chat = "tribe1", fromMe = false, sender = sender("Alice"), body = deleteBody("m1"), msat = null, timestamp = 201, tag = null, paymentHash = null),
            ),
        )

        val rows = storage.messages("tribe1").associateBy { it.uuid }
        assertTrue(rows.getValue("m1").deleted)
        assertFalse(rows.getValue("m2").deleted)
    }

    @Test
    fun `a delete naming a non-content message, or one already deleted, is a no-op`() {
        val storage = newStorage()
        storage.insertMessages(
            listOf(
                MessageRow(uuid = "boost1", serverIndex = 1, type = 29, chat = "alice", fromMe = false, sender = null, body = "{}", msat = 1000, timestamp = 100, tag = null, paymentHash = null),
                MessageRow(uuid = "d1", serverIndex = 2, type = 17, chat = "alice", fromMe = false, sender = null, body = deleteBody("boost1"), msat = null, timestamp = 200, tag = null, paymentHash = null),
            ),
        )
        assertFalse(storage.messages("alice").first { it.uuid == "boost1" }.deleted)

        // Deleting the same message twice (two devices racing) stays
        // idempotent: the second delete finds the target already gone.
        storage.insertMessages(
            listOf(
                MessageRow(uuid = "m1", serverIndex = 3, type = 0, chat = "alice", fromMe = false, sender = null, body = "hi", msat = null, timestamp = 300, tag = null, paymentHash = null),
                MessageRow(uuid = "d2", serverIndex = 4, type = 17, chat = "alice", fromMe = false, sender = null, body = deleteBody("m1"), msat = null, timestamp = 400, tag = null, paymentHash = null),
                MessageRow(uuid = "d3", serverIndex = 5, type = 17, chat = "alice", fromMe = false, sender = null, body = deleteBody("m1"), msat = null, timestamp = 401, tag = null, paymentHash = null),
            ),
        )
        assertTrue(storage.messages("alice").first { it.uuid == "m1" }.deleted)
    }
}
