package com.sphinx.peel.storage

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.sphinx.peel.engine.MessageQuery
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/// `Storage.searchMessages` over the whole stored history (iOS `Storage+Search`, peel#576): through the FTS5
/// index when this SQLite has it, else `LIKE`; the assertions hold for both.
@RunWith(RobolectricTestRunner::class)
class StorageSearchTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val account = "search-${System.nanoTime()}"
    private val storage by lazy { Storage(context, account = account) }

    /// The history page size (`Engine.HISTORY_PAGE_SIZE`, private there): a chat's older messages are past it.
    private val page = 100

    private fun sender(alias: String) = JSONObject().put("alias", alias).toString()

    private fun message(
        n: Int, content: String, chat: String = "alice", type: Int = 0, alias: String? = null, thread: String? = null, fromMe: Boolean = false,
    ) = MessageRow(
        uuid = "m$n", serverIndex = n.toLong(), type = type, chat = chat, fromMe = fromMe, sender = alias?.let(::sender),
        body = JSONObject().put("content", content).also { if (thread != null) it.put("threadUuid", thread) }.toString(),
        msat = null, timestamp = n.toLong() * 1000, tag = null, paymentHash = null,
    )

    private fun search(text: String, chat: String = "alice", limit: Int = 1000) = storage.searchMessages(MessageQuery.parse(text), chat, limit).map { it.uuid }

    @Test
    fun `a match older than a page of messages is found, newest first`() {
        val rows = mutableListOf(message(1, "the needle is here"))
        for (n in 2..page + 50) rows += message(n, "filler $n")
        rows += message(page + 51, "another NEEDLE")
        storage.insertMessages(rows)

        assertEquals(listOf("m${page + 51}", "m1"), search("needle"))
    }

    @Test
    fun `every term must appear, in any order, and a phrase stays whole`() {
        storage.insertMessages(listOf(message(1, "lunch with ann tomorrow"), message(2, "tomorrow lunch"), message(3, "lunch")))
        assertEquals(listOf("m2", "m1"), search("tomorrow lunch"))
        assertEquals(listOf("m1"), search("\"lunch with\""))
        assertEquals(emptyList<String?>(), search("\"with lunch\""))
    }

    @Test
    fun `only the chat asked for, and thread messages included`() {
        storage.insertMessages(listOf(message(1, "needle", chat = "bob"), message(2, "needle", thread = "root"), message(3, "needle", chat = "alice")))
        assertEquals(listOf("m3", "m2"), search("needle"))
        assertEquals(listOf("m1"), search("needle", chat = "bob"))
    }

    @Test
    fun `an attachment's caption is found, other types are not`() {
        storage.insertMessages(listOf(message(1, "a needle caption", type = 6), message(2, "needle", type = 5)))
        assertEquals(listOf("m1"), search("needle"))
    }

    @Test
    fun `deleted messages are excluded`() {
        val delete = JSONObject().put("content", "").put("replyUuid", "m1").toString()
        storage.insertMessages(listOf(message(1, "a needle", fromMe = false), message(2, "needle too")))
        assertEquals(listOf("m2", "m1"), search("needle"))
        storage.insertMessages(
            listOf(MessageRow(uuid = "d1", serverIndex = 9, type = 17, chat = "alice", fromMe = false, sender = null, body = delete, msat = null, timestamp = 9000, tag = null, paymentHash = null)),
        )
        assertEquals(listOf("m2"), search("needle"))
    }

    @Test
    fun `from filters by the sender's alias`() {
        storage.insertMessages(
            listOf(
                message(1, "needle one", alias = "Ann Lee"), message(2, "needle two", alias = "Bob"),
                message(3, "needle three", alias = "100%_a\\b"),
            ),
        )
        assertEquals(listOf("m1"), search("needle from:\"ann lee\""))
        assertEquals(listOf("m2"), search("from:bob"))
        assertEquals(listOf("m3"), search("from:\"100%_a\\b\""))
        // % and _ are not wildcards: "_" names no one.
        assertEquals(emptyList<String?>(), search("from:\"__\""))
    }

    @Test
    fun `the limit keeps the newest`() {
        storage.insertMessages((1..5).map { message(it, "needle $it") })
        assertEquals(listOf("m5", "m4"), search("needle", limit = 2))
    }

    @Test
    fun `the index follows an update and a delete`() {
        storage.insertMessages(listOf(message(1, "needle"), message(2, "needle")))
        val path = context.getDatabasePath("peel-$account.db").path
        val db = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            db.execSQL("UPDATE message SET content = 'haystack' WHERE uuid = 'm1'")
            assertEquals(listOf("m2"), search("needle"))
            assertEquals(listOf("m1"), search("haystack"))
            db.execSQL("DELETE FROM message WHERE uuid = 'm2'")
            assertTrue(search("needle").isEmpty())
        } finally {
            db.close()
        }
    }
}
