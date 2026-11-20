package com.sphinx.peel.engine

import com.sphinx.peel.account.FakeWall
import com.sphinx.peel.account.KeystoreBoxStore
import com.sphinx.peel.account.KeystoreHiveStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
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

/// Peel linked to a strut box (strut's plans/people-and-hosts.md §2.1, specs/API.md §13.6; docs/phases/45-box-link.md), ported from
/// iOS `BoxLinkTests` (PR #574): the `peel://strut/link` a box's Connect shows, what Peel will and will not sign, the vector strut
/// checks, the box list kept beside Hive's pairing, and strut's three routes on a stand-in. The engine's own signature over the
/// vector needs the native core: `BoxLinkSigningInstrumentedTest`.
@RunWith(RobolectricTestRunner::class)
class BoxLinkTest {
    private fun challenge(host: String, actor: String? = "ada-3f9a", name: String? = "Ada Lovelace", exp: Long = BoxFixture.NOW_MS + 600_000) =
        BoxFixture.challenge(host, actor, name, exp)

    /// The link strut's `accountsCapability` makes: both values with `encodeURIComponent`, which leaves `-_.!~*'()` as they are.
    private fun link(base: String, challenge: String): String {
        fun encoded(text: String) = text.toByteArray(Charsets.UTF_8).joinToString("") { b ->
            val c = b.toInt().toChar()
            if (b >= 0 && (c.isLetterOrDigit() || c in "-_.!~*'()")) "$c" else "%%%02X".format(b.toInt() and 0xff)
        }
        return "peel://strut/link?url=${encoded(base)}&challenge=${encoded(challenge)}"
    }

    private val signed = VisitorSignature("02ab", "1f")

    // The link

    /// A box's link reads back as its box, with a mount path or a port, and the challenge's claims; the question names the box and
    /// the person.
    @Test fun `a box's link reads as strut writes it`() {
        for ((base, box, host) in listOf(
            Triple("https://box.example.com", "https://box.example.com", "box.example.com"),
            Triple("https://box.example.com/lab", "https://box.example.com/lab", "box.example.com"),
            Triple("https://Box.Example.com:8443/", "https://box.example.com:8443", "box.example.com"),
        )) {
            val offer = BoxLinkOffer.parse(link(base, challenge(host)))
            assertNotNull(base, offer)
            assertEquals(box, offer!!.box)
            assertEquals(host, offer.host)
            assertEquals("ada-3f9a", offer.challenge.actor)
            assertEquals("Ada Lovelace", offer.challenge.name)
            assertEquals(BoxFixture.NOW_MS + 600_000, offer.challenge.expiresAtMs)
            assertEquals("Ada Lovelace", offer.challenge.person)
            assertNull(offer.problem(BoxFixture.NOW_MS, allowsLoopback = false))
            assertEquals("Link Peel to $host as Ada Lovelace?", BoxWords.title(offer))
        }
        // The name, else the actor, else "you".
        val actorOnly = BoxLinkOffer.of("https://box.example.com", challenge("box.example.com", name = null))!!
        assertEquals("Link Peel to box.example.com as ada-3f9a?", BoxWords.title(actorOnly))
    }

    /// Only `peel://strut/link?url=…&challenge=…` is a box's link, and only with a box Peel would post to and a link challenge to sign.
    @Test fun `what is not a box's link`() {
        val encoded = link("x", challenge("box.example.com")).substringAfter("challenge=")
        for (text in listOf(
            "peel://open?url=https%3A%2F%2Fbox.example.com&challenge=C",
            "peel://strut/other?url=https%3A%2F%2Fbox.example.com&challenge=C",
            "peel://strut?url=https%3A%2F%2Fbox.example.com&challenge=C",
            "https://strut/link?url=https%3A%2F%2Fbox.example.com&challenge=C",
            "peel://strut/link?url=https%3A%2F%2Fbox.example.com",
            "peel://strut/link?url=https%3A%2F%2Fbox.example.com&challenge=C&extra=1",
            "peel://strut/link?url=https%3A%2F%2Fbox.example.com&challenge=C&url=https%3A%2F%2Fbox.example.com",
            "peel://strut/link?url=https%3A%2F%2Fbox.example.com&challenge=C#frag",
            "peel://strut/link?url=https%3A%2F%2Fbox.example.com&challenge=C#",
            "peel://u@strut/link?url=https%3A%2F%2Fbox.example.com&challenge=C",
            "peel://strut:9/link?url=https%3A%2F%2Fbox.example.com&challenge=C",
            "peel://strut/link?url=http%3A%2F%2Fbox.example.com&challenge=C",
            "peel://strut/link?url=https%3A%2F%2Fu%3Ap%40box.example.com&challenge=C",
            "peel://strut/link?url=ftp%3A%2F%2Fbox.example.com&challenge=C",
            "peel://strut/link?url=https%3A%2F%2Fbox.example.com%ZZ&challenge=C",
            "peel://strut/link?url=https%3A%2F%2Fbox.example.com&challenge=strut-visitor.eyJ9.abc",
            "peel://strut/link?url=https%3A%2F%2Fbox.example.com&challenge=strut-link.notbase64!.abc",
        )) {
            assertNull(text, BoxLinkOffer.parse(text.replace("challenge=C", "challenge=$encoded")))
        }
        // And the same link, whole, is one: the list above is not refused for its challenge.
        assertNotNull(BoxLinkOffer.parse("peel://strut/link?url=https%3A%2F%2Fbox.example.com&challenge=$encoded"))
        assertNotNull(BoxLinkOffer.parse("PEEL://STRUT/link?challenge=$encoded&url=https%3A%2F%2Fbox.example.com"))
    }

