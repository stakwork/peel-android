package com.sphinx.peel.storage

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/// iOS v27 (`Message.isControl`): a control message in a direct message is a line, never unread; stored with the flag, and set by the v15 migration
/// for the rows stored before. A tribe's message with the same metadata is a message.
@RunWith(RobolectricTestRunner::class)
class StorageControlTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private var next = 0L
    private val ack = """{"peel":{"v":1,"op":"ack","re":"r"}}"""

    private fun body(content: String, metadata: String? = null) =
        JSONObject().put("content", content).apply { if (metadata != null) put("metadata", metadata) }.toString()

    private fun msg(chat: String, body: String): MessageRow {
        next++
        return MessageRow(uuid = "u$next", serverIndex = next, type = 0, chat = chat, fromMe = false, sender = null, body = body, msat = null, timestamp = next * 1000, tag = null, paymentHash = null)
    }

    @Test fun `a control message in a direct message is never unread, a tribe's is`() {
        val s = Storage(context, "control-${System.nanoTime()}")
        s.upsertContact("dm", "Agent", null, isTribe = false, ready = true)
        s.upsertContact("tribe", "Tribe", null, isTribe = true, ready = true)
        s.insertMessages(
            listOf(
                msg("dm", body("hello")),
                msg("dm", body("Peel asked your Mac", ack)),
                msg("dm", body("a status line", """{"tz":"GMT+1","peel":{"v":1,"op":"status","run":"x","state":"s","text":"t"}}""")),
                // Talk extras (no `op`) are an ordinary message.
                msg("dm", body("Sent.", """{"peel":{"tools":[{"name":"x"}]}}""")),
                msg("tribe", body("hi", ack)),
            ),
        )
        val rows = s.conversations().associateBy { it.chat }
        assertEquals(2L, rows["dm"]!!.unread)
        assertEquals(1L, rows["tribe"]!!.unread)
    }

    /// A v14 database with a control message and a tribe's lookalike, as the app before this port stored them.
    @Test fun `a v14 database opens at v15 with its control messages marked`() {
        val account = "migrate-${System.nanoTime()}"
        val file = context.getDatabasePath("peel-$account.db").also { it.parentFile!!.mkdirs() }
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL("CREATE TABLE setting (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
        db.execSQL("INSERT INTO setting VALUES ('network', 'bitcoin')")
        db.execSQL("CREATE TABLE contact (pubkey TEXT PRIMARY KEY NOT NULL, isTribe INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("INSERT INTO contact VALUES ('dm', 0)")
        db.execSQL("INSERT INTO contact VALUES ('tribe', 1)")
        db.execSQL("CREATE TABLE message (id INTEGER PRIMARY KEY AUTOINCREMENT, type INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0, chat TEXT NOT NULL, body TEXT)")
        fun insert(chat: String, body: String, type: Int = 0, deleted: Int = 0) =
            db.execSQL("INSERT INTO message (type, deleted, chat, body) VALUES (?, ?, ?, ?)", arrayOf<Any>(type, deleted, chat, body))
        insert("dm", body("plain"))                                             // 1
        insert("dm", body("Peel asked your Mac", ack))                          // 2: control
        insert("dm", body("escaped", """{"\u0070eel":{"v":1,"op":"ack","re":"r"}}""")) // 3: control, key spelled with an escape
        insert("dm", body("v2", """{"peel":{"v":2,"op":"ack","re":"r"}}"""))   // 4: unsupported is still a control message
        insert("dm", body("extras", """{"peel":{"tools":[]}}"""))               // 5: not
        insert("tribe", body("hi", ack))                                        // 6: a tribe's
        insert("dm", body("gone", ack), deleted = 1)                            // 7: deleted
        insert("dm", body("pay", ack), type = 5)                                // 8: not text
        db.version = 14
        db.close()

        val storage = Storage(context, account)
        assertEquals("bitcoin", storage.value(Setting.NETWORK))
        val check = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        val control = check.rawQuery("SELECT id FROM message WHERE isControl = 1 ORDER BY id", null).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
        assertEquals(listOf(2L, 3L, 4L), control)
        assertEquals(Storage.VERSION, check.version) // v16 (docs/phases/41-worker-onboarding.md), v17 (contact.isNew), v18 and v19 (in-chat search) after it
        check.close()
    }
}
