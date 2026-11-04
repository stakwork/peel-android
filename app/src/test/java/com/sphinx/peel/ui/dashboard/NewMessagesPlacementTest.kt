package com.sphinx.peel.ui.dashboard

import com.sphinx.peel.storage.MessageRow
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class NewMessagesPlacementTest {
    private val boost = 29
    private fun row(i: Long, fromMe: Boolean = false, type: Int = 0) =
        MessageRow(uuid = "u$i", serverIndex = i, type = type, chat = "c", fromMe = fromMe, sender = null,
            body = "x", msat = null, timestamp = i, tag = null, paymentHash = null)

    @Test fun firstIncomingPastMarker() =
        assertEquals(2, newMessagesIndex(listOf(row(1), row(2, fromMe = true), row(3), row(4)), 2, boost))

    @Test fun noMarkerNoLine() = assertNull(newMessagesIndex(listOf(row(1), row(2)), null, boost))

    @Test fun neverReadMarkerMinusOneMarksFirst() = assertEquals(0, newMessagesIndex(listOf(row(1), row(2)), -1, boost))

    @Test fun ownMessagesAndBoostsDoNotClaimIt() =
        assertEquals(3, newMessagesIndex(listOf(row(1), row(2, fromMe = true), row(3, type = boost), row(4)), 1, boost))

    @Test fun nothingPastMarker() = assertNull(newMessagesIndex(listOf(row(1), row(2)), 2, boost))
}
