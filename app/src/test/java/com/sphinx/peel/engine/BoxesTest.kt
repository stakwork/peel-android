package com.sphinx.peel.engine

import com.sphinx.peel.ui.page.PublicPageRouter
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/// The strut boxes of the open account ([BoxesModel]; iOS `BoxesTests`, PR #574): a link signed only on the person's yes and kept,
/// renewed when due, let go when its member removed it, and a refused token renewed once before a box opens. Each against a
/// stand-in strut ([FakeBox]) on this device's own address, as a debug build may link one.
@RunWith(RobolectricTestRunner::class)
class BoxesTest {
    private var signs = 0

    /// A model whose account signs as `02ab`, counting its signatures; the boxes' answers by address from [boxes].
    private fun model(boxes: Map<String, FakeBox>, now: Instant = BoxFixture.now): BoxesModel =
        BoxesModel(
            clientFor = { url -> BoxClient(url, boxes[url] ?: error("no box at $url")) },
            dispatcher = Dispatchers.Unconfined,
            now = { now },
            sleep = { awaitCancellation() },
            allowsLoopback = true,
        ).apply {
            sign = { signs++; VisitorSignature("02ab", "1f") }
            accountName = { "Ada on Sphinx" }
        }

    /// A stand-in strut: a renewal's challenge for 127.0.0.1, [token]'s answer to `POST /people/token`, [apps]' to `GET /apps`, and
    /// [link]'s to `POST /people/link`.
    private fun strut(
        token: (bearer: String?) -> BoxResponse = { BoxResponse(200, BoxFixture.answer(token = "strut-person.renewed")) },
        apps: (bearer: String?) -> BoxResponse = { BoxResponse(200, """{"apps":[]}""") },
        link: () -> BoxResponse = { BoxResponse(200, BoxFixture.answer()) },
    ) = FakeBox { method, url, _, bearer ->
        when {
            url.endsWith("/people/challenge") ->
                BoxResponse(200, """{"challenge":"${BoxFixture.challenge("127.0.0.1", actor = null, name = null, exp = BoxFixture.LATER)}","exp":1}""")
            url.endsWith("/people/token") -> token(bearer)
            url.endsWith("/people/link") && method == "POST" -> link()
            url.endsWith("/apps") -> apps(bearer)
            else -> BoxResponse(404, """{"error":"not here"}""")
        }
    }

    private fun offer(box: String = "http://127.0.0.1:3400", host: String = "127.0.0.1") =
        BoxLinkOffer.of(box, BoxFixture.challenge(host, exp = BoxFixture.LATER))!!

    private fun tokens(store: MemoryBoxStore) = BoxLink.read(store).map { it.token }

    /// No signs nothing and asks nothing; yes signs once, posts the link with the account's name, and keeps the box the answer names.
    @Test fun `a box is linked only on the yes`() {
        val box = strut()
        val store = MemoryBoxStore()
        val model = model(mapOf("http://127.0.0.1:3400" to box))
        model.open(store)
        val offer = offer()
        model.take(offer)
        assertEquals(offer, model.asking)
        model.decline()
        assertNull(model.asking)
        assertNull(model.accept())
        assertEquals(0, signs)
        assertTrue(box.calls.isEmpty())

        model.take(offer)
        val job = model.accept()
        assertNotNull(job)
        runBlocking { job!!.join() }
        assertNull(model.asking)
        assertNull(model.linking)
        assertNull(model.problem)
        assertEquals(1, signs)
        assertEquals("Ada on Sphinx", JSONObject(box.calls.single().json!!).getString("name"))
        assertEquals(listOf("ada-3f9a"), model.links.map { it.actor })
        assertEquals(offer.box, model.links.single().url)
        assertEquals(model.links.map { it.token }, tokens(store))
    }

    /// Linking the same box again (as another member) replaces the kept one; another box is kept beside it.
    @Test fun `one link per box address`() {
        var name = "Ada Lovelace"
        val first = strut(link = { BoxResponse(200, BoxFixture.answer(name = name, token = "strut-person.$name".replace(' ', '-'))) })
        val second = FakeBox { _, _, _, _ -> BoxResponse(200, BoxFixture.answer(token = "strut-person.two")) }
        val store = MemoryBoxStore()
        val model = model(mapOf("http://127.0.0.1:3400" to first, "http://127.0.0.1:3401" to second))
        model.open(store)
        model.take(offer()); model.accept()
        name = "Grace Hopper"
        model.take(offer()); model.accept()
        model.take(offer("http://127.0.0.1:3401")); model.accept()
        assertEquals(listOf("Grace Hopper", "Ada Lovelace"), model.links.map { it.name })
        assertEquals(listOf("strut-person.Grace-Hopper", "strut-person.two"), tokens(store))
    }