    /// A challenge's claims as strut reads them: an actor or a name that is there is a string; an end and a nonce are needed.
    @Test fun `a challenge reads only as strut writes one`() {
        fun of(claims: String) = BoxChallenge.parse(
            BoxChallenge.PREFIX + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(claims.toByteArray()) + ".c2ln",
        )
        assertNotNull(of("""{"host":"Box.Example.com","nonce":"n","exp":1795000600000.0}"""))
        assertEquals("box.example.com", of("""{"host":"Box.Example.com","nonce":"n","exp":1}""")!!.host)
        assertNull(of("""{"host":"b","nonce":"n","exp":1,"actor":7}"""))
        assertNull(of("""{"host":"b","nonce":"n","exp":1,"name":null}"""))
        assertNull(of("""{"host":"b","nonce":"n","exp":0}"""))
        assertNull(of("""{"host":"b","exp":1}"""))
        assertNull(of("""{"nonce":"n","exp":1}"""))
        assertNull(of("""{"host":"b","nonce":"n","exp":1.5}"""))
        assertNull(of("""[1]"""))
        assertNull(BoxChallenge.parse(challenge("b") + "x".repeat(BoxChallenge.MAX_LENGTH)))
        // An empty actor or name is none.
        val blank = of("""{"host":"b","nonce":"n","exp":1,"actor":"","name":""}""")!!
        assertNull(blank.actor)
        assertNull(blank.person)
    }

    /// What Peel will not sign, though it reads: another host's challenge, one run out, a renewal's (naming nobody), and this
    /// device's address outside a debug build.
    @Test fun `what Peel will not sign`() {
        fun offer(base: String, challenge: String) = BoxLinkOffer.of(base, challenge)!!
        val other = offer("https://box.example.com", challenge("evil.example.com"))
        assertTrue(other.problem(BoxFixture.NOW_MS, allowsLoopback = true)!!.contains("not box.example.com"))
        val ended = offer("https://box.example.com", challenge("box.example.com", exp = BoxFixture.NOW_MS - 1))
        assertTrue(ended.problem(BoxFixture.NOW_MS, allowsLoopback = true)!!.contains("ran out"))
        val nobody = offer("https://box.example.com", challenge("box.example.com", actor = null, name = null))
        assertTrue(nobody.problem(BoxFixture.NOW_MS, allowsLoopback = true)!!.contains("doesn't name anyone"))
        for (local in listOf("http://127.0.0.1:3400", "https://localhost:3400", "http://[::1]:3400")) {
            val host = Seller.host(local)!!
            val o = offer(local, challenge(host))
            assertTrue(local, o.problem(BoxFixture.NOW_MS, allowsLoopback = false)!!.contains("https"))
            assertNull(local, o.problem(BoxFixture.NOW_MS, allowsLoopback = true))
        }
        // An emulator's way to its host (10.0.2.2) is not this device: https only, as any other address.
        assertNull(BoxLinkOffer.of("http://10.0.2.2:3400", challenge("10.0.2.2")))
    }

    // The signature

