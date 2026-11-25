package com.sphinx.peel.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/// A tribe's Jamie row over the boxes ([TribeJamie], [BoxJamies]; strut's plans/jamie-in-tribes.md §4, item 7): what it offers and
/// shows picked, a pick linked on one box and taken off the others, a refused token renewed once, and a box's Jamie approved when it
/// asks to join a tribe the person linked there. Against stand-in struts ([PlacesStrut]).
@RunWith(RobolectricTestRunner::class)
class BoxJamiesTest {
    private val a = BoxFixture.box("https://strut.alpha.example.com")
    private val b = BoxFixture.box("https://strut.bee.example.com")
    private val tribe = TribePlace(PlacesStrut.TRIBE, PlacesStrut.LINK, "Builders")
    private val sleeps = mutableListOf<Long>()

    private fun jamies(vararg boxes: Pair<BoxLink, PlacesStrut>): BoxJamies = boxJamies(*boxes, sleeps = sleeps)

    private fun options(jamies: BoxJamies) = TribeJamie.options(jamies.boxes(), tribe.key, "No Jamie").map { it.name }

    // What the row shows

    /// Each box whose Sphinx host is ready, by name, then No Jamie; a box with no host ready is not offered; the pick is the box the
    /// person linked the tribe on, "" for none.
    @Test fun `the boxes ready are offered, and the one linked is picked`() = runBlocking {
        val strutA = PlacesStrut(a)
        val strutB = PlacesStrut(b, jamie = null)
        val jamies = jamies(a to strutA, b to strutB)
        // Not read yet: no row, nothing picked.
        assertFalse(TribeJamie.shows(jamies.boxes(), tribe.key))
        assertNull(TribeJamie.pick(jamies.boxes(), tribe.key))

        jamies.read()
        assertTrue(TribeJamie.shows(jamies.boxes(), tribe.key))
        assertEquals(listOf("alpha", "No Jamie"), options(jamies))
        assertEquals(TribeJamie.NONE, TribeJamie.pick(jamies.boxes(), tribe.key))
        assertEquals(listOf("" to "No Jamie"), TribeJamie.options(jamies.boxes(), tribe.key, "No Jamie").drop(1).map { it.id to it.name })

        strutA.linkAs(PlacesStrut.ADA, tribe.key)
        jamies.read()
        assertEquals(a.url, TribeJamie.pick(jamies.boxes(), tribe.key))
        assertEquals(listOf(PlacesStrut.JAMIE), TribeJamie.jamies(jamies.boxes(), tribe.key))
        // Another tribe on the same boxes is not linked.
        assertEquals(TribeJamie.NONE, TribeJamie.pick(jamies.boxes(), "02" + "00".repeat(32)))
    }

    /// No box with a Sphinx host ready: no row at all. A box the tribe is linked on is offered even when its host isn't ready now, so
    /// the row says what is true.
    @Test fun `no row without a box ready`() = runBlocking {
        val strutA = PlacesStrut(a, jamie = null)
        val jamies = jamies(a to strutA)
        jamies.read()
        assertFalse(TribeJamie.shows(jamies.boxes(), tribe.key))
        assertTrue(options(jamies).isEmpty())

        strutA.linkAs(PlacesStrut.ADA, tribe.key)
        jamies.read()
        assertTrue(TribeJamie.shows(jamies.boxes(), tribe.key))
        assertEquals(listOf("alpha", "No Jamie"), options(jamies))
        assertEquals(a.url, TribeJamie.pick(jamies.boxes(), tribe.key))
    }

