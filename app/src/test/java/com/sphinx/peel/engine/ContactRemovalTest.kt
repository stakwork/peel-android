package com.sphinx.peel.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/// Mirrors the engine half of PeelCore's `ContactDeleteTests` with the core, connection and storage
/// as fakes: `Engine` itself needs the native library (docs/phases/01-foundation.md). Robolectric because
/// `hasContactKey` reads JSON with `org.json`, which is only a stub on a plain JVM (as in `TribeFormTest`).
@RunWith(RobolectricTestRunner::class)
class ContactRemovalTest {
    private class Run {
        val log = mutableListOf<String>()
        var forgotten = false

        suspend fun delete(
            isContact: Boolean = true,
            pending: Boolean = false,
            connected: Boolean = true,
            contactJson: String? = """{"pubkey":"02aa","contact_key":"03dd","my_idx":1}""",
            indexes: List<Long> = listOf(3L, 5L),
        ) = ContactRemoval.delete(
            pubkey = "02aa",
            isContact = isContact,
            hasPendingPayment = { pending },
            connected = connected,
            contactJson = { contactJson },
            serverIndexes = { indexes },
            runCore = { who, idx -> log += "deleteMsgs($who, $idx)" },
            forget = { forgotten = true; log += "forget" },
        )
    }

    private fun refusal(run: Run, block: suspend Run.() -> Unit): Exception =
        try {
            runBlocking { run.block() }
            throw AssertionError("expected a refusal")
        } catch (e: Exception) {
            e
        }

    @Test
    fun `the server copy goes first, then everything on their key, then the local forget`() {
        val run = Run()
        runBlocking { run.delete() }
        assertEquals(listOf("deleteMsgs(null, [3, 5])", "deleteMsgs(02aa, null)", "forget"), run.log)
    }

    @Test
    fun `without a completed key exchange only the child key is deleted`() {
        val run = Run()
        runBlocking { run.delete(contactJson = """{"pubkey":"02aa","my_idx":1}""") }
        assertEquals(listOf("deleteMsgs(02aa, null)", "forget"), run.log)
    }

    @Test
    fun `with nothing indexed only the child key is deleted`() {
        val run = Run()
        runBlocking { run.delete(indexes = emptyList()) }
        assertEquals(listOf("deleteMsgs(02aa, null)", "forget"), run.log)
    }

    @Test
    fun `a tribe or a stranger is not a contact`() {
        val run = Run()
        val e = refusal(run) { delete(isContact = false) }
        assertEquals(ContactRemovalRefused.Reason.NOT_A_CONTACT, (e as ContactRemovalRefused).reason)
        assertTrue(run.log.isEmpty())
    }

    @Test
    fun `offline nothing is removed`() {
        val run = Run()
        val e = refusal(run) { delete(connected = false) }
        assertTrue(e is NotConnectedException)
        assertTrue(run.log.isEmpty())
        assertFalse(run.forgotten)
    }

    @Test
    fun `sats still on their way keep the contact`() {
        val run = Run()
        val e = refusal(run) { delete(pending = true) }
        assertEquals(ContactRemovalRefused.Reason.PAYMENT_PENDING, (e as ContactRemovalRefused).reason)
        assertTrue(run.log.isEmpty())
    }

    @Test
    fun `a contact is checked before a pending payment, a pending payment before the connection`() {
        val run = Run()
        assertEquals(
            ContactRemovalRefused.Reason.NOT_A_CONTACT,
            (refusal(run) { delete(isContact = false, pending = true, connected = false) } as ContactRemovalRefused).reason,
        )
        assertEquals(
            ContactRemovalRefused.Reason.PAYMENT_PENDING,
            (refusal(run) { delete(pending = true, connected = false) } as ContactRemovalRefused).reason,
        )
    }

    @Test
    fun `a failed core call forgets nothing`() {
        var forgot = false
        try {
            runBlocking {
                ContactRemoval.delete("02aa", true, { false }, true, { null }, { emptyList() }, { _, _ -> error("boom") }, { forgot = true })
            }
        } catch (_: IllegalStateException) {
        }
        assertFalse(forgot)
    }

    @Test
    fun `contact key reading`() {
        assertTrue(ContactRemoval.hasContactKey("""{"contact_key":"03dd"}"""))
        assertFalse(ContactRemoval.hasContactKey("""{"contact_key":""}"""))
        assertFalse(ContactRemoval.hasContactKey("""{"contact_key":null}"""))
        assertFalse(ContactRemoval.hasContactKey("not json"))
    }

    @Test
    fun `the account-key types are PeelCore's list`() {
        assertEquals(setOf(3, 5, 7, 29, 10, 11), ContactRemoval.ACCOUNT_KEY_TYPES.toSet())
    }
}