    /// iOS's vector applies to Android: the challenge Android's fixture builds for box.example.com is the vector's text, byte for
    /// byte, and reads as iOS reads it. `BoxLinkSigningInstrumentedTest` has the engine (the native core) sign it to the vector's
    /// key and signature, which strut's `ln-message` check takes.
    @Test fun `the vector's challenge is the one Android reads`() {
        assertEquals(BoxFixture.VECTOR_TEXT, challenge("box.example.com"))
        val c = BoxChallenge.parse(BoxFixture.VECTOR_TEXT)!!
        assertEquals("box.example.com", c.host)
        assertEquals("ada-3f9a", c.actor)
        assertEquals(1_795_000_600_000L, c.expiresAtMs)
        assertEquals(66, BoxFixture.VECTOR_KEY.length)
        assertEquals(130, BoxFixture.VECTOR_SIG.length)
    }

    // The box list

    /// The boxes are kept in their own Keystore-wrapped file beside Hive's pairing, which they never touch, several at once; none
    /// kept reads as none, the last one forgotten removes the file, and what cannot be read throws rather than reading as none.
    @Test fun `the boxes are kept beside Hive's pairing`() {
        val folder = Files.createTempDirectory("boxes").toFile()
        try {
            val hiveFile = File(folder, "hive.default.v1")
            val hive = KeystoreHiveStore(hiveFile, FakeWall(), "peel.hive.default".toByteArray())
            hive.write("""{"hive":"pairing"}""")
            val hiveBytes = hiveFile.readBytes()
            val boxFile = File(folder, "boxes.default.v1")
            val store = KeystoreBoxStore(boxFile, FakeWall(), "peel.boxes.default".toByteArray())
            assertTrue(BoxLink.read(store).isEmpty())
            val boxes = listOf(BoxFixture.box("https://one.example.com"), BoxFixture.box("https://two.example.com/lab"))
            BoxLink.write(boxes, store)
            assertEquals(boxes, BoxLink.read(store))
            assertTrue(boxFile.exists())
            // Wrapped: no token in the file.
            assertFalse(String(boxFile.readBytes(), Charsets.ISO_8859_1).contains("strut-person"))
            assertTrue(hiveFile.readBytes().contentEquals(hiveBytes))
            assertEquals("""{"hive":"pairing"}""", hive.read())
            BoxLink.write(emptyList(), store)
            assertNull(store.read())
            assertFalse(boxFile.exists())
            assertTrue(hiveFile.readBytes().contentEquals(hiveBytes))

            val memory = MemoryBoxStore("""{"boxes":[{"url":"https://a.example.com"}]}""")
            try { BoxLink.read(memory); fail("read what could not be read") } catch (e: BoxException) {}
        } finally {
            folder.deleteRecursively()
        }
    }

    /// A box link is never described with its token, nor its page, nor an answer.
    @Test fun `a box link is never described with its token`() {
        val box = BoxFixture.box("https://one.example.com")
        for (text in listOf("$box", "${box.home}", "${box.home.request}", "${BoxClient.Answer("a", null, box.token, BoxFixture.now)}")) {
            assertFalse(text, text.contains(box.token))
        }
    }

    /// Due a day after the last renewal, or within a day of the token's end.
    @Test fun `a box is due daily and before its token ends`() {
        val now = BoxFixture.now
        assertFalse(BoxFixture.box("https://a.example.com").due(now.plusSeconds(3600)))
        assertTrue(BoxFixture.box("https://a.example.com").due(now.plusSeconds(86_400)))
        assertTrue(BoxFixture.box("https://a.example.com", exp = now.plusSeconds(80_000)).due(now.plusSeconds(60)))
    }

    /// The box's page: its own strut UI's folder, the token as the first request's bearer and handed to the page's storage, never in
    /// the address; loaded again where it went (Reload) with the bearer still (the Mac's review found a reload dropping it).
    @Test fun `a box opens as its strut UI with the token`() {
        val box = BoxFixture.box("https://box.example.com/lab")
        val page = box.home
        assertEquals("https://box.example.com/lab/", page.url)
        assertEquals("box:https://box.example.com/lab", page.peer)
        assertEquals("box.example.com", page.workspace)
        assertEquals(mapOf("Authorization" to "Bearer ${box.token}"), page.request.headers)
        assertFalse(page.url.contains(box.token))
        assertTrue(page.script.contains(box.token) && page.script.contains("strut/apiKey"))
        assertEquals(WalletScope.Org("box:https://box.example.com/lab"), page.walletScope)
        assertTrue(page.serves("https://box.example.com/lab/apps"))
        assertFalse(page.serves("https://box.example.com/other/"))
        val reload = page.request("https://box.example.com/lab/?app=board&solo=1")
        assertEquals(mapOf("Authorization" to "Bearer ${box.token}"), reload?.headers)
        assertNull(page.request("https://elsewhere.example.com/lab/"))
    }

