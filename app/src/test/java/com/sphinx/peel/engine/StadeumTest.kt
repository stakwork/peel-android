package com.sphinx.peel.engine

import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/// An in-memory [HiveStore].
private class MemoryStore(var text: String? = null) : HiveStore {
    override fun read() = text
    override fun write(text: String) { this.text = text }
    override fun delete() { text = null }
}

/// Answers by path, in order; records each call.
private class FakeHive(private val answers: MutableMap<String, MutableList<Any>> = mutableMapOf()) : HiveHttp {
    val calls = mutableListOf<String>()
    fun on(path: String, vararg answer: Any) { answers.getOrPut(path) { mutableListOf() }.addAll(answer) }
    override suspend fun call(method: String, url: String, json: String?, bearer: String?, timeoutSeconds: Long): HiveResponse {
        val path = url.removePrefix("https://hive.test/")
        calls += "$method $path"
        val queue = answers[path] ?: error("no answer for $path")
        val next = if (queue.size > 1) queue.removeAt(0) else queue[0]
        if (next is IOException) throw next
        return next as HiveResponse
    }
}

private fun ok(body: String) = HiveResponse(200, body)

private const val PAIR = """{"deviceCode":"dc","userCode":"ABCD-1234","verificationUri":"https://hive.test/pair",
    "verificationUriComplete":"https://hive.test/pair?code=ABCD-1234","expiresIn":600,"interval":1}"""
private const val PAIRED = """{"credential":"hivedev_secret","device":{"id":"d1","name":"Pixel"},"actor":"jane",
    "peers":[{"id":"hive","baseUrl":"https://hive.example.com","token":"tok","label":"stakwork"},
             {"id":"bad","baseUrl":"http://insecure.example.com","token":"t2"}],"expiresAt":"2030-01-01T00:00:00Z"}"""

@RunWith(RobolectricTestRunner::class)
class StadeumTest {
    private fun link(revoked: Boolean = false) = HiveLink(
        hive = "https://hive.test", credential = "hivedev_secret", deviceId = "d1", deviceName = "Pixel", actor = "jane",
        peers = listOf(HivePeer("hive", "https://hive.example.com", "tok", "stakwork")),
        errors = listOf(HiveOrgError("x", "no record")),
        expiresAt = Instant.parse("2030-01-01T00:00:00Z"), renewedAt = Instant.parse("2026-01-01T00:00:00Z"),
        revokedAt = if (revoked) Instant.parse("2026-01-02T00:00:00Z") else null,
        identified = true, sphinxRefused = SphinxRefusal("02ab", "taken"), sphinxPubkey = "02cd",
    )

    private fun model(http: HiveHttp, now: Instant = Instant.parse("2026-01-01T01:00:00Z")) =
        StadeumModel({ HiveClient("https://hive.test", http) }, Dispatchers.Unconfined, { now }, { ms -> if (ms >= 3_600_000) awaitCancellation() })

    private fun <T> eventually(check: () -> T?): T {
        repeat(200) { check()?.let { return it }; Thread.sleep(10) }
        error("never happened")
    }

    @Test
    fun `link survives its kept form and keeps the token out of toString`() {
        val l = link()
        assertEquals(l, HiveLink.parse(l.toJson()))
        assertTrue("hivedev_secret" !in l.toString())
        assertNull(HiveLink.parse("not json"))
    }

    @Test
    fun `link is due after a day or near expiry, and belongs unless recorded for another`() {
        val l = link()
        assertFalse(l.due(Instant.parse("2026-01-01T23:00:00Z")))
        assertTrue(l.due(Instant.parse("2026-01-02T01:00:00Z")))
        assertTrue(l.copy(expiresAt = Instant.parse("2026-01-01T10:00:00Z")).due(Instant.parse("2026-01-01T01:00:00Z")))
        assertTrue(l.belongs("02cd")); assertFalse(l.belongs("02ee")); assertTrue(l.copy(sphinxPubkey = null).belongs("02ee"))
    }