    /// A box that can't be read is said, and not offered; the row shows (it may answer here); nothing is picked until every box
    /// has answered, unless one that did names the tribe.
    @Test fun `a box that can't be read`() = runBlocking {
        val strutA = PlacesStrut(a)
        val broken = PlacesStrut(b).apply { jamie = PlacesStrut.JAMIE }
        val down = FakeBox { _, _, _, _ -> BoxResponse(500, """{"error":"down"}""") }
        val jamies = jamies(a to strutA, b to broken)
        val failing = BoxJamies(
            BoxesModel(clientFor = { BoxClient(it, down) }, dispatcher = Dispatchers.Unconfined, now = { BoxFixture.now }, sleep = { awaitCancellation() })
                .apply { open(MemoryBoxStore().also { BoxLink.write(listOf(b), it) }) },
            clientFor = { PlacesClient(it, down) }, dispatcher = Dispatchers.Unconfined,
        )
        failing.read()
        val only = failing.boxes().single()
        assertEquals("Could not read which tribes have Jamie on bee. Bee could not read its tribes: down.", only.problem)
        assertTrue(TribeJamie.shows(failing.boxes(), tribe.key))
        assertNull(TribeJamie.pick(failing.boxes(), tribe.key))
        assertTrue(TribeJamie.options(failing.boxes(), tribe.key, "No Jamie").isEmpty())

        // One box read, one not: the read one is offered; the tribe linked on it is picked.
        strutA.linkAs(PlacesStrut.ADA, tribe.key)
        jamies.read()
        val mixed = jamies.boxes().map { if (it.box.url == b.url) it.copy(read = BoxJamies.Read.Failed("down", 0)) else it }
        assertEquals(listOf("alpha", "No Jamie"), TribeJamie.options(mixed, tribe.key, "No Jamie").map { it.name })
        assertEquals(a.url, TribeJamie.pick(mixed, tribe.key))
    }

    // Picking

    /// A box picked: the tribe is posted to it, and the row reads it picked at once; another box picked moves it there, taken off
    /// the first; No Jamie takes it off.
    @Test fun `a pick links the tribe on one box and takes it off the others`() = runBlocking {
        val strutA = PlacesStrut(a)
        val strutB = PlacesStrut(b)
        val jamies = jamies(a to strutA, b to strutB)
        jamies.read()

        jamies.pick(a.url, tribe)
        assertNull(jamies.problems[tribe.key])
        assertEquals(PlacesStrut.ADA, strutA.linkers[tribe.key])
        assertEquals(a.url, TribeJamie.pick(jamies.boxes(), tribe.key))
        assertEquals("Builders", JSONObject(strutA.http.calls.last { it.method == "POST" }.json!!).getString("name"))

        jamies.pick(b.url, tribe)
        assertEquals(PlacesStrut.ADA, strutB.linkers[tribe.key])
        assertFalse(tribe.key in strutA.linkers)
        assertEquals(b.url, TribeJamie.pick(jamies.boxes(), tribe.key))
        // The box it moved to is linked first, the other unlinked after.
        assertTrue(strutB.http.calls.any { it.method == "POST" } && strutA.http.calls.last().method == "DELETE")

        jamies.pick(TribeJamie.NONE, tribe)
        assertTrue(strutB.linkers.isEmpty())
        assertEquals(TribeJamie.NONE, TribeJamie.pick(jamies.boxes(), tribe.key))
        assertTrue(jamies.working.isEmpty())

        // What is picked already is not sent again.
        val calls = strutA.http.calls.size + strutB.http.calls.size
        jamies.pick(TribeJamie.NONE, tribe)
        assertEquals(calls, strutA.http.calls.size + strutB.http.calls.size)
    }

    /// A refusal is said in the row, and changes nothing: another person's place, a box not ready, a removal that failed.
    @Test fun `a pick's refusal is said`() = runBlocking {
        val strutA = PlacesStrut(a).apply { linkAs("02" + "cd".repeat(32), PlacesStrut.TRIBE) }
        val jamies = jamies(a to strutA)
        jamies.read()
        jamies.pick(a.url, tribe)
        assertEquals(
            "Could not add Jamie from alpha. Someone else on alpha already added Jamie to this tribe.",
            jamies.problems[tribe.key],
        )
        assertEquals(TribeJamie.NONE, TribeJamie.pick(jamies.boxes(), tribe.key))

        strutA.linkers.clear()
        strutA.jamie = null
        jamies.pick(a.url, tribe)
        assertEquals("Could not add Jamie from alpha. Alpha isn't ready for tribes yet.", jamies.problems[tribe.key])

        // A pick that goes through clears it.
        strutA.jamie = PlacesStrut.JAMIE
        jamies.pick(a.url, tribe)
        assertNull(jamies.problems[tribe.key])
    }

