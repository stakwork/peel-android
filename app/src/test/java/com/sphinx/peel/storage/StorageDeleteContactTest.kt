package com.sphinx.peel.storage

import androidx.test.core.app.ApplicationProvider
import com.sphinx.peel.engine.ContactRemoval
import com.sphinx.peel.engine.InvoiceRecord
import com.sphinx.peel.engine.PaymentRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/// Mirrors PeelCore's `ContactDeleteTests` (storage half): removing a contact takes their chat and
/// nothing else; `hasPendingPayment` and `serverIndexes` read what the removal decides on.
@RunWith(RobolectricTestRunner::class)
class StorageDeleteContactTest {
    private val alice = "02aa"
    private val bob = "02bb"
    private val tribe = "02cc"

    private fun message(uuid: String, chat: String, type: Int = 0, fromMe: Boolean = false, index: Long? = null, status: String? = null, body: String = "hi") =
        MessageRow(
            uuid = uuid, serverIndex = index, type = type, chat = chat, fromMe = fromMe, sender = null, body = body, msat = null,
            timestamp = 1, tag = if (fromMe) "t-$uuid" else null, paymentHash = null, status = status,
        )

    private fun newStorage(paymentStatus: String = "DELIVERED"): Storage {
        val storage = Storage(ApplicationProvider.getApplicationContext(), account = "del-${System.nanoTime()}")
        storage.upsertContact(alice, "Alice", null, isTribe = false, ready = true)
        storage.upsertContact(bob, "Bob", null, isTribe = false, ready = true)
        storage.upsertContact(tribe, "Tribe", null, isTribe = true, ready = true)
        storage.insertMessages(
            listOf(
                message("m1", alice, index = 1),
                message("m3", alice, type = 6, index = 3, body = """{"mediaToken":"x"}"""),
                message("m4", alice, type = 5, fromMe = true, status = paymentStatus),
                message("m5", bob),
                message("m6", tribe),
            ),
        )
        storage.apply(
            Storage.Commit(
                state = mapOf("c/$alice" to byteArrayOf(1), "c/$bob" to byteArrayOf(2), "c/$tribe" to byteArrayOf(3)),
            ),
        )
        storage.setMute(alice, 2)
        storage.setMute(bob, 1)
        storage.setMute(tribe, 1)
        storage.setChatTimeZone(alice, Storage.ChatTimeZone(false, null))
        storage.saveInvoice(
            InvoiceRecord(
                paymentHash = "h1", incoming = false, bolt11 = "lnbc1", amountMsat = 1000, memo = "", payee = alice, createdAt = 1,
                expiresAt = null, status = InvoiceRecord.Status.PAID, chat = alice,
            ),
        )
        storage.insertPayments(listOf(PaymentRecord("r1", 1, 1000, incoming = false, msgIndex = null, remote = false, error = null)))
        return storage
    }

    @Test
    fun `the chat goes and nothing else`() {
        val storage = newStorage()
        val removed = storage.deleteContact(alice)
        assertEquals(listOf("m3"), removed.map { it.uuid })
        assertNull(storage.contact(alice))
        assertTrue(storage.messages(alice).isEmpty())
        assertEquals(0, storage.muteLevel(alice))
        assertNull(storage.loadCoreState()["c/$alice"])
        // The others stay.
        assertNotNull(storage.contact(bob))
        assertEquals(1, storage.messages(bob).size)
        assertEquals(1, storage.messages(tribe).size)
        assertEquals(1, storage.muteLevel(tribe))
        assertNotNull(storage.loadCoreState()["c/$bob"])
        assertNotNull(storage.loadCoreState()["c/$tribe"])
        // The time zone switch is the account's preference; the money tables are history.
        assertEquals(false, storage.chatTimeZone(alice)?.enabled)
        assertNotNull(storage.invoice("h1", incoming = false))
        assertEquals(1, storage.payments(10).size)
        // The chat leaves the list.
        assertEquals(setOf(bob, tribe), storage.conversations().map { it.chat }.toSet())
    }

    @Test
    fun `a payment still on its way is pending, and only for its chat`() {
        val storage = newStorage(paymentStatus = "PENDING")
        assertTrue(storage.hasPendingPayment(alice))
        assertFalse(storage.hasPendingPayment(bob))
        assertEquals(1, storage.messages(alice).count { it.type == 5 })
    }

    @Test
    fun `a delivered payment is not pending`() {
        assertFalse(newStorage().hasPendingPayment(alice))
    }

    @Test
    fun `an invoice we are paying keeps the contact too`() {
        val storage = newStorage()
        storage.saveInvoice(
            InvoiceRecord(
                paymentHash = "h2", incoming = false, bolt11 = "lnbc2", amountMsat = 1000, memo = "", payee = bob, createdAt = 1,
                expiresAt = null, status = InvoiceRecord.Status.PENDING, tag = "t", chat = bob,
            ),
        )
        assertTrue(storage.hasPendingPayment(bob))
        assertFalse(storage.hasPendingPayment(alice))
    }

    @Test
    fun `server indexes are those of the asked types with an index`() {
        val storage = newStorage()
        storage.insertMessages(listOf(message("k1", alice, type = 10, index = 7), message("p1", alice, type = 5, index = 9), message("n1", alice, type = 5)))
        assertEquals(listOf(7L, 9L), storage.serverIndexes(alice, ContactRemoval.ACCOUNT_KEY_TYPES))
        // Text (type 0, index 1) is not an account-key type.
        assertEquals(listOf(1L), storage.serverIndexes(alice, listOf(0)))
        assertTrue(storage.serverIndexes(bob, ContactRemoval.ACCOUNT_KEY_TYPES).isEmpty())
    }
}
