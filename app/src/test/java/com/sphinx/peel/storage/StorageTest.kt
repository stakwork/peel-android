package com.sphinx.peel.storage

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StorageTest {
    private fun newStorage() = Storage(ApplicationProvider.getApplicationContext(), account = "test-${System.nanoTime()}")

    @Test
    fun `coreState round trips through a commit`() {
        val storage = newStorage()
        storage.apply(Storage.Commit(state = mapOf("my_scid" to byteArrayOf(1, 2, 3))))

        assertEquals(byteArrayOf(1, 2, 3).toList(), storage.loadCoreState()["my_scid"]?.toList())
    }

    @Test
    fun `a later commit overwrites the same key`() {
        val storage = newStorage()
        storage.apply(Storage.Commit(state = mapOf("k" to byteArrayOf(1))))
        storage.apply(Storage.Commit(state = mapOf("k" to byteArrayOf(2))))

        assertEquals(byteArrayOf(2).toList(), storage.loadCoreState()["k"]?.toList())
    }

    @Test
    fun `deleteState removes the key`() {
        val storage = newStorage()
        storage.apply(Storage.Commit(state = mapOf("gone" to byteArrayOf(9))))
        storage.apply(Storage.Commit(deleteState = listOf("gone")))

        assertNull(storage.loadCoreState()["gone"])
    }

    @Test
    fun `setting round trips, unset is null`() {
        val storage = newStorage()
        assertNull(storage.value(Setting.ALIAS))

        storage.apply(Storage.Commit(settings = mapOf(Setting.ALIAS to "Tom")))
        assertEquals("Tom", storage.value(Setting.ALIAS))

        storage.apply(Storage.Commit(settings = mapOf(Setting.ALIAS to "Tomas")))
        assertEquals("Tomas", storage.value(Setting.ALIAS))
    }

    @Test
    fun `a single commit applies state, deletes and settings together`() {
        val storage = newStorage()
        storage.apply(Storage.Commit(state = mapOf("a" to byteArrayOf(1), "b" to byteArrayOf(2))))

        storage.apply(
            Storage.Commit(
                state = mapOf("c" to byteArrayOf(3)),
                deleteState = listOf("a"),
                settings = mapOf(Setting.WORDS_CONFIRMED to "1"),
            ),
        )

        val state = storage.loadCoreState()
        assertNull(state["a"])
        assertEquals(byteArrayOf(2).toList(), state["b"]?.toList())
        assertEquals(byteArrayOf(3).toList(), state["c"]?.toList())
        assertEquals("1", storage.value(Setting.WORDS_CONFIRMED))
    }

    @Test
    fun `deleteAccount wipes state, and the same instance starts clean afterwards`() {
        // "Forget this account" (HomeScreen) relies on exactly this: the
        // next account made on this device must not inherit the previous
        // one's coreState through the same Storage instance.
        val storage = newStorage()
        storage.apply(
            Storage.Commit(state = mapOf("my_scid" to byteArrayOf(1)), settings = mapOf(Setting.ALIAS to "Tom")),
        )

        storage.deleteAccount()

        assertEquals(emptyMap<String, ByteArray>(), storage.loadCoreState())
        assertNull(storage.value(Setting.ALIAS))

        storage.apply(Storage.Commit(settings = mapOf(Setting.ALIAS to "Ann")))
        assertEquals("Ann", storage.value(Setting.ALIAS))
    }

    @Test
    fun `conversations lists only ready chats, newest message first`() {
        val storage = newStorage()
        storage.upsertContact("alice", "Alice", null, isTribe = false, ready = true)
        storage.upsertContact("bob", "Bob", null, isTribe = false, ready = false) // not ready: excluded
        storage.insertMessages(
            listOf(
                MessageRow(uuid = "1", serverIndex = 1, type = 0, chat = "alice", fromMe = false, sender = null, body = "hi", msat = null, timestamp = 100, tag = null, paymentHash = null),
                MessageRow(uuid = "2", serverIndex = 2, type = 0, chat = "alice", fromMe = true, sender = null, body = "hey", msat = null, timestamp = 200, tag = null, paymentHash = null),
            ),
        )

        val conversations = storage.conversations()

        assertEquals(1, conversations.size)
        assertEquals("alice", conversations[0].chat)
        assertEquals("hey", conversations[0].lastContent)
        assertEquals(200L, conversations[0].lastTimestamp)
        assertEquals(true, conversations[0].lastFromMe)
    }

    @Test
    fun `messages returns one chat's rows, oldest first, leaving other chats out`() {
        val storage = newStorage()
        storage.insertMessages(
            listOf(
                MessageRow(uuid = "1", serverIndex = 1, type = 0, chat = "alice", fromMe = false, sender = null, body = "hi", msat = null, timestamp = 200, tag = null, paymentHash = null),
                MessageRow(uuid = "2", serverIndex = 2, type = 0, chat = "alice", fromMe = true, sender = null, body = "hey", msat = null, timestamp = 100, tag = null, paymentHash = null),
                MessageRow(uuid = "3", serverIndex = 3, type = 0, chat = "bob", fromMe = false, sender = null, body = "yo", msat = null, timestamp = 150, tag = null, paymentHash = null),
            ),
        )

        val messages = storage.messages("alice")

        assertEquals(2, messages.size)
        assertEquals("hey", messages[0].body)
        assertEquals("hi", messages[1].body)
    }

    @Test
    fun `a sent message's own echo moves it from PENDING to DELIVERED`() {
        val storage = newStorage()
        storage.insertMessages(
            listOf(
                MessageRow(uuid = "sent-1", serverIndex = null, type = 0, chat = "alice", fromMe = true, sender = null, body = "hey", msat = null, timestamp = 100, tag = "tag-1", paymentHash = null, status = "PENDING"),
            ),
        )
        // The same uuid, now with a real serverIndex, the way the echo
        // arrives through the ordinary live/restore path.
        storage.insertMessages(
            listOf(
                MessageRow(uuid = "sent-1", serverIndex = 42, type = 0, chat = "alice", fromMe = true, sender = null, body = "hey", msat = null, timestamp = 100, tag = "tag-1", paymentHash = null),
            ),
        )

        val stored = storage.messages("alice").single()
        assertEquals("DELIVERED", stored.status)
        // The echo's own fields (serverIndex) never overwrite the original row.
        assertNull(stored.serverIndex)
    }

    @Test
    fun `markSendStatus moves a pending send to FAILED by its tag`() {
        val storage = newStorage()
        storage.insertMessages(
            listOf(
                MessageRow(uuid = "sent-1", serverIndex = null, type = 0, chat = "alice", fromMe = true, sender = null, body = "hey", msat = null, timestamp = 100, tag = "tag-1", paymentHash = null, status = "PENDING"),
            ),
        )

        storage.markSendStatus("tag-1", delivered = false)

        assertEquals("FAILED", storage.messages("alice").single().status)
    }

    @Test
    fun `markSendStatus never moves a message already DELIVERED or FAILED`() {
        val storage = newStorage()
        storage.insertMessages(
            listOf(
                MessageRow(uuid = "sent-1", serverIndex = null, type = 0, chat = "alice", fromMe = true, sender = null, body = "hey", msat = null, timestamp = 100, tag = "tag-1", paymentHash = null, status = "DELIVERED"),
            ),
        )

        storage.markSendStatus("tag-1", delivered = false)

        assertEquals("DELIVERED", storage.messages("alice").single().status)
    }

    @Test
    fun `a chat with no messages yet still shows, with nothing to preview`() {
        val storage = newStorage()
        storage.upsertContact("alice", "Alice", null, isTribe = false, ready = true)

        val conversations = storage.conversations()

        assertEquals(1, conversations.size)
        assertNull(conversations[0].lastContent)
        assertNull(conversations[0].lastTimestamp)
    }

    @Test
    fun `upsertContact never overwrites a known field with an unknown one`() {
        val storage = newStorage()
        storage.upsertContact("alice", "Alice", "https://example.com/a.jpg", isTribe = false, ready = true)
        storage.upsertContact("alice", null, null, isTribe = false)

        val conversations = storage.conversations()

        assertEquals("Alice", conversations[0].alias)
        assertEquals("https://example.com/a.jpg", conversations[0].photoUrl)
        assertEquals(true, conversations[0].isTribe == false) // unchanged, still a direct contact
    }

    @Test
    fun `a tribe's name and photo never come from a message, even one with no host`() {
        // A real restore has tribe messages that don't carry the `host`
        // field (delivery/control messages) — a later one of those must
        // not attribute its own `pubkey == chat` match's alias/photo to
        // the tribe, the way it would for a genuine direct contact.
        val storage = newStorage()
        storage.upsertContact("tribe1", alias = null, photoUrl = null, isTribe = true, ready = true)
        storage.upsertContact("tribe1", alias = "Paul", photoUrl = "https://example.com/paul.jpg", isTribe = false)

        val conversations = storage.conversations()

        assertNull(conversations[0].alias)
        assertNull(conversations[0].photoUrl)
        assertEquals(true, conversations[0].isTribe)
    }

    @Test
    fun `updateTribeInfo fills in a known tribe's real name and photo`() {
        val storage = newStorage()
        storage.upsertContact("tribe1", alias = null, photoUrl = null, isTribe = true, ready = true)

        storage.updateTribeInfo("tribe1", "The Watercooler", "https://example.com/tribe.jpg")

        val conversations = storage.conversations()
        assertEquals("The Watercooler", conversations[0].alias)
        assertEquals("https://example.com/tribe.jpg", conversations[0].photoUrl)
    }

    @Test
    fun `updateTribeInfo stores a tribe's own price and escrow`() {
        val storage = newStorage()
        storage.upsertContact("tribe1", alias = null, photoUrl = null, isTribe = true, ready = true)

        storage.updateTribeInfo("tribe1", "The Watercooler", null, pricePerMessageMsat = 2_000, escrowMsat = 500)

        val contact = storage.contact("tribe1")
        assertEquals(2_000L, contact?.pricePerMessageMsat)
        assertEquals(500L, contact?.escrowMsat)
    }

    @Test
    fun `a tribe's price survives an ordinary message arriving afterward`() {
        // CONFLICT_REPLACE rewrites the whole contact row - a later
        // upsertContact (any incoming message) must not silently wipe a
        // price updateTribeInfo already fetched back to 0.
        val storage = newStorage()
        storage.upsertContact("tribe1", alias = null, photoUrl = null, isTribe = true, ready = true)
        storage.updateTribeInfo("tribe1", "The Watercooler", null, pricePerMessageMsat = 2_000, escrowMsat = 500)

        storage.upsertContact("tribe1", alias = "Someone", photoUrl = null, isTribe = true)

        val contact = storage.contact("tribe1")
        assertEquals(2_000L, contact?.pricePerMessageMsat)
        assertEquals(500L, contact?.escrowMsat)
    }

    @Test
    fun `updateTribeInfo does nothing for a pubkey that isn't a known tribe`() {
        val storage = newStorage()
        storage.upsertContact("alice", "Alice", null, isTribe = false, ready = true)

        storage.updateTribeInfo("alice", "Not Alice", null)

        val conversations = storage.conversations()
        assertEquals("Alice", conversations[0].alias)
    }

    @Test
    fun `a tribe's stale name is wiped the moment a later message reveals it's a tribe`() {
        // The actual order a real restore hit: a `host`-less message for
        // the chat processed first (looking exactly like a direct
        // contact naming itself), only later followed by the message
        // that actually reveals it's a tribe.
        val storage = newStorage()
        storage.upsertContact("tribe1", alias = "Paul", photoUrl = "https://example.com/paul.jpg", isTribe = false, ready = true)
        storage.upsertContact("tribe1", alias = null, photoUrl = null, isTribe = true, ready = true)

        val conversations = storage.conversations()

        assertNull(conversations[0].alias)
        assertNull(conversations[0].photoUrl)
        assertEquals(true, conversations[0].isTribe)
    }

    @Test
    fun `ConversationRow falls back to the chat's own key when there is no alias`() {
        val row = ConversationRow(
            chat = "03abcdefabcdefabcdef", alias = null, photoUrl = null, isTribe = false,
            lastContent = null, lastTimestamp = null, lastFromMe = null,
        )
        assertEquals("03abcdefabcd", row.name)
    }

    @Test
    fun `conversations counts messages past the read marker as unread`() {
        val storage = newStorage()
        storage.upsertContact("alice", "Alice", null, isTribe = false, ready = true)
        storage.insertMessages(
            listOf(
                MessageRow(uuid = "1", serverIndex = 10, type = 0, chat = "alice", fromMe = false, sender = null, body = "hi", msat = null, timestamp = 100, tag = null, paymentHash = null),
                MessageRow(uuid = "2", serverIndex = 11, type = 0, chat = "alice", fromMe = false, sender = null, body = "there", msat = null, timestamp = 200, tag = null, paymentHash = null),
                // Our own messages never count, however new.
                MessageRow(uuid = "3", serverIndex = 12, type = 0, chat = "alice", fromMe = true, sender = null, body = "hey", msat = null, timestamp = 300, tag = null, paymentHash = null),
            ),
        )

        assertEquals(2L, storage.conversations().single().unread)

        storage.setRead("alice", index = 10, pending = false)
        assertEquals(1L, storage.conversations().single().unread)

        storage.setRead("alice", index = 12, pending = false)
        assertEquals(0L, storage.conversations().single().unread)
    }

    @Test
    fun `a boost on my own message counts as unread, a boost on someone else's doesn't`() {
        val storage = newStorage()
        storage.upsertContact("alice", "Alice", null, isTribe = true, ready = true)
        storage.insertMessages(
            listOf(
                // Mine, and someone else's, both already read.
                MessageRow(uuid = "mine", serverIndex = 1, type = 0, chat = "alice", fromMe = true, sender = null, body = "hey", msat = null, timestamp = 100, tag = null, paymentHash = null),
                MessageRow(uuid = "theirs", serverIndex = 2, type = 0, chat = "alice", fromMe = false, sender = null, body = "hi", msat = null, timestamp = 200, tag = null, paymentHash = null),
            ),
        )
        storage.setRead("alice", index = 2, pending = false)
        assertEquals(0L, storage.conversations().single().unread)

        // A boost on my own message: news to me.
        storage.insertMessages(
            listOf(
                MessageRow(
                    uuid = "boost-1", serverIndex = 3, type = 29, chat = "alice", fromMe = false, sender = null,
                    body = """{"replyUuid":"mine"}""", msat = 1000, timestamp = 300, tag = null, paymentHash = null,
                ),
            ),
        )
        assertEquals(1L, storage.conversations().single().unread)

        storage.setRead("alice", index = 3, pending = false)
        assertEquals(0L, storage.conversations().single().unread)

        // A boost on someone else's message: not news to me.
        storage.insertMessages(
            listOf(
                MessageRow(
                    uuid = "boost-2", serverIndex = 4, type = 29, chat = "alice", fromMe = false, sender = null,
                    body = """{"replyUuid":"theirs"}""", msat = 1000, timestamp = 400, tag = null, paymentHash = null,
                ),
            ),
        )
        assertEquals(0L, storage.conversations().single().unread)
    }

    @Test
    fun `insertMessages derives replyUuid and threadUuid columns from body`() {
        val storage = newStorage()
        storage.upsertContact("alice", "Alice", null, isTribe = false, ready = true)
        storage.insertMessages(
            listOf(
                MessageRow(
                    uuid = "1", serverIndex = 1, type = 0, chat = "alice", fromMe = false, sender = null,
                    body = """{"content":"hi","replyUuid":"root-1","threadUuid":"root-1"}""", msat = null,
                    timestamp = 100, tag = null, paymentHash = null,
                ),
                MessageRow(uuid = "root-1", serverIndex = 2, type = 0, chat = "alice", fromMe = true, sender = null, body = "hey", msat = null, timestamp = 50, tag = null, paymentHash = null),
            ),
        )

        val rows = storage.messages("alice").associateBy { it.uuid }
        assertEquals("root-1", rows["1"]?.replyUuid)
        assertEquals("root-1", rows["1"]?.threadUuid)
        assertNull(rows["root-1"]?.replyUuid)
        assertNull(rows["root-1"]?.threadUuid)
    }

    @Test
    fun `a muted chat's unread count never shows`() {
        val storage = newStorage()
        storage.upsertContact("alice", "Alice", null, isTribe = false, ready = true)
        storage.insertMessages(
            listOf(
                MessageRow(uuid = "1", serverIndex = 10, type = 0, chat = "alice", fromMe = false, sender = null, body = "hi", msat = null, timestamp = 100, tag = null, paymentHash = null),
            ),
        )

        val before = storage.conversations().single()
        assertEquals(1L, before.unread)
        assertEquals(1L, before.shownUnread)

        storage.applyServerMutes(mapOf("alice" to 1))

        val after = storage.conversations().single()
        assertEquals(1L, after.unread)
        assertEquals(0L, after.shownUnread)
    }

    @Test
    fun `setRead never moves a chat's marker backward`() {
        val storage = newStorage()
        assertTrue(storage.setRead("alice", index = 10, pending = false))
        assertFalse(storage.setRead("alice", index = 5, pending = false))
        assertEquals(mapOf<String, Long>(), storage.pendingReads())
    }

    @Test
    fun `setRead can clear a pending marker without moving it`() {
        val storage = newStorage()
        storage.setRead("alice", index = 10, pending = true)
        assertEquals(mapOf("alice" to 10L), storage.pendingReads())

        assertTrue(storage.setRead("alice", index = 10, pending = false))
        assertEquals(mapOf<String, Long>(), storage.pendingReads())
    }

    @Test
    fun `applyServerReads leaves a chat with a pending marker alone`() {
        val storage = newStorage()
        storage.setRead("alice", index = 20, pending = true)

        // The server's own answer is older than what this device already
        // moved to and hasn't sent yet - must not roll it back.
        storage.applyServerReads(mapOf("alice" to 5))

        assertEquals(mapOf("alice" to 20L), storage.pendingReads())
    }

    @Test
    fun `applyServerReads applies directly to a chat with nothing pending`() {
        val storage = newStorage()
        storage.upsertContact("alice", "Alice", null, isTribe = false, ready = true)
        storage.insertMessages(
            listOf(
                MessageRow(uuid = "1", serverIndex = 10, type = 0, chat = "alice", fromMe = false, sender = null, body = "hi", msat = null, timestamp = 100, tag = null, paymentHash = null),
            ),
        )

        storage.applyServerReads(mapOf("alice" to 10))

        assertEquals(0L, storage.conversations().single().unread)
        assertEquals(mapOf<String, Long>(), storage.pendingReads())
    }

    private fun msg(uuid: String, index: Long, type: Int, chat: String, fromMe: Boolean = false, sender: String? = null, replyUuid: String? = null) =
        MessageRow(uuid = uuid, serverIndex = index, type = type, chat = chat, fromMe = fromMe, sender = sender, body = "b", msat = null, timestamp = index, tag = null, paymentHash = null)

    @Test
    fun `unread ignores key exchange and tribe join or approve but counts normal messages`() {
        val storage = newStorage()
        storage.upsertContact("alice", "Alice", null, isTribe = false, ready = true)
        storage.insertMessages(
            listOf(
                msg("a", 1, 10, "alice"),
                msg("b", 2, 11, "alice"),
                msg("c", 3, 33, "alice"),
                msg("d", 4, 14, "alice"),
                msg("e", 5, 20, "alice"),
                msg("f", 6, 0, "alice"),
            ),
        )

        assertEquals(1L, storage.conversations().single().unread)
    }

    @Test
    fun `tribeHost reads host from a stored sender`() {
        val storage = newStorage()
        storage.insertMessages(
            listOf(
                msg("a", 1, 0, "tribe", sender = """{"pubkey":"tribe","alias":"x"}"""),
                msg("b", 2, 0, "tribe", sender = """{"pubkey":"tribe","host":"tribes.example.com"}"""),
            ),
        )

        assertEquals("tribes.example.com", storage.tribeHost("tribe"))
        assertNull(storage.tribeHost("other"))
    }

    @Test
    fun `newestMessageFromMe follows the highest server index`() {
        val storage = newStorage()
        storage.insertMessages(listOf(msg("a", 1, 0, "alice"), msg("b", 2, 0, "alice", fromMe = true)))
        assertTrue(storage.newestMessageFromMe("alice"))
        storage.insertMessages(listOf(msg("c", 3, 0, "alice")))
        assertFalse(storage.newestMessageFromMe("alice"))
    }
}
