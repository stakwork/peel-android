package com.sphinx.peel.storage

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.sphinx.peel.engine.InviteRecord
import com.sphinx.peel.engine.SendStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private typealias S = InviteRecord.Status

@RunWith(RobolectricTestRunner::class)
class StorageInviteTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun newStorage() = Storage(context, account = "invite-${System.nanoTime()}")

    private val code = "ab".repeat(32)

    private fun invite(status: InviteRecord.Status = S.SENDING, tag: String? = "tag1", created: Long = 1000) = InviteRecord(
        code = code, link = "sphinx.chat://?action=i&d=x", amountMsat = 50_000, nickname = "Bob", message = "hi",
        tag = tag, status = status, createdAt = created,
    )

    private fun keyExchange(from: String, senderCode: String?, fromMe: Boolean = false, uuid: String = "u1") = MessageRow(
        uuid = uuid, serverIndex = 1, type = 10, chat = from, fromMe = fromMe,
        sender = """{"pubkey":"$from","alias":"Carol"${senderCode?.let { ""","code":"$it"""" } ?: ""}}""",
        body = null, msat = null, timestamp = 10, tag = null, paymentHash = null,
    )

    @Test
    fun `an invite is stored and read back, newest first`() {
        val storage = newStorage()
        storage.insertInvite(invite())
        storage.insertInvite(invite(created = 2000).copy(code = "cd".repeat(32), tag = "tag2"))
        assertEquals(invite(), storage.invite(code))
        assertEquals(listOf(2000L, 1000L), storage.invites().map { it.createdAt })
        assertNull(storage.invite("00"))
    }

    @Test
    fun `its funding payment moves it from sending to ready, or to failed`() {
        val storage = newStorage()
        storage.insertInvite(invite())
        storage.markSendStatus("tag1", SendStatus.UNSETTLED)
        assertEquals(S.SENDING, storage.invite(code)?.status)
        storage.markSendStatus("tag1", SendStatus.DELIVERED)
        assertEquals(S.READY, storage.invite(code)?.status)
        // Once ready, a late failure moves nothing.
        storage.markSendStatus("tag1", SendStatus.FAILED)
        assertEquals(S.READY, storage.invite(code)?.status)

        val failing = newStorage()
        failing.insertInvite(invite())
        failing.markSendStatus("tag1", SendStatus.FAILED)
        assertEquals(S.FAILED, failing.invite(code)?.status)
        // The server had it after all.
        failing.markSendStatus("tag1", SendStatus.DELIVERED)
        assertEquals(S.READY, failing.invite(code)?.status)
    }

    @Test
    fun `a cancelled or used invite is never moved by its funding payment`() {
        val storage = newStorage()
        storage.insertInvite(invite(S.CANCELLED))
        storage.markSendStatus("tag1", SendStatus.DELIVERED)
        assertEquals(S.CANCELLED, storage.invite(code)?.status)
        storage.setInviteStatus(code, S.USED, listOf(S.CANCELLED))
        storage.markSendStatus("tag1", SendStatus.FAILED)
        assertEquals(S.USED, storage.invite(code)?.status)
    }

    @Test
    fun `status moves only from the states named`() {
        val storage = newStorage()
        storage.insertInvite(invite(S.READY))
        assertFalse(storage.setInviteStatus(code, S.CANCELLED, listOf(S.CANCELLING)))
        assertTrue(storage.setInviteStatus(code, S.CANCELLING, listOf(S.READY, S.CANCELLING)))
        assertTrue(storage.setInviteStatus(code, S.CANCELLED, listOf(S.CANCELLING)))
        assertEquals(S.CANCELLED, storage.invite(code)?.status)
    }

    @Test
    fun `a contact key naming the code marks the invite used, by that contact`() {
        val storage = newStorage()
        storage.insertInvite(invite(S.READY))
        storage.insertMessages(listOf(keyExchange("02carol", code.uppercase())))
        val used = storage.invite(code)
        assertEquals(S.USED, used?.status)
        assertEquals("02carol", used?.usedBy)
    }

    @Test
    fun `a contact key without a code, our own, or for a spent invite changes nothing`() {
        val storage = newStorage()
        storage.insertInvite(invite(S.READY))
        storage.insertMessages(listOf(keyExchange("02carol", null, uuid = "a")))
        storage.insertMessages(listOf(keyExchange("02carol", code, fromMe = true, uuid = "b")))
        assertEquals(S.READY, storage.invite(code)?.status)

        val cancelled = newStorage()
        cancelled.insertInvite(invite(S.CANCELLED))
        cancelled.insertMessages(listOf(keyExchange("02carol", code)))
        assertEquals(S.CANCELLED, cancelled.invite(code)?.status)
    }

    @Test
    fun `an invite still sending is asked about on connect`() {
        val storage = newStorage()
        storage.insertInvite(invite(S.SENDING))
        assertTrue("tag1" in storage.pendingSendTags(0))
        storage.setInviteStatus(code, S.READY, listOf(S.SENDING))
        assertFalse("tag1" in storage.pendingSendTags(0))
    }

    @Test
    fun `a database from before invites gains the table, keeping what it had`() {
        val name = "invite-upgrade"
        context.deleteDatabase("peel-$name.db")
        // The schema as of version 9, as far as an upgrade needs it: one stored setting.
        val old = context.openOrCreateDatabase("peel-$name.db", android.content.Context.MODE_PRIVATE, null)
        old.execSQL("CREATE TABLE setting (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
        old.insert("setting", null, ContentValues().apply { put("key", "alias"); put("value", "Old") })
        old.version = 9
        old.close()
        val storage = Storage(context, account = name)
        assertEquals("Old", storage.value(Setting.ALIAS))
        assertTrue(storage.invites().isEmpty())
        storage.insertInvite(invite())
        assertEquals(1, storage.invites().size)
        assertTrue(SQLiteDatabase::class.java.name.isNotEmpty())
    }
}