    // strut's routes, on a stand-in

    /// Linking posts the challenge, the account's key and signature, its name and address, with no other credential, under the box's
    /// mount path; the answer is the link kept.
    @Test fun `linking posts the signed challenge and keeps the answer`() = runBlocking {
        val box = FakeBox { _, _, _, _ -> BoxResponse(200, BoxFixture.answer()) }
        val base = Seller.base("http://127.0.0.1:3400/lab")!!
        val challenge = BoxChallenge.parse(challenge("127.0.0.1"))!!
        val key = "02" + "ab".repeat(32)
        val address = key + "_03" + "cd".repeat(32) + "_7"
        val signed = VisitorSignature(key, "1f".repeat(65), address)
        val link = BoxClient(base, box).link(challenge, signed, "Ada\non Sphinx", BoxFixture.now)
        assertEquals(base, link.url)
        assertEquals("ada-3f9a", link.actor)
        assertEquals("Ada Lovelace", link.name)
        assertEquals("strut-person.eyJ.abc", link.token)
        assertEquals(BoxFixture.now.plusSeconds(7 * 86_400), link.exp)
        assertEquals(BoxFixture.now, link.linkedAt)
        assertEquals(BoxFixture.now, link.renewedAt)
        val call = box.calls.single()
        assertEquals("POST", call.method)
        assertEquals("http://127.0.0.1:3400/lab/people/link", call.url)
        assertNull(call.bearer)
        val body = JSONObject(call.json!!)
        assertEquals(setOf("challenge", "key", "sig", "name", "address"), body.keys().asSequence().toSet())
        assertEquals(challenge.text, body.getString("challenge"))
        assertEquals(key, body.getString("key"))
        assertEquals(signed.sig, body.getString("sig"))
        assertEquals("Ada on Sphinx", body.getString("name"))
        assertEquals(address, body.getString("address"))
        // No name, no address: neither is sent.
        BoxClient(base, box).link(challenge, VisitorSignature(key, "1f"), "  ", BoxFixture.now)
        assertEquals(setOf("challenge", "key", "sig"), JSONObject(box.calls.last().json!!).keys().asSequence().toSet())
    }

    /// strut's refusals of a link, in words the person can act on; an answer that is not a person token is not kept.
    @Test fun `a refused link is said plainly`() = runBlocking {
        for ((status, body, words) in listOf(
            Triple(401, """{"error":"unauthorized: the challenge was already used"}""", "ran out or was already used"),
            Triple(409, """{"error":"linked_elsewhere: sphinx:02ab is linked to another person here"}""", "linked to someone else"),
            Triple(413, """{"error":"too large"}""", "too large"),
            Triple(429, """{"error":"slow down"}""", "busy"),
            Triple(400, """{"error":"name is one line of at most 80 characters"}""", "name is one line"),
            Triple(500, "<html>", "HTTP 500"),
            Triple(200, """{"actor":"ada","token":"strut-visitor.x.y","exp":1795600000000}""", "could not be read"),
            Triple(200, """{"actor":"ada","token":"strut-person.x y","exp":1795600000000}""", "could not be read"),
            Triple(200, """{"actor":"","token":"strut-person.x.y","exp":1795600000000}""", "could not be read"),
            Triple(200, """{"actor":"ada","token":"strut-person.x.y"}""", "could not be read"),
            Triple(200, """{"actor":"ada","token":"strut-person.x.y","exp":1}""", "already run out"),
        )) {
            val box = FakeBox { _, _, _, _ -> BoxResponse(status, body) }
            val challenge = BoxChallenge.parse(challenge("127.0.0.1"))!!
            try {
                BoxClient("http://127.0.0.1:3400", box).link(challenge, signed, null, BoxFixture.now)
                fail("$status $body was kept")
            } catch (e: BoxException) {
                assertTrue("${e.message}", e.message!!.contains(words))
            }
        }
    }

