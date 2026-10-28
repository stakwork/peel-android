package com.sphinx.peel.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/// One contact as `message`/`contact` know it: a pubkey, and whatever name
/// and photo its own messages have revealed so far (names and photos are
/// not core state — PeelCore's `contact` table comment says the same).
/// `isTribe` tells a tribe from a direct contact — see `Engine.contactHint`
/// for how it's actually detected (a tribes server marking what it
/// forwards, not anything the chat itself declares).
data class ContactRow(
    val pubkey: String,
    val alias: String?,
    val photoUrl: String?,
    val isTribe: Boolean,
    val ready: Boolean,
)

/// One row the dashboard shows: a chat (a ready contact or a discovered
/// tribe) and its newest message, if it has one yet. The Android shape of
/// PeelCore's `Conversation` (`Models.swift`) and `Storage.conversations()`.
data class ConversationRow(
    val chat: String,
    val alias: String?,
    val photoUrl: String?,
    val isTribe: Boolean,
    val lastContent: String?,
    val lastTimestamp: Long?,
    val lastFromMe: Boolean?,
) {
    /// The alias, or the chat's own key trimmed to 12 characters — same
    /// fallback as `Conversation.name`.
    val name: String get() = alias?.takeIf { it.isNotEmpty() } ?: chat.take(12)
}

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

    /// Every stored message's `chat`, `fromMe` and `sender` — what
    /// `Engine.contactHint` needs, and nothing it doesn't: `backfillContactsIfNeeded`
    /// re-derives `contact` rows from these without needing a whole
    /// `MessageRow` (`uuid`/`body`/etc. stay unset; nothing reads them off
    /// a backfilled row).
    fun messageSenders(): List<MessageRow> {
        val rows = mutableListOf<MessageRow>()
        helper.readableDatabase
            // Oldest first, same order a restore's own pages arrive in:
            // `upsertContact`'s "newest message wins" only holds if this
            // processes them in that order too, not whatever SQLite's
            // default row order happens to be.
            .query("message", arrayOf("chat", "fromMe", "sender"), null, null, null, null, "id ASC")
            .use { cursor ->
                while (cursor.moveToNext()) {
                    rows.add(
                        MessageRow(
                            uuid = null,
                            serverIndex = null,
                            type = 0,
                            chat = cursor.getString(0),
                            fromMe = cursor.getInt(1) != 0,
                            sender = cursor.getString(2),
                            body = null,
                            msat = null,
                            timestamp = null,
                            tag = null,
                            paymentHash = null,
                        ),
                    )
                }
            }
        return rows
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
    /// `ready` defaults false (only `markContactsReady` sets a direct
    /// contact ready); a tribe passes `ready = true` itself — nothing else
    /// ever confirms one the way the core confirms a direct contact's key
    /// exchange, so being seen at all is as ready as a tribe gets here.
    ///
    /// Once a chat is a tribe, `alias`/`photoUrl` are unconditionally
    /// `null` — never written from a message, whatever this call is asked
    /// to set them to, and never *kept* from one either: a tribe's own
    /// name and photo come from the tribes server itself
    /// (`Engine.contactHint`'s doc comment), not from any message, so
    /// there is no "newest message's alias" for a tribe to fall back to
    /// in the first place. This matters because not every one of a
    /// tribe's own messages carries the `host` field that identifies it
    /// as one — a real restore has some that don't (delivery/control
    /// messages, by the look of them) — so one of those can be the first
    /// call seen for a chat, writing a member's name in as if it belonged
    /// to the chat itself, before a later call reveals it's a tribe.
    /// Always nulling here (rather than only at the moment `isTribe`
    /// turns true) fixes that regardless of call order, and also heals a
    /// row a previous, buggier version of this method already got wrong
    /// — confirmed on a real account (`docs/phases/04-dashboard.md`) — the
    /// next time anything calls this for that pubkey.
    fun upsertContact(pubkey: String, alias: String?, photoUrl: String?, isTribe: Boolean, ready: Boolean = false) {
        val db = helper.writableDatabase
        val existing = readContact(db, pubkey)
        val tribe = isTribe || existing?.isTribe == true
        val values = ContentValues().apply {
            put("pubkey", pubkey)
            put("alias", if (tribe) null else (alias ?: existing?.alias))
            put("photoUrl", if (tribe) null else (photoUrl ?: existing?.photoUrl))
            put("isTribe", if (tribe) 1 else 0)
            put("ready", if (ready || existing?.ready == true) 1 else 0)
        }
        db.insertWithOnConflict("contact", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /// A tribe's real name and photo, from its own registry
    /// (`Engine.TribeInfo.fetch`) — the one legitimate source
    /// `upsertContact` itself refuses to accept them from (a message).
    /// No-op for a pubkey not already known as a tribe: this never turns
    /// a direct contact into one, only fills in what a known tribe's
    /// `contactHint` could never have, by design.
    fun updateTribeInfo(pubkey: String, name: String, photoUrl: String?) {
        val db = helper.writableDatabase
        val existing = readContact(db, pubkey) ?: return
        if (!existing.isTribe) return
        val values = ContentValues().apply {
            put("pubkey", pubkey)
            put("alias", name)
            put("photoUrl", photoUrl)
            put("isTribe", 1)
            put("ready", if (existing.ready) 1 else 0)
        }
        db.insertWithOnConflict("contact", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /// Marks contacts the core itself now recognises (`listContacts`) —
    /// "ready" the way PeelCore's `contact.ready` is: the core holds their
    /// key, so this is a real conversation, not just a name seen in a
    /// message. Never a tribe: `listContacts` is the core's own direct-contact
    /// registry, tribes aren't in it.
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
                    put("isTribe", if (existing?.isTribe == true) 1 else 0)
                    put("ready", 1)
                }
                db.insertWithOnConflict("contact", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /// The dashboard's rows: every ready contact or tribe, with its newest
    /// message if it has one, newest chat first. A chat with no message
    /// yet (just key-exchanged, say) sorts last — SQLite treats `NULL` as
    /// lowest, which is what a `DESC` newest-first order wants here.
    fun conversations(): List<ConversationRow> {
        val rows = mutableListOf<ConversationRow>()
        helper.readableDatabase.rawQuery(
            """
            SELECT
                c.pubkey, c.alias, c.photoUrl, c.isTribe,
                (SELECT body FROM message WHERE chat = c.pubkey ORDER BY timestamp DESC LIMIT 1),
                (SELECT timestamp FROM message WHERE chat = c.pubkey ORDER BY timestamp DESC LIMIT 1),
                (SELECT fromMe FROM message WHERE chat = c.pubkey ORDER BY timestamp DESC LIMIT 1)
            FROM contact c
            WHERE c.ready = 1
            ORDER BY (SELECT timestamp FROM message WHERE chat = c.pubkey ORDER BY timestamp DESC LIMIT 1) DESC
            """.trimIndent(),
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows.add(
                    ConversationRow(
                        chat = cursor.getString(0),
                        alias = cursor.getString(1),
                        photoUrl = cursor.getString(2),
                        isTribe = cursor.getInt(3) != 0,
                        lastContent = cursor.getString(4),
                        lastTimestamp = if (cursor.isNull(5)) null else cursor.getLong(5),
                        lastFromMe = if (cursor.isNull(6)) null else cursor.getInt(6) != 0,
                    ),
                )
            }
        }
        return rows
    }

    private fun readContact(db: SQLiteDatabase, pubkey: String): ContactRow? {
        db.query(
            "contact", arrayOf("pubkey", "alias", "photoUrl", "isTribe", "ready"),
            "pubkey = ?", arrayOf(pubkey), null, null, null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return ContactRow(
                pubkey = cursor.getString(0),
                alias = cursor.getString(1),
                photoUrl = cursor.getString(2),
                isTribe = cursor.getInt(3) != 0,
                ready = cursor.getInt(4) != 0,
            )
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
            v3(db)
        }

        // Append only, same rule PeelCore's migrator follows: a new version
        // adds its own step here and in onCreate (a fresh database runs
        // every step once; an existing one runs only what it's missing).
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) v2(db)
            if (oldVersion < 3) v3(db)
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

        // `isTribe`: whether a tribes server, not the chat itself, was
        // ever seen forwarding something in it (`Engine.contactHint`).
        // PeelCore's own v6 adds a whole `tribe` table (price, escrow,
        // membership, roles); this is only the one bit the dashboard needs
        // to sort a chat into the right section.
        private fun v3(db: SQLiteDatabase) {
            db.execSQL("ALTER TABLE contact ADD COLUMN isTribe INTEGER NOT NULL DEFAULT 0")
        }

        companion object {
            const val VERSION = 3
        }
    }
}