    @Test
    fun `pairing keeps https peers only and the gate follows connect and disconnect`() {
        val http = FakeHive().apply {
            on("api/strut-devices/pair", ok(PAIR))
            on("api/strut-devices/pair/token", HiveResponse(400, """{"error":"authorization_pending"}"""), ok(PAIRED))
            on("api/strut-devices/peers", ok("""{"actor":"jane","peers":[],"sphinx":true}"""))
        }
        val store = MemoryStore()
        val m = model(http)
        m.open(store, renewNow = false)
        assertEquals(StadeumModel.Status.Off, m.status)
        assertFalse(Stadeum.showsApps(m.live, null))
        m.connect("Pixel")
        eventually { if (m.status == StadeumModel.Status.Connected) true else null }
        assertEquals(listOf("hive"), m.live.map { it.id })
        assertEquals("stakwork", m.live[0].name)
        assertEquals(listOf("bad"), m.link!!.errors.map { it.org })
        assertTrue(Stadeum.showsApps(m.live, null))
        val kept = HiveLink.parse(store.text!!)!!
        assertEquals("hivedev_secret", kept.credential)
        m.disconnect()
        assertNull(store.text)
        assertEquals(StadeumModel.Status.Off, m.status)
        assertTrue(m.live.isEmpty())
        assertFalse(Stadeum.showsApps(m.live, null))
        m.close()
    }

    @Test
    fun `denied code ends in a problem`() {
        val http = FakeHive().apply {
            on("api/strut-devices/pair", ok(PAIR))
            on("api/strut-devices/pair/token", HiveResponse(400, """{"error":"access_denied"}"""))
        }
        val m = model(http)
        m.open(MemoryStore())
        m.connect("Pixel")
        eventually { m.problem }
        assertEquals(StadeumModel.Status.Off, m.status)
        assertEquals("It was turned down in Stadeum.", m.problem)
        m.close()
    }

    @Test
    fun `a pairing page on another site is refused`() {
        val http = FakeHive().apply { on("api/strut-devices/pair", ok(PAIR.replace("hive.test/pair?", "evil.test/pair?"))) }
        val m = model(http)
        m.open(MemoryStore())
        m.connect("Pixel")
        assertTrue(eventually { m.problem }.contains("another site"))
        m.close()
    }

    @Test
    fun `open reads what is kept and renews it`() {
        val http = FakeHive().apply {
            on("api/strut-devices/peers", ok("""{"actor":"jane","peers":[{"id":"two","baseUrl":"https://two.example.com","token":"t"}],"sphinx":true}"""))
        }
        val m = model(http)
        m.open(MemoryStore(link().toJson()))
        assertEquals(StadeumModel.Status.Connected, m.status)
        eventually { if (m.live.map { it.id } == listOf("two")) true else null }
        assertTrue(m.identified)
        m.close()
        assertEquals(StadeumModel.Status.Off, m.status)
        assertTrue(m.live.isEmpty())
    }

    @Test
    fun `revoked on renewal needs pairing, empties live and is kept revoked`() {
        val http = FakeHive().apply { on("api/strut-devices/peers", HiveResponse(401, "{}")) }
        val store = MemoryStore(link().toJson())
        val m = model(http)
        m.open(store)
        eventually { if (m.status == StadeumModel.Status.NeedsPairing) true else null }
        assertTrue(m.revoked)
        assertTrue(m.live.isEmpty())
        assertNotNull(HiveLink.parse(store.text!!)!!.revokedAt)
        m.close()
        // Still revoked after a restart: no renewal, tokens unused.
        val again = model(FakeHive())
        again.open(store)
        assertEquals(StadeumModel.Status.NeedsPairing, again.status)
        assertTrue(again.live.isEmpty())
        again.close()
    }

    @Test
    fun `identify registers the Sphinx account when Hive does not know it`() {
        val http = FakeHive().apply {
            on("api/strut-devices/peers", ok("""{"actor":"jane","peers":[],"sphinx":false}"""))
            on("api/strut-devices/sphinx", ok("{}"))
        }
        val store = MemoryStore(link().copy(identified = false, sphinxRefused = null).toJson())
        val m = model(http)
        m.sphinxProof = { SphinxProof("02cd", "token") }
        m.open(store)
        eventually { if (m.identified) true else null }
        assertTrue(http.calls.contains("POST api/strut-devices/sphinx"))
        m.close()
    }

    @Test
    fun `a pairing recorded for another account is let go`() {
        val store = MemoryStore(link().toJson())
        val m = model(FakeHive().apply { on("api/strut-devices/peers", ok("""{"actor":"jane","peers":[]}""")) })
        m.sphinxProof = { SphinxProof("02ff", "token") }
        m.open(store)
        eventually { if (m.status == StadeumModel.Status.Off) true else null }
        assertNull(store.text)
        assertTrue(m.reconnectHint)
        m.close()
    }
}