    /// What the account kept that cannot be read is never written over: a link is refused rather than kept in place of every box
    /// there, and Forget changes nothing (iOS's review found a failed read later erasing the kept boxes).
    @Test fun `boxes that cannot be read are never written over`() {
        val box = strut()
        val store = MemoryBoxStore("not what Peel keeps")
        val model = model(mapOf("http://127.0.0.1:3400" to box))
        model.open(store)
        assertTrue(model.links.isEmpty())
        assertNotNull(model.problem)
        model.take(offer())
        assertNull(model.accept())
        assertTrue(model.problem!!.contains("couldn't read"))
        assertEquals(0, signs)
        assertTrue(box.calls.isEmpty())
        model.forget(BoxFixture.box("http://127.0.0.1:3400"))
        assertEquals("not what Peel keeps", store.text)
        // Read again (the account opened again) and now readable: kept as it is.
        store.text = null
        model.open(store)
        assertNull(model.problem)
    }

    /// A link Peel will not sign is said, and never asked.
    @Test fun `a link for another host is never asked`() {
        val model = model(emptyMap())
        model.open(MemoryBoxStore())
        model.take(BoxLinkOffer.of("https://box.example.com", BoxFixture.challenge("evil.example.com", exp = BoxFixture.LATER))!!)
        assertNull(model.asking)
        assertTrue(model.problem!!.contains("evil.example.com"))
        // A link that is no box's link at all: said, nothing asked.
        model.takeBroken()
        assertNull(model.asking)
        assertEquals(BoxLinkOffer.BROKEN, model.problem)
    }

    /// A link opened while Peel was locked waits for its yes across the lock, and is linked once the account opens; a yes with no
    /// account open signs nothing.
    @Test fun `a link opened while locked waits for the account`() {
        val box = strut()
        val model = model(mapOf("http://127.0.0.1:3400" to box))
        model.take(offer())
        model.close()
        assertNotNull(model.asking)
        val noAccount = model(emptyMap())
        noAccount.take(offer())
        assertNull(noAccount.accept())
        assertTrue(noAccount.problem!!.contains("Unlock Peel"))
        assertTrue(box.calls.isEmpty())

        val store = MemoryBoxStore()
        model.open(store)
        assertNotNull(model.asking)
        model.accept()
        assertEquals(1, model.links.size)
        assertEquals(1, tokens(store).size)
    }

    /// As the account opens, a box due is renewed and kept with its new token; one whose member removed the link there
    /// (`403 not_linked`) is let go and said, and gone from what is kept.
    @Test fun `a due box is renewed and an unlinked one let go`() {
        val renews = strut()
        val unlinked = strut(token = { BoxResponse(403, """{"error":"not_linked: sphinx:02ab is not linked"}""") })
        val store = MemoryBoxStore()
        val dayAgo = BoxFixture.now.minusSeconds(90_000)
        BoxLink.write(
            listOf(BoxFixture.box("http://127.0.0.1:3400", renewedAt = dayAgo), BoxFixture.box("http://127.0.0.1:3401", renewedAt = dayAgo)), store,
        )
        val model = model(mapOf("http://127.0.0.1:3400" to renews, "http://127.0.0.1:3401" to unlinked))
        model.open(store)
        assertEquals(listOf("http://127.0.0.1:3400"), model.links.map { it.url })
        assertEquals("strut-person.renewed", model.links.single().token)
        assertTrue(model.notice!!.contains("removed Peel's link"))
        assertEquals(listOf("strut-person.renewed"), tokens(store))
        assertEquals(2, signs)
    }

    /// A box not yet due is left alone; one whose renewal fails is said and kept, to be tried again.
    @Test fun `a failed renewal is said and the box kept`() {
        val down = strut(token = { BoxResponse(502, "") })
        val fresh = strut()
        val store = MemoryBoxStore()
        BoxLink.write(
            listOf(
                BoxFixture.box("http://127.0.0.1:3400", renewedAt = BoxFixture.now.minusSeconds(90_000)),
                BoxFixture.box("http://127.0.0.1:3401"),
            ),
            store,
        )
        val model = model(mapOf("http://127.0.0.1:3400" to down, "http://127.0.0.1:3401" to fresh))
        model.open(store)
        assertEquals(2, model.links.size)
        assertTrue(model.problem!!.startsWith("Could not renew Peel's link to 127.0.0.1."))
        assertTrue(fresh.calls.isEmpty())
    }

