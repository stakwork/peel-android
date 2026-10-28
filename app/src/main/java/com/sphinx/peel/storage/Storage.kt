package com.sphinx.peel.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/// One contact as `message`/`contact` know it: a pubkey, and whatever name
/// and photo its own messages have revealed so far (names and photos are
/// not core state — PeelCore's `contact` table comment says the same).
data class ContactRow(val pubkey: String, val alias: String?, val photoUrl: String?, val ready: Boolean)

/// One stored message, trimmed to `message` v1's columns (PeelCore's
/// `Storage+Migrations.swift`): no reply/thread/media fields yet, those
/// come from a body a chat screen actually needs to render.
data class MessageRow(
    val uuid: String?,
    val serverIndex: Long?,
    val type: Int,
    val chat: String,
    val fromMe: Boolean,
    val sender: String?,
    val body: String?,
    val msat: Long?,
    val timestamp: Long?,
    val tag: String?,
    val paymentHash: String?,
)

/// Everything Peel keeps on disk for one account: `coreState` (opaque
/// entries owned by the Rust core), `setting` (this account's own config),
/// `contact` and `message` (what a restore, or a live connection, brings
/// back). Ported from PeelCore's Storage.swift; no `tribe`/`chatState`/
/// `payment` tables yet — nothing reads them (no chat UI), so there is
/// nothing to port them for.
class Storage(context: Context, account: String = "default") {
    private val appContext = context.applicationContext
    private val dbName = "peel-$account.db"
    private val helper = Helper(appContext, dbName)

    /// Every opaque entry the core owns, for the state a core call needs
    /// passed back in.
    fun loadCoreState(): Map<String, ByteArray> {
        val state = mutableMapOf<String, ByteArray>()
        helper.readableDatabase
            .query("coreState", arrayOf("key", "value"), null, null, null, null, null)
            .use { cursor ->
                while (cursor.moveToNext()) {
                    state[cursor.getString(0)] = cursor.getBlob(1)
                }
            }
        return state
    }

    fun value(setting: Setting): String? {
        helper.readableDatabase
            .query("setting", arrayOf("value"), "key = ?", arrayOf(setting.key), null, null, null)
            .use { cursor -> return if (cursor.moveToFirst()) cursor.getString(0) else null }
    }

    /// Everything one engine action changed, written in a single
    /// transaction so the account can never be half-updated. Ported from
    /// PeelCore's Storage+Writes.swift (`Commit`), trimmed to the two
    /// tables Android has so far: no messages, contacts, reads or mutes
    /// yet.
    data class Commit(
        val state: Map<String, ByteArray> = emptyMap(),
        val deleteState: List<String> = emptyList(),
        val settings: Map<Setting, String> = emptyMap(),
    )

