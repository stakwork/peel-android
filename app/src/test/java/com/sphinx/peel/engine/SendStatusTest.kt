package com.sphinx.peel.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class SendStatusTest {
    @Test
    fun liveStatusMapsTheMixersWords() {
        assertEquals(SendStatus.DELIVERED, SendStatus.live("COMPLETE"))
        assertEquals(SendStatus.FAILED, SendStatus.live("FAILED"))
        assertEquals(SendStatus.UNSETTLED, SendStatus.live("PENDING"))
        assertEquals(null, SendStatus.live("nonsense"))
    }

    @Test
    fun noRecordsWithoutJson() {
        // `recorded` parses with org.json, which plain JVM tests cannot load.
        assertEquals(emptyList<Any>(), SendStatus.recorded(null))
    }

    @Test
    fun statusesOnlyMoveForward() {
        assertEquals(emptyList<String>(), SendStatus.PENDING.movesFrom)
        assertEquals(listOf("PENDING", "UNSETTLED", "FAILED"), SendStatus.DELIVERED.movesFrom)
        assertEquals(listOf("PENDING", "UNSETTLED"), SendStatus.FAILED.movesFrom)
    }
}
