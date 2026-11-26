package com.sphinx.peel.engine

import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private class KeptStore(var text: String?) : HiveStore {
    override fun read() = text
    override fun write(text: String) { this.text = text }
    override fun delete() { text = null }
}

@RunWith(RobolectricTestRunner::class)
class StadeumReadAppsTest {
    private val fast = HivePeer("fast", "https://fast.example.com", "t1", null)
    private val slow = HivePeer("slow", "https://slow.example.com", "t2", null)

    @After fun close() { Stadeum.model.close() }

    private fun app(peer: HivePeer, name: String) = WorkspaceApp(peer.id, name, name)

    private fun open() {
        val link = HiveLink(
            hive = "https://hive.test", credential = "hivedev_secret", deviceId = "d1", deviceName = "Pixel", actor = "jane",
            peers = listOf(fast, slow), errors = emptyList(),
            expiresAt = Instant.parse("2030-01-01T00:00:00Z"), renewedAt = Instant.parse("2026-01-01T00:00:00Z"),
            revokedAt = null, identified = true, sphinxRefused = null, sphinxPubkey = "02cd",
        )
        Stadeum.model.open(KeptStore(link.toJson()), renewNow = false)
    }

    @Test fun publishesTheFastPeersAppsBeforeTheSlowOneAnswers() = runBlocking {
        open()
        val release = CompletableDeferred<Unit>()
        val read = async(Dispatchers.Default) {
            Stadeum.readApps { peer ->
                if (peer.id == "slow") { release.await(); listOf(app(slow, "b")) } else listOf(app(fast, "a"))
            }
        }
        withTimeout(5_000) { while (Stadeum.apps.isEmpty()) kotlinx.coroutines.delay(10) }
        assertEquals(listOf(app(fast, "a")), Stadeum.apps)
        assertTrue(Stadeum.readingApps)
        release.complete(Unit)
        read.await()
        assertEquals(setOf(app(fast, "a"), app(slow, "b")), Stadeum.apps.toSet())
        assertEquals(emptyList<String>(), Stadeum.unreadAppIds)
    }
}