    fun apply(commit: Commit) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            for ((key, value) in commit.state) {
                val values = ContentValues().apply {
                    put("key", key)
                    put("value", value)
                }
                db.insertWithOnConflict("coreState", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            if (commit.deleteState.isNotEmpty()) {
                val placeholders = commit.deleteState.joinToString(",") { "?" }
                db.delete("coreState", "key IN ($placeholders)", commit.deleteState.toTypedArray())
            }
            for ((setting, value) in commit.settings) {
                val values = ContentValues().apply {
                    put("key", setting.key)
                    put("value", value)
                }
                db.insertWithOnConflict("setting", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /// One page of history, stored `INSERT OR IGNORE`: a message a page
    /// repeats (two runs overlapping at a boundary) is simply skipped by
    /// its `uuid`, same as PeelCore's unique `uuid` column does.
    fun insertMessages(rows: List<MessageRow>) {
        if (rows.isEmpty()) return
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            for (row in rows) {
                val values = ContentValues().apply {
                    put("uuid", row.uuid)
                    put("serverIndex", row.serverIndex)
                    put("type", row.type)
                    put("chat", row.chat)
                    put("fromMe", if (row.fromMe) 1 else 0)
                    put("sender", row.sender)
                    put("body", row.body)
                    put("msat", row.msat)
                    put("timestamp", row.timestamp)
                    put("tag", row.tag)
                    put("paymentHash", row.paymentHash)
                }
                db.insertWithOnConflict("message", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /// A contact's alias/photo, from the newest message naming them so
    /// far — a blank field here means "unknown so far", not "cleared", so
    /// it never overwrites a value already stored (same reasoning as
    /// PeelCore's `contact` migration, which only ever takes the newest row).
    fun upsertContact(pubkey: String, alias: String?, photoUrl: String?) {
        val db = helper.writableDatabase
        val existing = readContact(db, pubkey)
        val values = ContentValues().apply {
            put("pubkey", pubkey)
            put("alias", alias ?: existing?.alias)
            put("photoUrl", photoUrl ?: existing?.photoUrl)
            put("ready", if (existing?.ready == true) 1 else 0)
        }
        db.insertWithOnConflict("contact", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /// Marks contacts the core itself now recognises (`listContacts`) —
    /// "ready" the way PeelCore's `contact.ready` is: the core holds their
    /// key, so this is a real conversation, not just a name seen in a
    /// message.
    fun markContactsReady(pubkeys: List<String>) {
        if (pubkeys.isEmpty()) return
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            for (pubkey in pubkeys) {
                val existing = readContact(db, pubkey)
                val values = ContentValues().apply {
                    put("pubkey", pubkey)
                    put("alias", existing?.alias)
                    put("photoUrl", existing?.photoUrl)
                    put("ready", 1)
                }
                db.insertWithOnConflict("contact", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun readContact(db: SQLiteDatabase, pubkey: String): ContactRow? {
        db.query("contact", arrayOf("pubkey", "alias", "photoUrl", "ready"), "pubkey = ?", arrayOf(pubkey), null, null, null)
            .use { cursor ->
                if (!cursor.moveToFirst()) return null
                return ContactRow(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getInt(3) != 0)
            }
    }

    fun close() = helper.close()

    /// Wipes this account's whole database. "Forget this account" (dev)
    /// needs this, not just `SecretStore.deleteMnemonic`: without it, the
    /// next account made on this device would start from the previous
    /// one's `coreState` (a different seed entirely), rather than empty.
    fun deleteAccount() {
        helper.close()
        appContext.deleteDatabase(dbName)
    }

    private class Helper(context: Context, name: String) : SQLiteOpenHelper(context, name, null, VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            v1(db)
            v2(db)
        }

        // Append only, same rule PeelCore's migrator follows: a new version
        // adds its own step here and in onCreate (a fresh database runs
        // every step once; an existing one runs only what it's missing).
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) v2(db)
        }

        private fun v1(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE coreState (key TEXT PRIMARY KEY NOT NULL, value BLOB NOT NULL)")
            db.execSQL("CREATE TABLE setting (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
        }

        // contact and message, ported from PeelCore's Storage+Migrations.swift
        // v1/v2/v3 (the columns a restore actually fills; chatState/tribe/
        // payment come with the features that need them).
        private fun v2(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE message (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    uuid TEXT UNIQUE,
                    serverIndex INTEGER,
                    type INTEGER NOT NULL,
                    chat TEXT NOT NULL,
                    fromMe INTEGER NOT NULL,
                    sender TEXT,
                    body TEXT,
                    msat INTEGER,
                    timestamp INTEGER,
                    tag TEXT,
                    paymentHash TEXT
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX message_serverIndex ON message (serverIndex)")
            db.execSQL("CREATE INDEX message_tag ON message (tag)")
            db.execSQL("CREATE INDEX message_chat_timestamp ON message (chat, timestamp)")
            db.execSQL(
                """
                CREATE TABLE contact (
                    pubkey TEXT PRIMARY KEY NOT NULL,
                    alias TEXT,
                    photoUrl TEXT,
                    ready INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
        }

        companion object {
            const val VERSION = 2
        }
    }
}
