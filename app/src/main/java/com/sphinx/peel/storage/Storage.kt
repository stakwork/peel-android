package com.sphinx.peel.storage

import com.sphinx.peel.engine.MONEY_SEND_TYPES
import com.sphinx.peel.engine.SendStatus
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.sphinx.peel.engine.InviteRecord
import com.sphinx.peel.engine.InviteRules
import com.sphinx.peel.engine.InvoiceRecord
import com.sphinx.peel.engine.Mentions
import com.sphinx.peel.engine.PaymentRecord
import org.json.JSONObject

/// `engine/Attachment.kt`'s own `ATTACHMENT_MESSAGE_TYPE`/
/// `DELETE_MESSAGE_TYPE`, copied as plain literals rather than imported —
/// `Engine` already depends on `Storage`, so importing the other
/// direction here would make the two packages depend on each other.
/// `Storage.applyDeletes` is the one place in this file that needs to
/// know a message's type by its number at all.
private const val attachmentMessageType = 6
private const val deleteMessageType = 17

/// The first message of a key exchange: someone adding this account (PeelCore's `MessageType.contactKey`).
const val CONTACT_KEY_MESSAGE_TYPE = 10

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
    /// The tribe's own price per message and escrow, in msat — PeelCore's
    /// own `Tribe.pricePerMessageMsat`/`escrowMsat`, from its registry
    /// (`TribeInfo`, `Storage.v7`). Always 0 for a direct contact, and
    /// for a tribe whose price was never fetched (offline on first
    /// sight, say — `Engine.refreshTribeNames` only fetches once, the
    /// same gap its own name/photo already has).
    val pricePerMessageMsat: Long = 0,
    val escrowMsat: Long = 0,
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
    /// Someone else's messages past this chat's read marker
    /// (`chatState.lastRead`) — PeelCore's `ChatReadState.unread`, trimmed:
    /// every non-`fromMe`, non-boost message counts, not just the content
    /// types PeelCore's own `countsAsUnreadSQL` picks out (no payment/
    /// control-message filtering here, since this port doesn't parse
    /// message bodies that finely yet).
    val unread: Long = 0,
    /// `chatState.muteLevel` as the server stores it — 0 "all" (loudest),
    /// 1 "mentions only", 2 "muted" — PeelCore's own `MuteLevel`. Kept as
    /// the raw level, not just a bool, so a chat list row can still tell
    /// "mentions only" from "fully muted" for its own trailing glyph
    /// (`ChatRow` in `ChatsScreen.kt`), even though `shownUnread` below
    /// still can't distinguish them (no mention tracking to pick a
    /// mentions-only count out of `unread`).
    val muteLevel: Int = 0,
) {
    /// The alias, or the chat's own key trimmed to 12 characters — same
    /// fallback as `Conversation.name`.
    val name: String get() = alias?.takeIf { it.isNotEmpty() } ?: chat.take(12)

    /// Any mute level above "all" hides the count — PeelCore distinguishes
    /// "mentions only" from "muted" (`ChatReadState.shownUnread`, which
    /// shows a mentions-only chat its *mentions* count); this port can't,
    /// since it has no mention tracking, so both read as muted here.
    val muted: Boolean get() = muteLevel != 0

    /// What a chat row actually shows — `unread`, unless muted.
    val shownUnread: Long get() = if (muted) 0 else unread
}

/// One row of the Threads list — PeelCore's `ThreadSummary` as
/// `Storage.threads()` builds it: a thread's original message, how many
/// replies it has, when it last moved, how many of them are new, and its
/// newest reply. See `Storage.threads()` for which threads make the list.
data class ThreadListItem(
    val conversation: ConversationRow,
    /// The thread's original message (the root every reply names by uuid).
    val root: MessageRow,
    /// Replies with words, not counting the original.
    val count: Int,
    val lastTimestamp: Long,
    /// Replies from others past the chat's read marker.
    val unread: Long,
    val latest: MessageRow,
    /// Up to the last 3 distinct senders, most recent first.
    val participants: List<MessageRow>,
)

/// Content type 0 is text, 6 an attachment (`engine/Attachment.kt`'s
/// `ATTACHMENT_MESSAGE_TYPE`); same rule as the conversation screen's
/// `isThreadContent` — PeelCore's `type IN contentSQL AND noWords = 0`.
private fun isThreadContentRow(row: MessageRow): Boolean {
    if (row.deleted || (row.type != 0 && row.type != 6)) return false
    if (row.type == 6) return true
    val content = row.body?.let { runCatching { JSONObject(it).optString("content") }.getOrNull() }.orEmpty()
    return content.isNotBlank()
}

/// What a message's own JSON `body` names as its reply/thread target —
/// shared by `Storage.insertMessages` (deriving `message.replyUuid`/
/// `threadUuid` for a row being written) and `Storage.v6` (backfilling
/// every row already stored before those columns existed). File-private,
/// not a `Storage` method, so the migration (on `Helper`, a different
/// class) can call it too without either class reaching into the other.
private fun extractReplyUuid(body: String?): String? =
    body?.let { runCatching { JSONObject(it).optString("replyUuid") }.getOrNull() }?.takeIf { it.isNotEmpty() }

private fun extractThreadUuid(body: String?): String? =
    body?.let { runCatching { JSONObject(it).optString("threadUuid") }.getOrNull() }?.takeIf { it.isNotEmpty() }

/// A message's own text, from `body`'s `content` field.
private fun messageContent(body: String?): String =
    body?.let { runCatching { JSONObject(it).optString("content") }.getOrNull() }.orEmpty()

/// A row's own claimed sender alias, from its raw `sender` JSON — used
/// only by `Storage.applyDeletes`'s own authorization check (matching a
/// tribe delete's sender against its target's, the same way
/// `ConversationScreen.kt`'s own `senderAlias` does for display; kept as
/// a separate one-line copy here rather than a cross-layer import, since
/// storage has no reason to depend on a UI file).
private fun aliasOf(senderJson: String?): String? =
    senderJson?.let { runCatching { JSONObject(it).optString("alias") }.getOrNull() }?.takeIf { it.isNotEmpty() }

