package com.sphinx.peel.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/// A box's places for a person ([PlacesClient], [BoxPlaces]; strut's specs/API.md §13.4 and §13.6 "Places", plans/jamie-in-tribes.md
/// §4): the routes Peel calls with the token it holds for a box, against a stand-in strut ([PlacesStrut]) that answers as strut's
/// `src/createStrut.ts` does at strut main (#787).
@RunWith(RobolectricTestRunner::class)
class BoxPlacesTest {
    private val box = BoxFixture.box("https://strut.stadeum.example.com")
    private val tribe = TribePlace(PlacesStrut.TRIBE, PlacesStrut.LINK, "Builders")

    private fun <T> refused(block: suspend () -> T): BoxException = try {
        runBlocking { block() }
        fail("no refusal")
        throw AssertionError()
    } catch (e: BoxException) {
        e
    }

    /// The person's places and the Sphinx host's account, read with the box's token; the host's key is the box's Jamie.
    @Test fun `places are read with the box's token`() {
        val strut = PlacesStrut(box)
        strut.linkAs(PlacesStrut.ADA, tribe.key)
        val places = runBlocking { PlacesClient(box, strut.http).read() }
        assertEquals(PlacesStrut.JAMIE, places.jamie)
        assertEquals(listOf(BoxPlaces.Host("sphinx", "sphinx:${PlacesStrut.JAMIE}")), places.hosts)
        assertNotNull(places.place(tribe.key))
        // The key as it was linked, whatever its case.
        assertNotNull(places.place(tribe.key.uppercase()))
        assertNull(places.place("02" + "00".repeat(32)))
        val call = strut.http.calls.single()
        assertEquals("GET" to "https://strut.stadeum.example.com/people/places", call.method to call.url)
        assertEquals(box.token, call.bearer)
    }

    /// No host ready (none, or it hasn't said who it is) is no Jamie; a strut from before places has none either; a refused token
    /// is its own failure, for [BoxesModel.asMember] to renew; an answer that is not one is said.
    @Test fun `what a read can answer`() {
        val notReady = PlacesStrut(box, jamie = null)
        val read = runBlocking { PlacesClient(box, notReady.http).read() }
        assertNull(read.jamie)
        assertTrue(read.places.isEmpty())

        val old = FakeBox { _, _, _, _ -> BoxResponse(404, """{"error":"not found"}""") }
        assertEquals(BoxPlaces.NONE, runBlocking { PlacesClient(box, old).read() })

        val expired = FakeBox { _, _, _, _ -> BoxResponse(401, """{"error":"unauthorized: expired"}""") }
        assertTrue(refused { PlacesClient(box, expired).read() } is BoxTokenRefused)

        val garbled = FakeBox { _, _, _, _ -> BoxResponse(200, "<html>") }
        assertEquals("stadeum's answer about Jamie could not be read.", refused { PlacesClient(box, garbled).read() }.message)

        val down = FakeBox { _, _, _, _ -> BoxResponse(500, """{"error":"it broke"}""") }
        assertEquals("stadeum could not read its tribes: it broke.", refused { PlacesClient(box, down).read() }.message)
    }

    /// A pick posts the tribe for the Sphinx host, its key, link and name; the answer is the place and the box's Jamie.
    @Test fun `a tribe is linked for the box's Jamie`() {
        val strut = PlacesStrut(box)
        val linked = runBlocking { PlacesClient(box, strut.http).link(tribe) }
        assertEquals(PlacesStrut.JAMIE, linked.jamie)
        assertEquals(tribe.key, linked.place.key)
        assertEquals("sphinx:${PlacesStrut.ADA}", linked.place.account)
        val call = strut.http.calls.single()
        assertEquals("POST" to "https://strut.stadeum.example.com/people/places", call.method to call.url)
        assertEquals(box.token, call.bearer)
        val body = JSONObject(call.json!!)
        assertEquals(listOf("sphinx", tribe.key, tribe.link, "Builders"), listOf("host", "key", "link", "name").map { body.getString(it) })

        // A name is one line of at most 80 characters, as strut keeps one; none is left out.
        runBlocking { PlacesClient(box, strut.http).link(tribe.copy(name = "Build\nders " + "x".repeat(100))) }
        assertTrue(JSONObject(strut.http.calls.last().json!!).getString("name").let { it.length <= 80 && '\n' !in it })
        runBlocking { PlacesClient(box, strut.http).link(tribe.copy(name = null)) }
        assertFalse(JSONObject(strut.http.calls.last().json!!).has("name"))
    }

