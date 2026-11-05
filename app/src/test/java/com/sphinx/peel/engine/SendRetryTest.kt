package com.sphinx.peel.engine

import com.sphinx.peel.storage.MessageRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/// The pure rules of a resend (`SendRetry`), PeelCore's `Message.isRetryable`
/// and friends.
class SendRetryTest {
    private val now = 10_000_000L

    private fun send(
        status: String? = "PENDING", ageMs: Long = 0, type: Int = 0, msat: Long? = null, tag: String? = "t",
        firstSentAt: Long? = null, autoResentAt: Long? = null, fromMe: Boolean = true, deleted: Boolean = false,
    ) = MessageRow(
        uuid = "u", serverIndex = null, type = type, chat = "alice", fromMe = fromMe, sender = null, body = "hi",
        msat = msat, timestamp = now - ageMs, tag = tag, paymentHash = null, status = status, deleted = deleted,
        firstSentAt = firstSentAt, autoResentAt = autoResentAt,
    )

    @Test
    fun `a failed send is retryable at once`() {
        assertTrue(SendRetry.isRetryable(send("FAILED"), now))
    }

    @Test
    fun `a pending send is retryable only after the 30 second timeout`() {
        assertFalse(SendRetry.isRetryable(send(ageMs = 30_000), now))
        assertTrue(SendRetry.isRetryable(send(ageMs = 30_001), now))
    }

    @Test
    fun `delivered, unsettled and unknown statuses are never retryable`() {
        for (status in listOf("DELIVERED", "UNSETTLED", null)) {
            assertFalse(status.toString(), SendRetry.isRetryable(send(status, ageMs = 99_999), now))
        }
    }

    @Test
    fun `a pending send that may have paid a tribe's price waits, a failed one may go`() {
        assertFalse(SendRetry.isRetryable(send(ageMs = 99_999, msat = 3_000), now))
        assertTrue(SendRetry.isRetryable(send("FAILED", msat = 3_000), now))
    }

    @Test
    fun `payments, boosts, others' messages and deleted ones are not retryable`() {
        assertFalse(SendRetry.isRetryable(send("FAILED", type = DIRECT_PAYMENT_MESSAGE_TYPE), now))
        assertFalse(SendRetry.isRetryable(send("FAILED", type = BOOST_MESSAGE_TYPE), now))
        assertFalse(SendRetry.isRetryable(send("FAILED", fromMe = false), now))
        assertFalse(SendRetry.isRetryable(send("FAILED", deleted = true), now))
    }

    @Test
    fun `an attachment is resendable like text`() {
        assertTrue(SendRetry.isRetryable(send("FAILED", type = ATTACHMENT_MESSAGE_TYPE), now))
    }

    @Test
    fun `a pending send shows as sending until it turns retryable`() {
        assertTrue(SendRetry.isSending(send(ageMs = 1_000), now))
        assertFalse(SendRetry.isSending(send(ageMs = 60_000), now))
        assertFalse(SendRetry.isSending(send("FAILED"), now))
    }

    @Test
    fun `a failed call may be discarded but never retried`() {
        val call = send("FAILED", type = CALL_MESSAGE_TYPE)
        assertFalse(SendRetry.isRetryable(call, now))
        assertTrue(SendRetry.isDiscardable(call, now))
        assertFalse(SendRetry.isDiscardable(send("DELIVERED"), now))
        assertTrue(SendRetry.isDiscardable(send("FAILED"), now))
    }

    @Test
    fun `auto resend once within the hour with no sats`() {
        assertTrue(SendRetry.isAutoResendable(send("FAILED", ageMs = 59 * 60_000L), now))
        assertFalse(SendRetry.isAutoResendable(send("FAILED", ageMs = 61 * 60_000L), now))
        assertFalse(SendRetry.isAutoResendable(send("FAILED", autoResentAt = now - 1), now))
        assertFalse(SendRetry.isAutoResendable(send("FAILED", msat = 3_000), now))
        // The window counts from the first send, not the latest resend.
        assertFalse(SendRetry.isAutoResendable(send("FAILED", ageMs = 1_000, firstSentAt = now - 2 * 3_600_000L), now))
    }

    @Test
    fun `auto resend needs the server's records, and never sends what they name`() {
        val failed = send("FAILED", tag = "t1")
        val unanswered = SendRetry.Gate(asked = setOf("t1"))
        assertFalse(SendRetry.autoResends(failed, unanswered, now))
        assertFalse(SendRetry.autoResends(failed, null, now))

        val answeredEmpty = SendRetry.Gate(asked = setOf("t1")).also { it.settle() }
        assertTrue(SendRetry.autoResends(failed, answeredEmpty, now))

        val named = SendRetry.Gate(asked = setOf("t1")).also { it.name("t1"); it.settle() }
        assertFalse(SendRetry.autoResends(failed, named, now))

        // A tag nobody asked about is left alone.
        assertFalse(SendRetry.autoResends(send("FAILED", tag = "t2"), answeredEmpty, now))
    }

    @Test
    fun `auto resend of a pending send waits out the in-flight limit, a tagless failed one goes`() {
        val gate = SendRetry.Gate(asked = setOf("t")).also { it.settle() }
        assertFalse(SendRetry.autoResends(send(ageMs = 60_000), gate, now))
        assertTrue(SendRetry.autoResends(send(ageMs = 121_000), gate, now))
        assertTrue(SendRetry.autoResends(send("FAILED", tag = null), null, now))
        assertFalse(SendRetry.autoResends(send(ageMs = 99_999, tag = null), null, now))
    }

    @Test
    fun `mute levels and read toggle`() {
        assertEquals(listOf(MuteLevel.ALL, MuteLevel.MUTED), MuteLevel.choices(isTribe = false))
        assertEquals(listOf(MuteLevel.ALL, MuteLevel.MENTIONS_ONLY, MuteLevel.MUTED), MuteLevel.choices(isTribe = true))
        assertEquals(MuteLevel.ALL, MuteLevel.of(99))
        assertEquals(MuteLevel.MENTIONS_ONLY, MuteLevel.of(1))
    }
}
