package com.sphinx.peel.storage

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