    /// Another person's place, a box with no Sphinx host ready, and the rest, each in a short line.
    @Test fun `a link's refusals in words`() {
        val taken = PlacesStrut(box).apply { linkAs("02" + "cd".repeat(32), tribe.key) }
        assertEquals(
            "Someone else on stadeum already added Jamie to this tribe.",
            refused { PlacesClient(box, taken.http).link(tribe) }.message,
        )
        val notReady = PlacesStrut(box, jamie = null)
        assertEquals("stadeum isn't ready for tribes yet.", refused { PlacesClient(box, notReady.http).link(tribe) }.message)
        // A 404 that is not strut's no_host: a strut from before places.
        val old = FakeBox { _, _, _, _ -> BoxResponse(404, "{}") }
        assertEquals("stadeum could not add Jamie: HTTP 404.", refused { PlacesClient(box, old).link(tribe) }.message)
        val bad = FakeBox { _, _, _, _ -> BoxResponse(400, """{"error":"key: no spaces"}""") }
        assertEquals("stadeum could not add Jamie: key: no spaces.", refused { PlacesClient(box, bad).link(tribe) }.message)
        val busy = FakeBox { _, _, _, _ -> BoxResponse(429, "{}") }
        assertEquals("stadeum is busy. Try again in a minute.", refused { PlacesClient(box, busy).link(tribe) }.message)
        val expired = FakeBox { _, _, _, _ -> BoxResponse(401, "{}") }
        assertTrue(refused { PlacesClient(box, expired).link(tribe) } is BoxTokenRefused)
        // An answer for another tribe, or without the host's key, is not taken.
        val wrong = FakeBox { _, _, _, _ ->
            BoxResponse(200, JSONObject().put("place", PlacesStrut.place("02" + "00".repeat(32), PlacesStrut.ADA)).put("account", "sphinx:${PlacesStrut.JAMIE}").toString())
        }
        assertEquals("stadeum's answer about Jamie could not be read.", refused { PlacesClient(box, wrong).link(tribe) }.message)
        val keyless = FakeBox { _, _, _, _ ->
            BoxResponse(200, JSONObject().put("place", PlacesStrut.place(tribe.key, PlacesStrut.ADA)).put("account", "sphinx:nope").toString())
        }
        assertEquals("stadeum's answer about Jamie could not be read.", refused { PlacesClient(box, keyless).link(tribe) }.message)
    }

    /// No Jamie deletes the person's place by its key; one that was not theirs is no removal.
    @Test fun `a place is removed by its key`() {
        val strut = PlacesStrut(box)
        strut.linkAs(PlacesStrut.ADA, tribe.key)
        assertTrue(runBlocking { PlacesClient(box, strut.http).unlink(tribe.key) })
        val call = strut.http.calls.single()
        assertEquals("DELETE" to "https://strut.stadeum.example.com/people/places/sphinx/${tribe.key}", call.method to call.url)
        assertEquals(box.token, call.bearer)
        assertFalse(runBlocking { PlacesClient(box, strut.http).unlink(tribe.key) })
        // A key is one path segment, whatever it holds.
        assertEquals("a%2Fb%20c%3F", PlacesClient.segment("a/b c?"))
    }

    /// A Sphinx account names a compressed key, bare or as `sphinx:<key>`, read in lower case.
    @Test fun `a Sphinx account's key`() {
        assertEquals(PlacesStrut.JAMIE, BoxPlaces.sphinxKey("sphinx:${PlacesStrut.JAMIE.uppercase()}"))
        assertEquals(PlacesStrut.JAMIE, BoxPlaces.sphinxKey(PlacesStrut.JAMIE))
        for (account in listOf("email:${PlacesStrut.JAMIE}", "sphinx:", "sphinx:04" + "b0".repeat(32), "sphinx:03" + "zz".repeat(32), "sphinx:03")) {
            assertNull(account, BoxPlaces.sphinxKey(account))
        }
        assertEquals("place_linked", PlacesClient.code("""{"error":"place_linked: sphinx:02f1 is linked by another person here"}"""))
        assertNull(PlacesClient.code("""{"error":"no code here"}"""))
    }

    /// What a link and an unlink leave in a box's read: the place first, the host's account as answered; the place gone.
    @Test fun `places after a link and an unlink`() {
        val other = PlacesStrut.place("02" + "00".repeat(32), PlacesStrut.ADA)
        val read = BoxPlaces.parse(JSONObject().put("hosts", JSONArray()).put("places", JSONArray().put(other)).toString())!!
        val place = BoxPlace.parse(PlacesStrut.place(tribe.key, PlacesStrut.ADA))!!
        val linked = read.linking(place, "sphinx:${PlacesStrut.JAMIE}")
        assertEquals(PlacesStrut.JAMIE, linked.jamie)
        assertEquals(listOf(tribe.key, "02" + "00".repeat(32)), linked.places.map { it.key })
        assertEquals(listOf(tribe.key, "02" + "00".repeat(32)), linked.linking(place, "sphinx:${PlacesStrut.JAMIE}").places.map { it.key })
        assertEquals(listOf("02" + "00".repeat(32)), linked.unlinking(tribe.key.uppercase()).places.map { it.key })
        // What is not a places answer.
        for (body in listOf("", "[]", """{"hosts":[]}""", """{"places":[]}""")) assertNull(body, BoxPlaces.parse(body))
        // A host or place that can't be read is skipped.
        val partial = BoxPlaces.parse("""{"hosts":[{"host":"sphinx"},{"host":"sphinx","account":"sphinx:${PlacesStrut.JAMIE}"}],"places":[{"key":"x"}]}""")!!
        assertEquals(1, partial.hosts.size)
        assertTrue(partial.places.isEmpty())
    }
}