/// One stored message, trimmed to `message` v1's columns (PeelCore's
/// `Storage+Migrations.swift`): no media fields yet, those come from a
/// body a chat screen actually needs to render.
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
    /// The `message` table's own row id — 0 for a row not read from it yet
    /// (one just sent, or built by a test), never a real stored id: SQLite's
    /// own `AUTOINCREMENT` starts at 1. A stable key for a message list; see
    /// `messages(in:)`.
    val id: Long = 0,
    /// A sent message's own delivery state (`PENDING`/`DELIVERED`/`FAILED`)
    /// — see `Storage.v4`. Always null for anything not `fromMe`.
    val status: String? = null,
    /// What this message replies to, by uuid — `body`'s own `replyUuid`
    /// field, a boost's too (it names what it boosts the same way a
    /// reply names what it answers). PeelCore's typed `Message.replyUUID`
    /// is always a real column, decoded once at write time; this port's
    /// `body` stays raw JSON (nothing here decodes a whole message into
    /// typed fields), so `replyUuid`/`threadUuid` are columns derived
    /// from it once, in `Storage.insertMessages` — see `Storage.v6` — not
    /// re-parsed out of JSON every time a reply, thread or boost needs
    /// to know it, the way this port briefly did before v6.
    val replyUuid: String? = null,
    /// The thread this message belongs to, by its root's uuid — `body`'s
    /// own `threadUuid` field, same derivation as `replyUuid` above.
    val threadUuid: String? = null,
    /// Deleted for everyone — PeelCore's own `Message.deleted`, set here
    /// only by `insertMessages`'s own apply-on-receipt step (a type-17
    /// message naming this one), never by a caller building a row by
    /// hand. `body` is cleared alongside it (`Storage.v8`).
    val deleted: Boolean = false,
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

    /// Where this account's decrypted attachments are cached, by `muid`
    /// — Android's own cache dir, which the OS may clear under storage
    /// pressure and which never counts as the user's data the way the
    /// files dir would: an attachment is always re-derivable from the
    /// memes server plus its key, so losing the cache only costs a
    /// re-download, never data (`Engine.downloadAttachment`).
    fun attachmentCacheDir(): java.io.File {
        val dir = java.io.File(appContext.cacheDir, "attachments/${dbName.removeSuffix(".db")}")
        dir.mkdirs()
        return dir
    }

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
        /// Settings removed in the same transaction (e.g. a finished
        /// restore's cursor goes in the commit that marks it finished).
        val deleteSettings: List<Setting> = emptyList(),
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
            for (setting in commit.deleteSettings) {
                db.delete("setting", "key = ?", arrayOf(setting.key))
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

    /// The account's own name, from the newest message it sent in a direct
    /// chat (peel's `newestOwnAlias`): the server keeps no profile, but a
    /// sent message's `sender` carries the name it was sent under. Tribe
    /// chats are skipped, tribes rewrite the sender. First non-blank
    /// trimmed alias of the newest 50 wins.
    fun newestOwnAlias(): String? {
        for (sender in ownSenders(tribes = false, withoutPayments = false)) {
            val alias = runCatching { JSONObject(sender).optString("alias") }.getOrNull()?.trim()
            if (!alias.isNullOrEmpty()) return alias
        }
        return null
    }

    /// The account's own photo, likewise (peel's `newestOwnPhotoURL`): the
    /// newest own direct, non-payment/boost message that carries a
    /// `photo_url` field decides; empty means none; only https counts. Own
    /// messages in tribes are the fallback (tribes keep the photo).
    fun newestOwnPhotoUrl(): String? {
        for (tribes in listOf(false, true)) {
            for (sender in ownSenders(tribes, withoutPayments = true)) {
                val obj = runCatching { JSONObject(sender) }.getOrNull() ?: continue
                if (!obj.has("photo_url") || obj.isNull("photo_url")) continue
                return obj.optString("photo_url").trim().takeIf { it.startsWith("https://") }
            }
        }
        return null
    }

    private fun ownSenders(tribes: Boolean, withoutPayments: Boolean): List<String> {
        val senders = mutableListOf<String>()
        val chatTest = if (tribes) "chat IN (SELECT pubkey FROM contact WHERE isTribe = 1)"
        else "chat NOT IN (SELECT pubkey FROM contact WHERE isTribe = 1) AND chat IN (SELECT pubkey FROM contact)"
        val typeTest = if (withoutPayments) " AND type NOT IN (5, 29)" else ""
        helper.readableDatabase.rawQuery(
            "SELECT sender FROM message WHERE fromMe = 1 AND sender IS NOT NULL AND serverIndex IS NOT NULL AND $chatTest$typeTest " +
                "ORDER BY serverIndex DESC LIMIT 50",
            null,
        ).use { while (it.moveToNext()) senders.add(it.getString(0)) }
        return senders
    }

    /// The highest `serverIndex` any stored message carries, or null for
    /// no message at all yet — where a catch-up sync resumes from
    /// (`Engine.catchUpMessages`), one past this. Every message a restore
    /// or a live receive stores carries its own server index, so the
    /// highest one stored already *is* "every message below this has
    /// been handled," with no separate marker to keep in sync by hand.
    /// One gap: `Engine.sendMessage`'s own optimistic row has none (its
    /// uuid is already taken when the real echo, carrying one, tries to
    /// store under it — `insertMessages`' `INSERT OR IGNORE` keeps the
    /// first), so this can understate how far caught up a chat with
    /// recent sends of its own really is, and a catch-up may re-ask for a
    /// little more than strictly new. Harmless beyond the extra round
    /// trip: a message already stored (by uuid) is ignored again.
    fun maxServerIndex(): Long? {
        helper.readableDatabase.rawQuery("SELECT MAX(serverIndex) FROM message", null).use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) return null
            return cursor.getLong(0)
        }
    }

    /// The highest `serverIndex` stored for one chat, or null for none —
    /// what `markRead` moves that chat's own marker up to.
    fun highestServerIndex(chat: String): Long? {
        helper.readableDatabase.rawQuery(
            "SELECT MAX(serverIndex) FROM message WHERE chat = ?", arrayOf(chat),
        ).use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) return null
            return cursor.getLong(0)
        }
    }

    /// Whether the newest stored message (by server index) of `chat` is
    /// ours — iOS's `setLastMessagesOnChats` rule for settling a restored
    /// chat as read. False when the chat has no indexed message.
    fun newestMessageFromMe(chat: String): Boolean {
        helper.readableDatabase.rawQuery(
            "SELECT fromMe FROM message WHERE chat = ? AND serverIndex IS NOT NULL ORDER BY serverIndex DESC LIMIT 1",
            arrayOf(chat),
        ).use { cursor ->
            return cursor.moveToFirst() && cursor.getInt(0) != 0
        }
    }

    /// Moves a chat's read marker to `index`, never back (PeelCore's
    /// `markRead` doc comment: "the server's may be past what is stored
    /// here"). `pending` says whether this device could actually tell the
    /// server just now (`Engine.markRead`: true offline, false once the
    /// `read` call has gone out) — kept as `readPending` for
    /// `pendingReads` to find and resend later, and can change on its own
    /// even when `index` doesn't (flushing a pending marker once a
    /// connection comes back resends the *same* index, only to clear the
    /// flag). Returns whether anything actually changed, the way
    /// PeelCore's own `setRead` returns whether the marker moved: a
    /// caller only publishes when it did.
    fun setRead(chat: String, index: Long, pending: Boolean): Boolean {
        val db = helper.writableDatabase
        val existing = readChatState(db, chat)
        val current = existing?.lastRead ?: -1L
        if (index < current) return false
        if (index == current && pending == (existing?.readPending ?: false)) return false
        val values = ContentValues().apply {
            put("chat", chat)
            put("lastRead", index)
            put("readPending", if (pending) 1 else 0)
            put("muteLevel", existing?.muteLevel ?: 0)
        }
        db.insertWithOnConflict("chatState", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        return true
    }

    /// This chat's own read marker, or null if it has none yet — the
    /// snapshot a conversation screen takes once, right as it opens and
    /// before its own `markRead` call moves it, to know where "new
    /// messages" started this time (PeelCore's own `AppModel.newMessages`,
    /// captured the same way at open).
    fun lastRead(chat: String): Long? = readChatState(helper.readableDatabase, chat)?.lastRead

    /// Read markers set here while offline, not yet sent — PeelCore's
    /// `pendingReads`. What `Engine.fetchReadsAndMutes`'s
    /// `publishPendingReads` resends once a connection answers `getReads`.
    fun pendingReads(): Map<String, Long> {
        val reads = mutableMapOf<String, Long>()
        helper.readableDatabase.query(
            "chatState", arrayOf("chat", "lastRead"), "readPending != 0 AND lastRead IS NOT NULL",
            null, null, null, null,
        ).use { cursor ->
            while (cursor.moveToNext()) reads[cursor.getString(0)] = cursor.getLong(1)
        }
        return reads
    }

    /// The server's own read markers (`RunReturn.lastRead`, decoded by the
    /// caller), which are the truth once they answer `getReads` — applied
    /// to every chat they name, *except* one with a marker set here that
    /// hasn't reached the server yet (`readPending`): that one is left for
    /// `publishPendingReads` to push forward instead of being quietly
    /// reset backward by an answer that predates it.
    fun applyServerReads(reads: Map<String, Long>) {
        if (reads.isEmpty()) return
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            for ((chat, index) in reads) {
                val existing = readChatState(db, chat)
                if (existing?.readPending == true) continue
                val values = ContentValues().apply {
                    put("chat", chat)
                    put("lastRead", index)
                    put("readPending", 0)
                    put("muteLevel", existing?.muteLevel ?: 0)
                }
                db.insertWithOnConflict("chatState", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /// The server's own mute levels (`RunReturn.muteLevels`), applied the
    /// same way as reads — these are set from another device (or a
    /// previous session), never from any UI this port has yet.
    fun applyServerMutes(mutes: Map<String, Int>) {
        if (mutes.isEmpty()) return
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            for ((chat, level) in mutes) {
                val existing = readChatState(db, chat)
                val values = ContentValues().apply {
                    put("chat", chat)
                    put("lastRead", existing?.lastRead)
                    put("readPending", if (existing?.readPending == true) 1 else 0)
                    put("muteLevel", level)
                }
                db.insertWithOnConflict("chatState", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private data class ChatStateRow(val lastRead: Long?, val readPending: Boolean, val muteLevel: Int)

    private fun readChatState(db: SQLiteDatabase, chat: String): ChatStateRow? {
        db.query(
            "chatState", arrayOf("lastRead", "readPending", "muteLevel"),
            "chat = ?", arrayOf(chat), null, null, null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return ChatStateRow(
                lastRead = if (cursor.isNull(0)) null else cursor.getLong(0),
                readPending = cursor.getInt(1) != 0,
                muteLevel = cursor.getInt(2),
            )
        }
    }

    /// One chat's messages, oldest first — what a conversation screen
    /// shows. Not paged yet: fine for how little history a real account
    /// here has per chat so far, revisit once a busy tribe makes "load
    /// more" worth building.
    /// Messages that may announce a call (a call message, or text holding a
    /// call room link) since [sinceMs], newest first — PeelCore's
    /// `Storage.recentCallMessages`.
    fun recentCallMessages(sinceMs: Long): List<MessageRow> = readMessages(
        "timestamp >= ? AND deleted = 0 AND (type = 32 OR (type = 0 AND instr(body, 'sphinx.call.') > 0))",
        arrayOf(sinceMs.toString()), "timestamp DESC, id DESC",
    ).take(500)

    fun messages(chat: String): List<MessageRow> = readMessages("chat = ?", arrayOf(chat), "timestamp ASC, id ASC")

    /// The newest `limit` messages that mention this account's own tribe
    /// alias, newest first — PeelCore's `Storage.mentions()`. There is no
    /// `mentionsMe` column here: a SQL prefilter (someone else's, not deleted,
    /// in a tribe, text containing an `@`) picks the candidates and
    /// `Mentions.mentions` decides in Kotlin. "@all" is not a mention for
    /// this list, only the alias itself.
    fun mentions(myAlias: String, limit: Int = 200): List<MessageRow> = mentionRows(myAlias).take(limit)

    /// Mentions past their chat's read marker, over every tribe — PeelCore's
    /// `unreadMentions`, the count on the Dashboard's Mentions row. A chat
    /// with no marker counts them all; a message with no server index yet
    /// never does.
    fun unreadMentions(myAlias: String): Long {
        val lastReads = HashMap<String, Long>()
        return mentionRows(myAlias).count { row ->
            val index = row.serverIndex ?: return@count false
            index > lastReads.getOrPut(row.chat) { lastRead(row.chat) ?: -1L }
        }.toLong()
    }

    private fun mentionRows(myAlias: String): List<MessageRow> {
        val aliases = setOf(Mentions.tribeAlias(myAlias))
        return readMessages(
            "fromMe = 0 AND deleted = 0 AND type IN (0, $attachmentMessageType) AND body LIKE '%@%' " +
                "AND chat IN (SELECT pubkey FROM contact WHERE isTribe = 1)",
            emptyArray(), "timestamp DESC, id DESC",
        ).filter { row -> Mentions.mentions(messageContent(row.body), aliases) }
    }

    private fun readMessages(selection: String, args: Array<String>, orderBy: String): List<MessageRow> {
        val rows = mutableListOf<MessageRow>()
        helper.readableDatabase.query(
            "message",
            arrayOf(
                "id", "uuid", "serverIndex", "type", "chat", "fromMe", "sender", "body", "msat", "timestamp", "tag",
                "paymentHash", "status", "replyUuid", "threadUuid", "deleted",
            ),
            selection, args, null, null, orderBy,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows.add(
                    MessageRow(
                        id = cursor.getLong(0),
                        uuid = cursor.getString(1),
                        serverIndex = if (cursor.isNull(2)) null else cursor.getLong(2),
                        type = cursor.getInt(3),
                        chat = cursor.getString(4),
                        fromMe = cursor.getInt(5) != 0,
                        sender = cursor.getString(6),
                        body = cursor.getString(7),
                        msat = if (cursor.isNull(8)) null else cursor.getLong(8),
                        timestamp = if (cursor.isNull(9)) null else cursor.getLong(9),
                        tag = cursor.getString(10),
                        paymentHash = cursor.getString(11),
                        status = cursor.getString(12),
                        replyUuid = cursor.getString(13),
                        threadUuid = cursor.getString(14),
                        deleted = cursor.getInt(15) != 0,
                    ),
                )
            }
        }
        return rows
    }

    /// One page of history, stored `INSERT OR IGNORE`: a message a page
    /// repeats (two runs overlapping at a boundary) is simply skipped by
    /// its `uuid`, same as PeelCore's unique `uuid` column does. A row
    /// `Engine.sendMessage` already stored optimistically (`PENDING`) is
    /// its own kind of repeat — its own echo, now coming back through the
    /// ordinary live/restore path under the same `uuid` — and is ignored
    /// here the same way, but counts as proof the send actually reached
    /// the server: moved to `DELIVERED` rather than left exactly as
    /// `insertWithOnConflict` would leave it (unmatched silently, since
    /// `CONFLICT_IGNORE` cannot also update the row it left alone).
    fun insertMessages(rows: List<MessageRow>, contacts: List<ContactRow> = emptyList()) {
        if (rows.isEmpty() && contacts.isEmpty()) return
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
                    put("status", row.status)
                    // Derived from `body` here, once, at the one place
                    // every message (sent or received) is written —
                    // never from `row.replyUuid`/`threadUuid` directly,
                    // since `body` is the actual source of truth and a
                    // caller building a `MessageRow` by hand never sets
                    // those two fields itself (`Engine.sendMessage`, say,
                    // only ever sets `body`).
                    put("replyUuid", extractReplyUuid(row.body))
                    put("threadUuid", extractThreadUuid(row.body))
                }
                val inserted = db.insertWithOnConflict("message", null, values, SQLiteDatabase.CONFLICT_IGNORE)
                // Someone who joined with one of our invites adds us with its code
                // (a key exchange, type 10, whose sender names it): the invite is used.
                if (inserted != -1L && row.type == CONTACT_KEY_MESSAGE_TYPE && !row.fromMe) {
                    inviteCodeOf(row.sender)?.let { markInviteUsed(db, it, row.chat) }
                }
                if (inserted == -1L && row.uuid != null) {
                    db.execSQL(
                        "UPDATE message SET status = 'DELIVERED' WHERE uuid = ? AND status = 'PENDING'",
                        arrayOf(row.uuid),
                    )
                }
            }
            applyDeletes(db, rows)
            // The contacts these messages reveal go in the same
            // transaction (in order, so "newest wins" still holds): a
            // restore page used to cost one transaction for its messages
            // plus two more writes *per message* for the contact, each
            // its own journal sync.
            for (c in contacts) upsertContact(db, c.pubkey, c.alias, c.photoUrl, c.isTribe, c.ready)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /// A type-17 message in this batch deletes the message it names by
    /// `replyUuid`, for everyone — PeelCore's own `applyDelete`, ported
    /// with its exact authorization rule minus the one piece this port
    /// cannot check (a tribe *owner* deleting someone else's message: no
    /// membership/role table exists here yet, `docs/phases/14-message-
    /// menu.md`'s own "Open"). Re-reads each delete row's own *stored*
    /// state rather than trusting the `MessageRow` passed in, because the
    /// same uuid may have just been inserted fresh (a received delete) or
    /// resolved a conflict (this device's own delete, finally echoed back
    /// — `insertMessages`'s own `status` flip above, in the very same
    /// transaction, already landed by the time this runs).
    ///
    /// A delete still `fromMe` and `PENDING` (not yet confirmed the
    /// server has it) is skipped — PeelCore's own comment on this is
    /// exact: "a delete that fails to send leaves the message as
    /// everyone else still sees it." The next batch that confirms it
    /// (an echo, or `markSendStatus`) applies it then.
    private fun applyDeletes(db: SQLiteDatabase, rows: List<MessageRow>) {
        val deleteUuids = rows.filter { it.type == deleteMessageType }.mapNotNull { it.uuid }.distinct()
        for (deleteUuid in deleteUuids) {
            db.query(
                "message", arrayOf("chat", "fromMe", "sender", "status", "replyUuid"),
                "uuid = ? AND type = ?", arrayOf(deleteUuid, deleteMessageType.toString()), null, null, null,
            ).use { cursor ->
                if (!cursor.moveToFirst()) return@use
                val chat = cursor.getString(0)
                val deleteFromMe = cursor.getInt(1) != 0
                val deleteSender = cursor.getString(2)
                val status = cursor.getString(3)
                val replyUuid = cursor.getString(4) ?: return@use
                if (deleteFromMe && status == "PENDING") return@use

                db.query(
                    "message", arrayOf("id", "type", "fromMe", "sender", "deleted"),
                    "chat = ? AND uuid = ?", arrayOf(chat, replyUuid), null, null, null,
                ).use { target ->
                    if (!target.moveToFirst()) return@use
                    val type = target.getInt(1)
                    if (type != 0 && type != attachmentMessageType) return@use
                    if (target.getInt(4) != 0) return@use // already deleted
                    val targetFromMe = target.getInt(2) != 0
                    val targetSender = target.getString(3)
                    val isTribe = db.query(
                        "contact", arrayOf("isTribe"), "pubkey = ?", arrayOf(chat), null, null, null,
                    ).use { c -> c.moveToFirst() && c.getInt(0) != 0 }
                    val allowed = if (deleteFromMe) {
                        targetFromMe
                    } else if (isTribe) {
                        val deleterAlias = aliasOf(deleteSender)
                        !targetFromMe && deleterAlias != null && deleterAlias == aliasOf(targetSender)
                    } else {
                        !targetFromMe
                    }
                    if (!allowed) return@use
                    db.execSQL(
                        "UPDATE message SET deleted = 1, body = NULL WHERE id = ?",
                        arrayOf(target.getLong(0)),
                    )
                }
            }
        }
    }

    /// What the mixer said about a tag this account sent under, whether
    /// live (`RunReturn.sentStatus`) or in a `getTags` reply — PeelCore's
    /// `Storage.applyStatus`, the one forward-only rule: no status or
    /// `PENDING` becomes `DELIVERED`, `UNSETTLED` (money sends only) or
    /// `FAILED`; `UNSETTLED` becomes `DELIVERED` or `FAILED`; `FAILED`
    /// becomes `DELIVERED` (the server had it after all); `DELIVERED` never
    /// changes. A tag naming no stored row is a no-op. Returns whether a
    /// message row moved.
    fun markSendStatus(tag: String, status: SendStatus): Boolean {
        val from = status.movesFrom
        if (from.isEmpty()) return false
        val fromSql = from.joinToString(",") { "'$it'" }
        val onlyMoney = if (status == SendStatus.UNSETTLED) {
            "AND type IN (${MONEY_SEND_TYPES.joinToString(",")})"
        } else ""
        val db = helper.writableDatabase
        db.execSQL(
            "UPDATE message SET status = ? WHERE tag = ? AND fromMe = 1 AND (status IS NULL OR status IN ($fromSql)) $onlyMoney",
            arrayOf(status.raw, tag),
        )
        val moved = db.compileStatement("SELECT changes()").simpleQueryForLong() > 0
        // The invite this payment funds follows it (PeelCore's `applyStatus`).
        InviteRules.forFunding(status)?.let { next ->
            val fromInvite = InviteRules.fundingMovesFrom(status).joinToString(",") { "'${it.raw}'" }
            if (fromInvite.isNotEmpty()) {
                db.execSQL(
                    "UPDATE invite SET status = ? WHERE tag = ? AND status IN ($fromInvite)",
                    arrayOf(next.raw, tag),
                )
            }
        }
        return moved
    }

    // MARK: - Invites

    /// An invite made here, written before its payment is published.
    fun insertInvite(invite: InviteRecord) {
        helper.writableDatabase.insertWithOnConflict(
            "invite", null,
            ContentValues().apply {
                put("code", invite.code)
                put("link", invite.link)
                put("amountMsat", invite.amountMsat)
                put("nickname", invite.nickname)
                put("message", invite.message)
                put("tag", invite.tag)
                put("status", invite.status.raw)
                put("createdAt", invite.createdAt)
                put("usedBy", invite.usedBy)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /// Every invite made here, newest first.
    fun invites(): List<InviteRecord> =
        helper.readableDatabase.query("invite", null, null, null, null, null, "createdAt DESC")
            .use { c -> buildList { while (c.moveToNext()) readInvite(c)?.let(::add) } }

    fun invite(code: String): InviteRecord? =
        helper.readableDatabase.query("invite", null, "code = ?", arrayOf(code.lowercase()), null, null, null)
            .use { c -> if (c.moveToFirst()) readInvite(c) else null }

    private fun readInvite(c: android.database.Cursor): InviteRecord? {
        val status = InviteRecord.Status.fromRaw(c.getString(c.getColumnIndexOrThrow("status"))) ?: return null
        fun text(name: String) = c.getColumnIndexOrThrow(name).let { if (c.isNull(it)) null else c.getString(it) }
        return InviteRecord(
            code = c.getString(c.getColumnIndexOrThrow("code")),
            link = c.getString(c.getColumnIndexOrThrow("link")),
            amountMsat = c.getLong(c.getColumnIndexOrThrow("amountMsat")),
            nickname = text("nickname"),
            message = text("message"),
            tag = text("tag"),
            status = status,
            createdAt = c.getLong(c.getColumnIndexOrThrow("createdAt")),
            usedBy = text("usedBy"),
        )
    }

    /// Moves an invite from one of `from` to `to`; false when it was in none.
    fun setInviteStatus(code: String, to: InviteRecord.Status, from: List<InviteRecord.Status>): Boolean {
        val db = helper.writableDatabase
        val marks = from.joinToString(",") { "?" }
        // `executeUpdateDelete` counts on the connection that ran it: `changes()` run
        // afterwards could land on another pooled connection (WAL).
        val statement = db.compileStatement("UPDATE invite SET status = ? WHERE code = ? AND status IN ($marks)")
        statement.bindAllArgsAsStrings((listOf(to.raw, code.lowercase()) + from.map { it.raw }).toTypedArray())
        return statement.use { it.executeUpdateDelete() } > 0
    }

    /// Whoever joined with `code` added this account: its invite is used, if it was still waiting.
    fun markInviteUsed(code: String, by: String) = markInviteUsed(helper.writableDatabase, code, by)

    private fun markInviteUsed(db: SQLiteDatabase, code: String, by: String) {
        val pending = InviteRecord.PENDING_STATUSES.map { it.raw }
        db.execSQL(
            "UPDATE invite SET status = ?, usedBy = ? WHERE code = ? AND status IN (${pending.joinToString(",") { "?" }})",
            (listOf(InviteRecord.Status.USED.raw, by, code.lowercase()) + pending).toTypedArray(),
        )
    }

    /// The invite code a type-10 message's sender JSON carries (`code`), if any.
    private fun inviteCodeOf(sender: String?): String? {
        val json = sender ?: return null
        return runCatching { org.json.JSONObject(json).optString("code") }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /// Delivered-or-failed shorthand kept for the live `sentStatus` call sites.
    fun markSendStatus(tag: String, delivered: Boolean) {
        markSendStatus(tag, if (delivered) SendStatus.DELIVERED else SendStatus.FAILED)
    }

    /// The tags of this chat's own messages the server has not confirmed or
    /// refused (no status, or `PENDING`), newest first, at most 200 —
    /// PeelCore's `unconfirmedTags(in:)`, what `Engine.checkOwnMessageStatus`
    /// asks about when a chat is open.
    fun unconfirmedTags(chat: String): List<String> =
        helper.readableDatabase.query(
            "message", arrayOf("tag"),
            "chat = ? AND fromMe = 1 AND deleted = 0 AND tag IS NOT NULL AND (status IS NULL OR status = 'PENDING')",
            arrayOf(chat), null, null, "id DESC", "200",
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    /// Every send of ours the server has not confirmed or refused, for
    /// `Engine.checkPendingSends` — PeelCore's `pendingSendTags(textBefore:)`
    /// trimmed to this port's tables: our messages still `PENDING` that
    /// either may have paid (sats, a payment or boost) or are older than
    /// [textBefore] (ms), newest first, at most 200; our messages with no
    /// status at all (restored history, another device's), at most 200; and
    /// the invoices we pay still `PENDING`, at most 200; and the invites
    /// still `sending` (their funding payment's tag), at most 200.
    fun pendingSendTags(textBefore: Long): List<String> {
        val moneyTypes = MONEY_SEND_TYPES.joinToString(",")
        val sql = """
            SELECT tag FROM (SELECT tag FROM message WHERE fromMe = 1 AND status = 'PENDING' AND tag IS NOT NULL
                AND (COALESCE(msat, 0) > 0 OR type IN ($moneyTypes) OR COALESCE(timestamp, 0) < ?)
                ORDER BY id DESC LIMIT 200)
            UNION ALL SELECT tag FROM (SELECT tag FROM message WHERE fromMe = 1 AND status IS NULL AND tag IS NOT NULL
                ORDER BY id DESC LIMIT 200)
            UNION ALL SELECT tag FROM (SELECT tag FROM invoice WHERE incoming = 0 AND status = 'PENDING' AND tag IS NOT NULL
                ORDER BY createdAt DESC LIMIT 200)
            UNION ALL SELECT tag FROM (SELECT tag FROM invite WHERE status = 'sending' AND tag IS NOT NULL
                ORDER BY createdAt DESC LIMIT 200)
        """.trimIndent()
        return helper.readableDatabase.rawQuery(sql, arrayOf(textBefore.toString()))
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }.distinct()
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
    /// A row that already was a tribe keeps its stored name/photo; they
    /// are nulled only when a row is newly recognised as a tribe, which
    /// fixes that regardless of call order, and also heals a row a
    /// previous, buggier version of this method already got wrong
    /// — confirmed on a real account (`docs/phases/04-dashboard.md`).
    fun upsertContact(pubkey: String, alias: String?, photoUrl: String?, isTribe: Boolean, ready: Boolean = false) =
        upsertContact(helper.writableDatabase, pubkey, alias, photoUrl, isTribe, ready)

    private fun upsertContact(db: SQLiteDatabase, pubkey: String, alias: String?, photoUrl: String?, isTribe: Boolean, ready: Boolean) {
        val existing = readContact(db, pubkey)
        val tribe = isTribe || existing?.isTribe == true
        val values = ContentValues().apply {
            put("pubkey", pubkey)
            // A tribe's name/photo are never taken from the message, but one
            // already stored for a row that was a tribe (`updateTribeInfo`,
            // the registry's own answer) is kept: nulling it here made every
            // later message of the tribe reset its name to the public key.
            // A row only just recognised as a tribe still drops what a member's
            // message wrote into it.
            val keepTribeInfo = tribe && existing?.isTribe == true
            put("alias", if (tribe) (if (keepTribeInfo) existing?.alias else null) else (alias ?: existing?.alias))
            put("photoUrl", if (tribe) (if (keepTribeInfo) existing?.photoUrl else null) else (photoUrl ?: existing?.photoUrl))
            put("isTribe", if (tribe) 1 else 0)
            put("ready", if (ready || existing?.ready == true) 1 else 0)
            // `CONFLICT_REPLACE` replaces the whole row — a price fetched
            // by `updateTribeInfo` must survive the next ordinary message
            // from the same tribe, or it would be silently wiped back to
            // 0 (no price) the moment any other field about it changes.
            put("pricePerMessageMsat", existing?.pricePerMessageMsat ?: 0)
            put("escrowMsat", existing?.escrowMsat ?: 0)
        }
        db.insertWithOnConflict("contact", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /// A tribe's real name and photo, from its own registry
    /// (`Engine.TribeInfo.fetch`) — the one legitimate source
    /// `upsertContact` itself refuses to accept them from (a message).
    /// No-op for a pubkey not already known as a tribe: this never turns
    /// a direct contact into one, only fills in what a known tribe's
    /// `contactHint` could never have, by design.
    fun updateTribeInfo(pubkey: String, name: String, photoUrl: String?, pricePerMessageMsat: Long = 0, escrowMsat: Long = 0) {
        val db = helper.writableDatabase
        val existing = readContact(db, pubkey) ?: return
        if (!existing.isTribe) return
        val values = ContentValues().apply {
            put("pubkey", pubkey)
            put("alias", name)
            put("photoUrl", photoUrl)
            put("isTribe", 1)
            put("ready", if (existing.ready) 1 else 0)
            put("pricePerMessageMsat", pricePerMessageMsat)
            put("escrowMsat", escrowMsat)
        }
        db.insertWithOnConflict("contact", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /// One contact or tribe, for whatever needs more than
    /// `conversations()` shows — a tribe's own price, so far
    /// (`Engine.sendMessage`/`sendImageAttachment`/`sendBoost`).
    fun contact(pubkey: String): ContactRow? = readContact(helper.readableDatabase, pubkey)

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
                    put("pricePerMessageMsat", existing?.pricePerMessageMsat ?: 0)
                    put("escrowMsat", existing?.escrowMsat ?: 0)
                }
                db.insertWithOnConflict("contact", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /// The dashboard's rows: every ready contact or tribe, with its newest
    /// "last message" if it has one, newest chat first — PeelCore's
    /// `conversationsSQL`. Only text/attachment (0, 6) and undeleted
    /// payment/call (5, 32) messages with visible words count (noWords is
    /// approximated by a non-blank body), newest `timestamp DESC, id DESC`;
    /// joins, boosts and control messages never move a chat. Order is
    /// `lastTimestamp IS NULL, lastTimestamp DESC, alias`: chats without
    /// such a message last, ties broken by plain alias ascending.
    ///
    /// A boost only counts toward unread if it targets one of *this
    /// account's own* messages (someone boosting a third party's message
    /// is not news to you) — PeelCore's own `countsAsUnreadSQL` rule, one
    /// correlated subquery now that `message.replyUuid` is a real column
    /// (`Storage.v6`) rather than something buried in raw JSON `body`.
    fun conversations(): List<ConversationRow> {
        val rows = mutableListOf<ConversationRow>()
        helper.readableDatabase.rawQuery(
            """
            SELECT
                c.pubkey, c.alias, c.photoUrl, c.isTribe,
                l.body,
                l.timestamp,
                l.fromMe,
                (SELECT COUNT(*) FROM message m
                    WHERE m.chat = c.pubkey AND m.fromMe = 0
                        AND m.type IN (0, 5, 6, 2, 32, 29)
                        AND m.serverIndex > COALESCE((SELECT lastRead FROM chatState WHERE chat = c.pubkey), -1)
                        AND (m.type != 29 OR EXISTS (
                            SELECT 1 FROM message t WHERE t.chat = c.pubkey AND t.uuid = m.replyUuid AND t.fromMe = 1
                        ))),
                COALESCE((SELECT muteLevel FROM chatState WHERE chat = c.pubkey), 0)
            FROM contact c
            LEFT JOIN message l ON l.id = (
                SELECT id FROM message
                WHERE chat = c.pubkey AND deleted = 0
                    AND type IN (0, 5, 6, 32)
                    AND (type = 6 OR TRIM(COALESCE(body, ''), ' ' || char(9) || char(10) || char(13)) != '')
                ORDER BY timestamp DESC, id DESC LIMIT 1
            )
            WHERE c.ready = 1
            ORDER BY l.timestamp IS NULL, l.timestamp DESC, c.alias
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
                        unread = cursor.getLong(7),
                        muteLevel = cursor.getInt(8),
                    ),
                )
            }
        }
        return rows
    }

    /// The Threads list — PeelCore's `Storage.threads()`: threads (replies
    /// carrying a `threadUuid`, content with words only) in chats the
    /// dashboard lists, whose original message is stored. A tribe thread
    /// needs 2+ replies, a direct one shows from its first. Only threads
    /// this account took part in (it wrote a reply, or the original).
    /// Newest activity first, then newest original; at most 200.
    fun threads(): List<ThreadListItem> {
        val conversations = conversations().associateBy { it.chat }
        if (conversations.isEmpty()) return emptyList()
        val all = readMessages("threadUuid IS NOT NULL", emptyArray(), "timestamp ASC, id ASC")
            .filter { it.chat in conversations && isThreadContentRow(it) }
        if (all.isEmpty()) return emptyList()
        val byThread = all.groupBy { it.chat to it.threadUuid!! }
        val items = mutableListOf<ThreadListItem>()
        for ((key, replies) in byThread) {
            val (chat, rootUuid) = key
            val conversation = conversations.getValue(chat)
            if (conversation.isTribe && replies.size < 2) continue
            val root = readMessages("chat = ? AND uuid = ?", arrayOf(chat, rootUuid), "id ASC").firstOrNull() ?: continue
            if (replies.none { it.fromMe } && !root.fromMe) continue
            val newestFirst = replies.sortedWith(compareByDescending<MessageRow> { it.timestamp ?: 0L }.thenByDescending { it.id })
            val lastRead = readChatState(helper.readableDatabase, chat)?.lastRead ?: -1L
            val participants = mutableListOf<MessageRow>()
            val seen = mutableSetOf<String>()
            for (row in newestFirst) {
                val who = if (row.fromMe) "me" else (aliasOf(row.sender) ?: row.sender ?: "")
                if (seen.add(who)) {
                    participants.add(row)
                    if (participants.size == 3) break
                }
            }
            items.add(
                ThreadListItem(
                    conversation = conversation,
                    root = root,
                    count = replies.size,
                    lastTimestamp = replies.maxOf { it.timestamp ?: 0L },
                    unread = replies.count { !it.fromMe && (it.serverIndex ?: Long.MIN_VALUE) > lastRead }.toLong(),
                    latest = newestFirst.first(),
                    participants = participants,
                ),
            )
        }
        return items
            .sortedWith(compareByDescending<ThreadListItem> { it.lastTimestamp }.thenByDescending { it.root.id })
            .take(200)
    }

    /// Threads with something new — the dashboard Threads row's badge
    /// (`AppModel.unreadThreads`).
    fun unreadThreadCount(): Int = threads().count { it.unread > 0 }

    private fun readContact(db: SQLiteDatabase, pubkey: String): ContactRow? {
        db.query(
            "contact", arrayOf("pubkey", "alias", "photoUrl", "isTribe", "ready", "pricePerMessageMsat", "escrowMsat"),
            "pubkey = ?", arrayOf(pubkey), null, null, null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return ContactRow(
                pubkey = cursor.getString(0),
                alias = cursor.getString(1),
                photoUrl = cursor.getString(2),
                isTribe = cursor.getInt(3) != 0,
                ready = cursor.getInt(4) != 0,
                pricePerMessageMsat = cursor.getLong(5),
                escrowMsat = cursor.getLong(6),
            )
        }
    }

    // MARK: - Money (invoices and the server's payment history)

    /// Writes an invoice record, refusing to replace a record that is not
    /// failed with one that would pay again: a second send for a hash that is
    /// pending or paid is turned down (returns false), so the same invoice
    /// can never be paid twice from here — PeelCore's `Storage.apply`
    /// invoice guard. An incoming (receive) record is replaced freely only
    /// while still open.
    fun saveInvoice(record: InvoiceRecord): Boolean {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val existing = readInvoice(db, record.paymentHash, record.incoming)
            if (existing != null && existing.status != InvoiceRecord.Status.FAILED && existing.status != InvoiceRecord.Status.OPEN) {
                return false
            }
            val values = ContentValues().apply {
                put("paymentHash", record.paymentHash)
                put("incoming", if (record.incoming) 1 else 0)
                put("bolt11", record.bolt11)
                put("amountMsat", record.amountMsat)
                put("memo", record.memo)
                put("payee", record.payee)
                put("createdAt", record.createdAt)
                put("expiresAt", record.expiresAt)
                put("status", record.status.raw)
                put("paidAt", record.paidAt)
                put("paidMsat", record.paidMsat)
                put("tag", record.tag)
                put("chat", record.chat)
                put("error", record.error)
            }
            db.insertWithOnConflict("invoice", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    fun invoice(paymentHash: String, incoming: Boolean): InvoiceRecord? =
        readInvoice(helper.readableDatabase, paymentHash, incoming)

    /// Newest first.
    fun invoices(): List<InvoiceRecord> {
        helper.readableDatabase.rawQuery("SELECT * FROM invoice ORDER BY createdAt DESC", null).use { c ->
            val out = mutableListOf<InvoiceRecord>()
            while (c.moveToNext()) out.add(invoiceFrom(c))
            return out
        }
    }

    private fun readInvoice(db: SQLiteDatabase, hash: String, incoming: Boolean): InvoiceRecord? =
        db.rawQuery("SELECT * FROM invoice WHERE paymentHash = ? AND incoming = ?", arrayOf(hash, if (incoming) "1" else "0")).use { c ->
            if (c.moveToFirst()) invoiceFrom(c) else null
        }

    private fun invoiceFrom(c: android.database.Cursor): InvoiceRecord = InvoiceRecord(
        paymentHash = c.getString(c.getColumnIndexOrThrow("paymentHash")),
        incoming = c.getInt(c.getColumnIndexOrThrow("incoming")) != 0,
        bolt11 = c.getString(c.getColumnIndexOrThrow("bolt11")),
        amountMsat = c.getLong(c.getColumnIndexOrThrow("amountMsat")),
        memo = c.getString(c.getColumnIndexOrThrow("memo")),
        payee = c.getString(c.getColumnIndexOrThrow("payee")),
        createdAt = c.getLong(c.getColumnIndexOrThrow("createdAt")),
        expiresAt = c.getColumnIndexOrThrow("expiresAt").let { if (c.isNull(it)) null else c.getLong(it) },
        status = InvoiceRecord.Status.of(c.getString(c.getColumnIndexOrThrow("status"))),
        paidAt = c.getColumnIndexOrThrow("paidAt").let { if (c.isNull(it)) null else c.getLong(it) },
        paidMsat = c.getColumnIndexOrThrow("paidMsat").let { if (c.isNull(it)) null else c.getLong(it) },
        tag = c.getColumnIndexOrThrow("tag").let { if (c.isNull(it)) null else c.getString(it) },
        chat = c.getColumnIndexOrThrow("chat").let { if (c.isNull(it)) null else c.getString(it) },
        error = c.getColumnIndexOrThrow("error").let { if (c.isNull(it)) null else c.getString(it) },
    )

    /// An invoice of ours that the server says was paid (a history row, a
    /// type-3 message): settled, never moved backwards. For one we made, an
    /// amount under what it asked is UNDERPAID, as PeelCore settles it.
    fun settleInvoice(paymentHash: String, incoming: Boolean, paidMsat: Long?, atMs: Long) {
        val record = invoice(paymentHash, incoming) ?: return
        if (record.status == InvoiceRecord.Status.PAID || record.status == InvoiceRecord.Status.UNDERPAID) return
        val under = incoming && paidMsat != null && paidMsat < record.amountMsat
        helper.writableDatabase.execSQL(
            "UPDATE invoice SET status = ?, paidAt = ?, paidMsat = ?, error = NULL WHERE paymentHash = ? AND incoming = ?",
            arrayOf<Any?>(
                (if (under) InvoiceRecord.Status.UNDERPAID else InvoiceRecord.Status.PAID).raw,
                atMs, paidMsat, paymentHash, if (incoming) 1 else 0,
            ),
        )
    }

    /// The mixer's word on a payment's tag: COMPLETE settles it, FAILED
    /// frees it to be tried again. Only a pending record moves.
    fun markInvoiceSendStatus(tag: String, delivered: Boolean, error: String? = null) {
        helper.writableDatabase.execSQL(
            "UPDATE invoice SET status = ?, paidAt = ?, error = ? WHERE tag = ? AND incoming = 0 AND status = 'PENDING'",
            arrayOf<Any?>(
                if (delivered) InvoiceRecord.Status.PAID.raw else InvoiceRecord.Status.FAILED.raw,
                if (delivered) System.currentTimeMillis() else null,
                if (delivered) null else (error ?: "failed"),
                tag,
            ),
        )
    }

    /// A send of ours the server's history shows failed: free to be paid again.
    fun failPendingInvoice(paymentHash: String, error: String?) {
        helper.writableDatabase.execSQL(
            "UPDATE invoice SET status = 'FAILED', error = ? WHERE paymentHash = ? AND incoming = 0 AND status = 'PENDING'",
            arrayOf<Any?>(error ?: "failed", paymentHash),
        )
    }

    /// A page of the server's history, stored (a row seen again is replaced:
    /// its error may have been filled in since).
    fun insertPayments(rows: List<PaymentRecord>) {
        if (rows.isEmpty()) return
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            for (row in rows) {
                val values = ContentValues().apply {
                    put("rhash", row.rhash)
                    put("ts", row.ts)
                    put("amountMsat", row.amountMsat)
                    put("incoming", if (row.incoming) 1 else 0)
                    put("msgIndex", row.msgIndex)
                    put("remote", if (row.remote) 1 else 0)
                    put("error", row.error)
                }
                db.insertWithOnConflict("payment", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /// Stored history, newest first, at most `limit` rows.
    fun payments(limit: Int): List<PaymentRecord> =
        helper.readableDatabase.rawQuery("SELECT * FROM payment ORDER BY ts DESC LIMIT ?", arrayOf(limit.toString())).use { c ->
            val out = mutableListOf<PaymentRecord>()
            while (c.moveToNext()) {
                out.add(
                    PaymentRecord(
                        rhash = c.getString(c.getColumnIndexOrThrow("rhash")),
                        ts = c.getLong(c.getColumnIndexOrThrow("ts")),
                        amountMsat = c.getLong(c.getColumnIndexOrThrow("amountMsat")),
                        incoming = c.getInt(c.getColumnIndexOrThrow("incoming")) != 0,
                        msgIndex = c.getColumnIndexOrThrow("msgIndex").let { if (c.isNull(it)) null else c.getLong(it) },
                        remote = c.getInt(c.getColumnIndexOrThrow("remote")) != 0,
                        error = c.getColumnIndexOrThrow("error").let { if (c.isNull(it)) null else c.getString(it) },
                    ),
                )
            }
            out
        }

    fun oldestPaymentTimestamp(): Long? = scalarLong("SELECT MIN(ts) FROM payment")

    fun newestPaymentTimestamp(): Long? = scalarLong("SELECT MAX(ts) FROM payment")

    private fun scalarLong(sql: String): Long? =
        helper.readableDatabase.rawQuery(sql, null).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }

    /// The message with this payment hash, if stored: how a history row finds
    /// its chat, note and counterpart.
    fun messageByPaymentHash(hash: String): MessageRow? =
        readMessages("paymentHash = ?", arrayOf(hash), "id ASC").firstOrNull()

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
        // WAL: a commit appends to the log instead of syncing a rollback
        // journal and the main file, which every restore page (and each of
        // its commits) used to pay. `deleteDatabase` removes -wal/-shm too.
        override fun onConfigure(db: SQLiteDatabase) {
            db.enableWriteAheadLogging()
        }

        override fun onCreate(db: SQLiteDatabase) {
            v1(db)
            v2(db)
            v3(db)
            v4(db)
            v5(db)
            v6(db)
            v7(db)
            v8(db)
            v9(db)
            v10(db)
        }

        // Append only, same rule PeelCore's migrator follows: a new version
        // adds its own step here and in onCreate (a fresh database runs
        // every step once; an existing one runs only what it's missing).
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) v2(db)
            if (oldVersion < 3) v3(db)
            if (oldVersion < 4) v4(db)
            if (oldVersion < 5) v5(db)
            if (oldVersion < 6) v6(db)
            if (oldVersion < 7) v7(db)
            if (oldVersion < 8) v8(db)
            if (oldVersion < 9) v9(db)
            if (oldVersion < 10) v10(db)
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

        // `status`: a sent message's own delivery state (PeelCore's
        // `Message.Delivery`, trimmed to PENDING/DELIVERED/FAILED — no
        // UNSETTLED here, which is specifically about a payment the
        // mixer handed off but hasn't confirmed, not relevant to a free
        // text send). Always null for anything not `fromMe`: nothing but
        // our own sends ever has one.
        private fun v4(db: SQLiteDatabase) {
            db.execSQL("ALTER TABLE message ADD COLUMN status TEXT")
        }

        // `chatState`: each chat's own read marker and mute level — shared
        // across every device of the account on the server
        // (`Sphinxrs.getReads`/`getMutes`/`read`/`mute`), not something
        // this device decides alone. `readPending`: this device moved
        // `lastRead` while it had no connection to publish it over; kept
        // separate from `lastRead` itself so a reconnect's `getReads`
        // answer (the server's own truth) never undoes a marker that
        // simply hasn't reached the server yet (`Engine.fetchReadsAndMutes`).
        private fun v5(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE chatState (
                    chat TEXT PRIMARY KEY NOT NULL,
                    lastRead INTEGER,
                    readPending INTEGER NOT NULL DEFAULT 0,
                    muteLevel INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
        }

        // `message.replyUuid`/`threadUuid`: a reply's or boost's own
        // target, and a reply's own thread, as real columns — both were
        // only ever inside a message's raw JSON `body` before, re-parsed
        // in Kotlin everywhere a reply, thread or boost needed to know
        // it (`ConversationScreen.kt`'s own `replyUuid`/`threadUuid`
        // functions, now reading the column instead). Lets a boost's
        // unread check (`Storage.conversations()`) be one correlated SQL
        // subquery, the way PeelCore's own typed `Message.replyUUID`
        // always let `countsAsUnreadSQL` be, instead of a separate
        // Kotlin pass over every unread boost.
        //
        // Backfills every row already stored under v2-v5: the column
        // didn't exist for them to have been written into yet, so
        // without this an account that restored before this version
        // would have every old reply/thread/boost silently stop being
        // found by uuid the moment this port started reading the column
        // instead of the body.
        private fun v6(db: SQLiteDatabase) {
            db.execSQL("ALTER TABLE message ADD COLUMN replyUuid TEXT")
            db.execSQL("ALTER TABLE message ADD COLUMN threadUuid TEXT")
            db.execSQL("CREATE INDEX message_replyUuid ON message (replyUuid)")
            db.query("message", arrayOf("id", "body"), "body IS NOT NULL", null, null, null, null).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val body = cursor.getString(1)
                    val replyUuid = extractReplyUuid(body)
                    val threadUuid = extractThreadUuid(body)
                    if (replyUuid != null || threadUuid != null) {
                        db.execSQL(
                            "UPDATE message SET replyUuid = ?, threadUuid = ? WHERE id = ?",
                            arrayOf<Any?>(replyUuid, threadUuid, id),
                        )
                    }
                }
            }
        }

        // `contact.pricePerMessageMsat`/`escrowMsat`: a tribe's own price,
        // from its registry (`TribeInfo`, `Engine.refreshTribeNames`) —
        // the same `GET /tribes/<pubkey>` call already fetched for a
        // tribe's name and photo, now also kept for `Engine.sendMessage`/
        // `sendImageAttachment`/`sendBoost` to charge the right amount.
        // Always 0 for a direct contact, and for a tribe whose price was
        // never fetched. PeelCore's own `Tribe`/`pricePerMessageMsat`/
        // `escrowMsat` live in a whole separate `tribe` table (price,
        // escrow, membership, roles); two columns on `contact` cover the
        // one thing this port reads from it so far, the same trimming
        // `v3`'s own `isTribe` already did for the same table.
        private fun v7(db: SQLiteDatabase) {
            db.execSQL("ALTER TABLE contact ADD COLUMN pricePerMessageMsat INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE contact ADD COLUMN escrowMsat INTEGER NOT NULL DEFAULT 0")
        }

        // `message.deleted`: set only by `insertMessages`'s own
        // apply-on-receipt step (`applyDeletes`), never by a caller
        // building a row by hand — PeelCore's own `Message.deleted`,
        // trimmed to the one column this port's simpler, no-separate-
        // content-columns `message` table needs (PeelCore's own
        // `applyDelete` also clears `content`/`mediaType`/`fileName`/
        // `fileSize`/`mentionsMe`; this port's equivalent is just
        // clearing `body`, the one column any of those would have come
        // from). No backfill needed: a fresh column defaulting to 0 is
        // already correct for every row stored before this version —
        // none of them could have been deleted by a mechanism that did
        // not exist yet.
        private fun v8(db: SQLiteDatabase) {
            db.execSQL("ALTER TABLE message ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
        }

        // `invoice` and `payment`, ported from PeelCore's Storage+Migrations
        // (the columns Android's Payments feature reads): an invoice made
        // here or paid here, and the server's payment history (fetch_payments).
        private fun v9(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE invoice (
                    paymentHash TEXT NOT NULL,
                    incoming INTEGER NOT NULL,
                    bolt11 TEXT NOT NULL,
                    amountMsat INTEGER NOT NULL,
                    memo TEXT NOT NULL,
                    payee TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    expiresAt INTEGER,
                    status TEXT NOT NULL,
                    paidAt INTEGER,
                    paidMsat INTEGER,
                    tag TEXT,
                    chat TEXT,
                    error TEXT,
                    PRIMARY KEY (paymentHash, incoming)
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX invoice_tag ON invoice(tag)")
            db.execSQL(
                """
                CREATE TABLE payment (
                    rhash TEXT PRIMARY KEY NOT NULL,
                    ts INTEGER NOT NULL,
                    amountMsat INTEGER NOT NULL,
                    incoming INTEGER NOT NULL,
                    msgIndex INTEGER,
                    remote INTEGER NOT NULL,
                    error TEXT
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX payment_ts ON payment(ts)")
        }

        // `invite`: invites made here with sats (PeelCore's v16). The core keeps no
        // record of them and the server only the code; `tag` is the funding
        // payment's, which the server confirms; `status` as `InviteRecord.Status`.
        private fun v10(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE invite (
                    code TEXT PRIMARY KEY NOT NULL,
                    link TEXT NOT NULL,
                    amountMsat INTEGER NOT NULL,
                    nickname TEXT,
                    message TEXT,
                    tag TEXT,
                    status TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    usedBy TEXT
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX invite_tag ON invite(tag) WHERE tag IS NOT NULL")
        }

        companion object {
            const val VERSION = 10
        }
    }
}