    /// A token the box refused is renewed once (by signing again) and the call made again with the new one.
    @Test fun `a refused token is renewed once`() = runBlocking {
        val strutA = PlacesStrut(a, token = "strut-person.other")
        val jamies = jamies(a to strutA)
        jamies.read()
        assertEquals(PlacesStrut.JAMIE, jamies.boxes().single().places?.jamie)
        assertEquals(listOf(a.token, null, null, "strut-person.renewed"), strutA.http.calls.map { it.bearer })
        jamies.pick(a.url, tribe)
        assertEquals("strut-person.renewed", strutA.http.calls.last().bearer)
        assertEquals(PlacesStrut.ADA, strutA.linkers[tribe.key])
    }

    // Approving Jamie

    /// A join request in a tribe the person linked on a box: that box's Jamie approved by its key, once a run; the server may list
    /// it a moment late, so it is asked up to three times. A tribe linked on no box approves nobody.
    @Test fun `a box's Jamie is approved by its key`() = runBlocking {
        val strutA = PlacesStrut(a)
        val jamies = jamies(a to strutA)
        val asked = mutableListOf<Pair<String, String>>()
        var waiting = false
        val approver = JamieApprover { t, key -> asked += t to key; waiting }

        jamies.requested(tribe.key, approver)
        assertTrue("not linked: nobody asked for", asked.isEmpty())

        strutA.linkAs(PlacesStrut.ADA, tribe.key)
        jamies.requested(tribe.key, approver)
        assertEquals(List(3) { tribe.key to PlacesStrut.JAMIE }, asked)
        assertEquals(listOf(5_000L, 5_000L), sleeps)

        asked.clear()
        waiting = true
        jamies.requested(tribe.key, approver)
        assertEquals(listOf(tribe.key to PlacesStrut.JAMIE), asked)
        // Approved: not again this run, which would cost the tribe's price again.
        jamies.requested(tribe.key, approver)
        assertEquals(1, asked.size)
    }

    /// An approval that fails is said in the row; the request stays in Members to approve by hand.
    @Test fun `an approval that fails is said`() = runBlocking {
        val strutA = PlacesStrut(a).apply { linkAs(PlacesStrut.ADA, PlacesStrut.TRIBE) }
        val jamies = jamies(a to strutA)
        jamies.requested(tribe.key) { _, _ -> throw TribeRefused("The tribes server did not answer. Try again in a moment.") }
        assertEquals("Could not approve Jamie. The tribes server did not answer. Try again in a moment.", jamies.problems[tribe.key])
    }

    /// The member waiting is found by its key on the server's list, with the stored request it answers (by key, else by alias); a
    /// request alone, naming no key, is never approved as Jamie. The answer names the member by key.
    @Test fun `a waiting member is approved by key`() {
        val jamie = PlacesStrut.JAMIE
        val listed = TribeMember(jamie, "Jamie", null, pending = true)
        val request = TribeMember("", "Jamie", null, pending = true, requestUuid = "r1")
        val found = TribeMembers.waiting(listOf(listed, request), jamie.uppercase())!!
        assertEquals("r1", found.requestUuid)
        assertEquals(jamie, found.pubkey)
        val body = JSONObject(TribeMembers.answerBody(found))
        assertEquals(jamie, body.getString("member"))
        assertEquals("r1", body.getString("replyUuid"))

        // A request under another alias is not taken for it; the answer still names the member.
        val other = TribeMembers.waiting(listOf(listed, request.copy(alias = "Ada")), jamie)!!
        assertNull(other.requestUuid)
        assertEquals(jamie, JSONObject(TribeMembers.answerBody(other)).getString("member"))
        // Merged already (the request named the key): kept as it is.
        assertEquals("r2", TribeMembers.waiting(listOf(listed.copy(requestUuid = "r2")), jamie)!!.requestUuid)
        // Not listed waiting, or confirmed already: nobody.
        assertNull(TribeMembers.waiting(listOf(request), jamie))
        assertNull(TribeMembers.waiting(listOf(listed.copy(pending = false)), jamie))
        // An answer for a member whose key is not one names no member.
        assertFalse(JSONObject(TribeMembers.answerBody(request)).has("member"))
    }
}
