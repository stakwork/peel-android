package com.sphinx.peel.ui.dashboard

import com.sphinx.peel.engine.BOOST_MESSAGE_TYPE
import com.sphinx.peel.storage.MessageRow
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/// `boostSummaries`' distinct sender list, for the boost chip's avatars.
@RunWith(RobolectricTestRunner::class)
class BoostSummariesTest {
    private fun boost(uuid: String, target: String, alias: String?, fromMe: Boolean = false) = MessageRow(
        uuid = uuid, serverIndex = null, type = BOOST_MESSAGE_TYPE, chat = "tribe", fromMe = fromMe,
        sender = alias?.let { JSONObject().put("alias", it).toString() },
        body = JSONObject().put("replyUuid", target).toString(), msat = 100_000, timestamp = 1, tag = null, paymentHash = null,
        replyUuid = target,
    )

    @Test
    fun `lists each distinct booster once, in first-seen order`() {
        val summary = boostSummaries(
            listOf(boost("1", "m", "Bob"), boost("2", "m", "Cat"), boost("3", "m", "Bob"), boost("4", "m", null, fromMe = true)),
            myAlias = "Me", myPhotoUrl = null, isTribe = true,
        )["m"]!!
        assertEquals(listOf("Bob", "Cat", "Me"), summary.senders.map { it.name })
        assertEquals(4, summary.count)
    }

    @Test
    fun `keeps per-boost amounts oldest first`() {
        fun b(uuid: String, alias: String, msat: Long, ts: Long) = boost(uuid, "m", alias).copy(msat = msat, timestamp = ts)
        val summary = boostSummaries(
            listOf(b("1", "Cat", 2_000, 20), b("2", "Bob", 5_000, 10), b("3", "Cat", 3_000, 30)),
            myAlias = "Me", myPhotoUrl = null, isTribe = true,
        )["m"]!!
        assertEquals(listOf("Bob" to 5_000L, "Cat" to 2_000L, "Cat" to 3_000L), summary.boosts.map { it.sender.name to it.amountMsat })
        assertEquals("10 sats from 3 boosts", boostSummaryLine(summary))
    }
}
