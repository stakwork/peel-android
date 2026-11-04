package com.sphinx.peel.storage

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/// What a resumable restore leans on in storage: the cursor setting
/// surviving a reopen and being removed with the finishing commit, and a
/// page's messages and contacts landing together, idempotently.
@RunWith(RobolectricTestRunner::class)
class StorageRestoreTest {
    private val account = "restore-${System.nanoTime()}"
    private fun open() = Storage(ApplicationProvider.getApplicationContext(), account = account)

    private fun row(uuid: String, index: Long, chat: String = "alice") = MessageRow(
        uuid = uuid, serverIndex = index, type = 0, chat = chat, fromMe = false, sender = null, body = "hi",
        msat = null, timestamp = 100, tag = null, paymentHash = null,
    )

    @Test
    fun `the restore cursor survives a reopen and goes with the finishing commit`() {
        val first = open()
        first.apply(Storage.Commit(settings = mapOf(Setting.NEEDS_RESTORE to "1", Setting.RESTORE_CURSOR to "1|M|300||0|")))
        first.close()

        val reopened = open()
        assertEquals("1|M|300||0|", reopened.value(Setting.RESTORE_CURSOR))

        reopened.apply(
            Storage.Commit(
                settings = mapOf(Setting.NEEDS_RESTORE to "0"),
                deleteSettings = listOf(Setting.RESTORE_CURSOR),
            ),
        )
        assertNull(reopened.value(Setting.RESTORE_CURSOR))
        assertEquals("0", reopened.value(Setting.NEEDS_RESTORE))
    }

    @Test
    fun `messages and the contacts they reveal are stored together, and a repeated page changes nothing`() {
        val storage = open()
        val contacts = listOf(
            ContactRow("alice", "Alice", null, isTribe = false, ready = false),
            ContactRow("alice", "Alice B", null, isTribe = false, ready = false),
            ContactRow("tribe", null, null, isTribe = true, ready = true),
        )
        val page = listOf(row("m1", 5), row("m2", 6), row("m3", 7, chat = "tribe"))

        storage.insertMessages(page, contacts)
        storage.insertMessages(page, contacts) // a page asked for again after a crash

        assertEquals(2, storage.messages("alice").size)
        assertEquals(1, storage.messages("tribe").size)
        assertEquals("Alice B", storage.contact("alice")?.alias) // newest wins, in order
        assertTrue(storage.contact("tribe")!!.isTribe)
        assertEquals(7L, storage.maxServerIndex())
    }
}

/// The account's own profile, recovered from what it sent (peel's
/// `newestOwnAlias` / `newestOwnPhotoURL`).
@RunWith(RobolectricTestRunner::class)
class StorageOwnProfileTest {
    private fun open() = Storage(ApplicationProvider.getApplicationContext(), account = "own-${System.nanoTime()}")

    private fun sent(uuid: String, index: Long, chat: String, sender: String?, type: Int = 0) = MessageRow(
        uuid = uuid, serverIndex = index, type = type, chat = chat, fromMe = true, sender = sender, body = "x",
        msat = null, timestamp = 1, tag = null, paymentHash = null,
    )

    private fun who(alias: String? = null, photo: String? = null): String {
        val o = org.json.JSONObject()
        if (alias != null) o.put("alias", alias)
        if (photo != null) o.put("photo_url", photo)
        return o.toString()
    }

    private fun Storage.people(vararg keys: String) = keys.forEach { upsertContact(it, null, null, false, true) }

    @Test
    fun `alias comes from the newest own direct message and blank ones are skipped`() {
        val s = open()
        s.people("alice")
        s.insertMessages(listOf(
            sent("1", 1, "alice", who("Old")),
            sent("2", 2, "alice", who("  Me  ")),
            sent("3", 3, "alice", who("   ")),
            sent("4", 4, "alice", who()),
        ))
        assertEquals("Me", s.newestOwnAlias())
    }

    @Test
    fun `tribe only messages give no alias`() {
        val s = open()
        s.upsertContact("tribe", null, null, true, true)
        s.insertMessages(listOf(sent("1", 1, "tribe", who("Rewritten", "https://x/p.jpg"))))
        assertNull(s.newestOwnAlias())
    }

    @Test
    fun `photo is https only, empty on the newest means none, tribes are the fallback`() {
        val s = open()
        s.people("alice")
        s.insertMessages(listOf(
            sent("1", 1, "alice", who("Me", "https://x/old.jpg")),
            sent("2", 2, "alice", who("Me", "http://insecure/p.jpg")),
        ))
        assertNull(s.newestOwnPhotoUrl())

        val t = open()
        t.people("alice")
        t.insertMessages(listOf(
            sent("1", 1, "alice", who("Me", "https://x/old.jpg")),
            sent("2", 2, "alice", who("Me", "https://x/new.jpg")),
            sent("3", 3, "alice", who("Me", "https://x/boost.jpg"), type = 29),
        ))
        assertEquals("https://x/new.jpg", t.newestOwnPhotoUrl())
        t.insertMessages(listOf(sent("4", 4, "alice", who("Me", ""))))
        assertNull(t.newestOwnPhotoUrl())

        val u = open()
        u.upsertContact("tribe", null, null, true, true)
        u.insertMessages(listOf(sent("1", 1, "tribe", who("Me", "https://x/tribe.jpg"))))
        assertEquals("https://x/tribe.jpg", u.newestOwnPhotoUrl())
    }
}
