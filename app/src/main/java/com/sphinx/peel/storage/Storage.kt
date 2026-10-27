package com.sphinx.peel.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/// Everything Peel keeps on disk for one account: `coreState` (opaque
/// entries owned by the Rust core) and `setting` (this account's own
/// config). Ported from PeelCore's Storage.swift; messages, contacts and
/// tribes come later, once there is an engine and a server connection to
/// fill them from.
class Storage(context: Context, account: String = "default") {
    private val helper = Helper(context.applicationContext, "peel-$account.db")

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

    fun close() = helper.close()

    private class Helper(context: Context, name: String) : SQLiteOpenHelper(context, name, null, VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            // v1, ported from PeelCore's Storage+Migrations.swift. Append
            // only, same rule as there: a new version adds its own
            // `if (oldVersion < N)` step in onUpgrade, never edits this one.
            db.execSQL("CREATE TABLE coreState (key TEXT PRIMARY KEY NOT NULL, value BLOB NOT NULL)")
            db.execSQL("CREATE TABLE setting (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // No migrations yet.
        }

        companion object {
            const val VERSION = 1
        }
    }
}