    /// A box that refuses the token kept (401) is renewed once and opens with the new one, as the first request's bearer; one whose
    /// renewal says the link is gone does not open and is let go, and its page is no longer kept open.
    @Test fun `a refused token is renewed once before the box opens`() = runBlocking {
        var linked = true
        val box = strut(
            token = { if (linked) BoxResponse(200, BoxFixture.answer(token = "strut-person.renewed")) else BoxResponse(403, """{"error":"not_linked: gone"}""") },
            apps = { bearer -> if (bearer == "strut-person.renewed") BoxResponse(200, """{"apps":[]}""") else BoxResponse(401, """{"error":"no longer linked"}""") },
        )
        val base = "http://127.0.0.1:3400/lab"
        val store = MemoryBoxStore()
        BoxLink.write(listOf(BoxFixture.box(base)), store)
        val model = model(mapOf(base to box))
        model.open(store)
        val page = model.home(model.links.single())
        assertEquals(base + "/", page.url)
        assertEquals(mapOf("Authorization" to "Bearer strut-person.renewed"), page.request.headers)
        assertTrue(model.shows(page))
        assertEquals("strut-person.renewed", model.links.single().token)
        val router = PublicPageRouter()
        router.openOrg(page)
        router.workspacesChanged(emptyList(), model::shows)
        assertEquals(page, router.orgPage)

        linked = false
        BoxLink.write(listOf(BoxFixture.box(base)), store)
        model.open(store)
        assertNull(model.page(model.links.single()))
        assertTrue(model.links.isEmpty())
        assertTrue(model.notice!!.contains("removed Peel's link"))
        assertFalse(model.shows(page))
        router.workspacesChanged(emptyList(), model::shows)
        assertNull(router.orgPage)
    }

    /// A box the box still refuses after a fresh token is let go too; a box that answers neither is said and kept.
    @Test fun `a box that refuses a fresh token is let go`() = runBlocking {
        val refuses = strut(apps = { BoxResponse(401, "{}") })
        val store = MemoryBoxStore()
        BoxLink.write(listOf(BoxFixture.box("http://127.0.0.1:3400")), store)
        val model = model(mapOf("http://127.0.0.1:3400" to refuses))
        model.open(store)
        assertNull(model.page(model.links.single()))
        assertTrue(model.links.isEmpty())
        assertTrue(model.notice!!.contains("removed Peel's link"))

        val down = strut(apps = { BoxResponse(503, "") })
        BoxLink.write(listOf(BoxFixture.box("http://127.0.0.1:3401")), store)
        val other = model(mapOf("http://127.0.0.1:3401" to down))
        other.open(store)
        assertNull(other.page(other.links.single()))
        assertEquals(1, other.links.size)
        assertTrue(other.problem!!.startsWith("Could not open 127.0.0.1."))
    }

    /// Forget lets one box go from this device; the account forgotten takes every box with it, and a waiting question.
    @Test fun `forget and the account forgotten`() {
        val store = MemoryBoxStore()
        BoxLink.write(listOf(BoxFixture.box("http://127.0.0.1:3400"), BoxFixture.box("http://127.0.0.1:3401")), store)
        val model = model(emptyMap())
        model.open(store)
        model.forget(model.links.first())
        assertEquals(listOf("http://127.0.0.1:3401"), model.links.map { it.url })
        assertEquals(listOf("http://127.0.0.1:3401"), BoxLink.read(store).map { it.url })
        model.take(offer())
        model.forgetAll()
        assertNull(store.text)
        assertNull(model.asking)
        assertTrue(model.links.isEmpty())
        assertFalse(model.isOpen)
    }

    /// Closing (a lock) lets the boxes go here; what was under way keeps nothing and says nothing.
    @Test fun `closing lets the boxes go`() {
        val store = MemoryBoxStore()
        BoxLink.write(listOf(BoxFixture.box("http://127.0.0.1:3400")), store)
        val model = model(emptyMap())
        model.open(store)
        assertEquals(1, model.links.size)
        model.close()
        assertTrue(model.links.isEmpty())
        assertFalse(model.isOpen)
        assertEquals(1, BoxLink.read(store).size)
    }

    /// The words of an error, as iOS's `PeelError.noted` puts them.
    @Test fun `errors are said as sentences`() {
        assertEquals("Could not open a.example.com. a.example.com is busy.", BoxesModel.noted(BoxException("a.example.com is busy"), "open a.example.com"))
        assertEquals("Could not link Peel. Failed to connect.", BoxesModel.noted(java.io.IOException("failed to connect:"), "link Peel"))
    }
}
