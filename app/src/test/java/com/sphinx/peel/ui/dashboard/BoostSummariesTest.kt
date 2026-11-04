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
}