    /// Renewing asks the box for a fresh challenge, signs it only when it is for the box's own host, and keeps the new token; a key
    /// the member unlinked (`403 not_linked`) is [BoxClient.Renewal.Unlinked].
    @Test fun `renewing signs a fresh challenge for the box alone`() = runBlocking {
        val fresh = challenge("127.0.0.1", actor = null, name = null)
        var unlinked = false
        val box = FakeBox { method, url, _, _ ->
            when {
                method == "GET" && url.endsWith("/people/challenge") -> BoxResponse(200, """{"challenge":"$fresh","exp":${BoxFixture.NOW_MS + 60_000}}""")
                unlinked -> BoxResponse(403, """{"error":"not_linked: sphinx:02ab is not linked to a person here"}""")
                else -> BoxResponse(200, BoxFixture.answer(token = "strut-person.renewed"))
            }
        }
        val kept = BoxFixture.box("http://127.0.0.1:3400", renewedAt = BoxFixture.now.minusSeconds(90_000))
        val signedFor = mutableListOf<String>()
        val sign: suspend (BoxChallenge) -> VisitorSignature = { signedFor += it.text; signed }
        val later = BoxFixture.now.plusSeconds(10)
        val renewal = BoxClient(kept.url, box).renew(kept, later, sign) as BoxClient.Renewal.Renewed
        assertEquals("strut-person.renewed", renewal.link.token)
        assertEquals(later, renewal.link.renewedAt)
        assertEquals(kept.linkedAt, renewal.link.linkedAt)
        assertEquals(listOf(fresh), signedFor)
        val posted = box.calls.last()
        assertEquals("POST", posted.method)
        assertEquals("http://127.0.0.1:3400/people/token", posted.url)
        assertNull(posted.bearer)
        val body = JSONObject(posted.json!!)
        assertEquals(fresh, body.getString("challenge"))
        assertEquals("02ab", body.getString("key"))
        assertEquals("1f", body.getString("sig"))
        assertEquals(3, body.length())
        unlinked = true
        assertEquals(BoxClient.Renewal.Unlinked, BoxClient(kept.url, box).renew(kept, later, sign))
    }

    /// A renewal challenge for another host is never signed, nor one that is not a box's challenge.
    @Test fun `a renewal challenge for another host is never signed`() = runBlocking {
        for (answer in listOf(
            """{"challenge":"${challenge("evil.example.com", actor = null, name = null)}","exp":1}""",
            """{"challenge":"strut-visitor.eyJ9.abc","exp":1}""",
            """{"exp":1}""",
        )) {
            val box = FakeBox { _, _, _, _ -> BoxResponse(200, answer) }
            var signs = 0
            try {
                BoxClient("http://127.0.0.1:3400", box).renew(BoxFixture.box("http://127.0.0.1:3400"), BoxFixture.now) { signs++; signed }
                fail("renewed on $answer")
            } catch (e: BoxException) {}
            assertEquals(0, signs)
            assertEquals(1, box.calls.size)
        }
    }

    /// The token's check on use: `GET /apps` with it as the bearer; 401 is "no longer held", anything else not 200 is a failure.
    @Test fun `a token is checked by reading the box's apps`() = runBlocking {
        val kept = BoxFixture.box("https://box.example.com/lab")
        for ((status, held) in listOf(200 to true, 401 to false)) {
            val box = FakeBox { _, _, _, _ -> BoxResponse(status, "{}") }
            assertEquals(held, BoxClient(kept.url, box).accepts(kept))
            val call = box.calls.single()
            assertEquals("GET", call.method)
            assertEquals("https://box.example.com/lab/apps", call.url)
            assertEquals(kept.token, call.bearer)
        }
        try {
            BoxClient(kept.url, FakeBox { _, _, _, _ -> BoxResponse(502, "") }).accepts(kept)
            fail("a 502 read as an answer")
        } catch (e: BoxException) {
            assertTrue(e.message!!.contains("HTTP 502"))
        }
    }

    /// The one reader of `peel://` links' values: each name once, nothing else, decoded.
    @Test fun `a peel link's values are read strictly`() {
        assertEquals(mapOf("a" to "x y", "b" to "+/"), PeelLink.values("peel://h/p?a=x%20y&b=+%2F", "h", "/p", setOf("a", "b")))
        assertEquals(mapOf("a" to ""), PeelLink.values("peel://h?a=", "h", names = setOf("a")))
        assertEquals(mapOf("a" to "1"), PeelLink.values("peel://h/?a=1", "h", names = setOf("a")))
        assertNull(PeelLink.values("peel://h?a", "h", names = setOf("a")))
        assertNull(PeelLink.values("peel://h?a=1&&", "h", names = setOf("a")))
        assertNull(PeelLink.values("peel://h", "h", names = setOf("a")))
        assertNull(PeelLink.values("peel://h/p?a=1", "h", names = setOf("a")))
    }
}