/// A stand-in strut's places for one box (strut's `src/createStrut.ts`, `GET`, `POST` and `DELETE /people/places`): its Sphinx host
/// ready with [jamie]'s key (null: none ready), the places by key with their linker, and the token it takes ([token], else `401`).
class PlacesStrut(private val box: BoxLink, var jamie: String? = JAMIE, var token: String = box.token, private val person: String = ADA) {
    /// Each place's linker (a Sphinx key), by tribe key.
    val linkers = linkedMapOf<String, String>()

    fun linkAs(linker: String, key: String) { linkers[key] = linker }

    val http = FakeBox { method, url, json, bearer ->
        val path = url.removePrefix(box.url)
        when {
            path == "/people/challenge" ->
                BoxResponse(200, """{"challenge":"${BoxFixture.challenge(box.host, actor = null, name = null, exp = BoxFixture.LATER)}","exp":1}""")
            path == "/people/token" -> {
                token = "strut-person.renewed"
                BoxResponse(200, BoxFixture.answer(token = token, exp = BoxFixture.LATER))
            }
            bearer != token -> BoxResponse(401, """{"error":"unauthorized: the token ran out"}""")
            path == "/people/places" && method == "GET" -> BoxResponse(
                200,
                JSONObject()
                    .put("hosts", JSONArray().apply { jamie?.let { put(JSONObject().put("host", "sphinx").put("account", "sphinx:$it")) } })
                    .put("places", JSONArray().apply { linkers.filterValues { it == person }.keys.forEach { put(place(it, person)) } })
                    .toString(),
            )
            path == "/people/places" && method == "POST" -> {
                val key = JSONObject(json!!).getString("key")
                val ready = jamie
                when {
                    ready == null -> BoxResponse(404, """{"error":"no_host: no sphinx host is ready here"}""")
                    linkers[key]?.let { it != person } == true -> BoxResponse(409, """{"error":"place_linked: sphinx:$key is linked by another person here"}""")
                    else -> {
                        linkers[key] = person
                        BoxResponse(200, JSONObject().put("place", place(key, person)).put("account", "sphinx:$ready").toString())
                    }
                }
            }
            path.startsWith("/people/places/sphinx/") && method == "DELETE" -> {
                val key = path.removePrefix("/people/places/sphinx/")
                val removed = linkers[key] == person
                if (removed) linkers.remove(key)
                BoxResponse(200, """{"removed":$removed}""")
            }
            else -> BoxResponse(404, """{"error":"not found"}""")
        }
    }

    companion object {
        /// A tribe's key, the box's Jamie, and Ada's Sphinx key.
        val TRIBE = "02" + "f1".repeat(32)
        val JAMIE = "03" + "b0".repeat(32)
        val ADA = "02" + "ab".repeat(32)
        val LINK = "sphinx.chat://?action=tribeV2&pubkey=$TRIBE&host=tribes.example.com"

        fun place(key: String, linker: String): JSONObject = JSONObject()
            .put("host", "sphinx").put("key", key).put("link", "sphinx.chat://?action=tribeV2&pubkey=$key&host=tribes.example.com")
            .put("name", "Builders").put("account", "sphinx:$linker").put("at", BoxFixture.NOW_MS)
    }
}

/// The open account linked to [boxes], each answered by its stand-in strut, and its boxes as tribes' Jamies, all on the calling
/// thread; [sleeps] records each wait between approvals.
fun boxJamies(vararg boxes: Pair<BoxLink, PlacesStrut>, sleeps: MutableList<Long> = mutableListOf()): BoxJamies {
    val by = boxes.associate { (box, strut) -> box.url to strut }
    val model = BoxesModel(
        clientFor = { url -> BoxClient(url, by.getValue(url).http) },
        dispatcher = Dispatchers.Unconfined,
        now = { BoxFixture.now },
        sleep = { awaitCancellation() },
    ).apply { sign = { VisitorSignature("02ab", "1f") } }
    val store = MemoryBoxStore()
    BoxLink.write(boxes.map { it.first }, store)
    model.open(store)
    return BoxJamies(
        model,
        clientFor = { box -> PlacesClient(box, by.getValue(box.url).http) },
        dispatcher = Dispatchers.Unconfined,
        nowMs = { BoxFixture.NOW_MS },
        sleep = { sleeps += it },
    )
}
