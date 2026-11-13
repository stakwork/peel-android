package com.sphinx.peel.engine

import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/// A challenge with these claims (the HMAC is never checked, so any text will do).
internal fun challengeOf(claims: String, mac: String = "c2ln"): String =
    "strut-visitor." + Base64.getUrlEncoder().withoutPadding().encodeToString(claims.toByteArray(Charsets.UTF_8)) + "." + mac

internal fun claims(host: String = "shop.example.com", key: String = "root", exp: Long = 1793800060000, nonce: String = "n0nce") =
    """{"host":"$host","visitorKey":"$key","nonce":"$nonce","exp":$exp}"""

/// strut's vectors (specs/WALLET.md).
internal const val ROOT_CHALLENGE =
    "strut-visitor.eyJob3N0Ijoic2hvcC5leGFtcGxlLmNvbSIsInZpc2l0b3JLZXkiOiJyb290Iiwibm9uY2UiOiI2ZjBjM2E5ZDJiN2U0MWM1OGExZjBlOWQzYzJiN2E2NCIsImV4cCI6MTc5MzgwMDA2MDAwMH0.qYzkz2ZbLMhHogJxEF9YQob8BCwvg99JgWymZCESoHA"
internal const val LOCALHOST_CHALLENGE =
    "strut-visitor.eyJob3N0IjoibG9jYWxob3N0IiwidmlzaXRvcktleSI6InJvb3QiLCJub25jZSI6IjJhM2I0YzVkNmU3ZjgwOTEyYTNiNGM1ZDZlN2Y4MDkxIiwiZXhwIjoxNzkzODAwMDYwMDAwfQ.oBSPrN2kJQGK5eCuIqFRDJ4NBVfhSF_9u3NbybuiGSs"
internal const val EVIL_CHALLENGE =
    "strut-visitor.eyJob3N0IjoiZXZpbC5leGFtcGxlLm5ldCIsInZpc2l0b3JLZXkiOiJyb290Iiwibm9uY2UiOiIwZDljOGI3YTZmNWU0ZDNjMmIxYTA5ZjhlN2Q2YzViNCIsImV4cCI6MTc5MzgwMDA2MDAwMH0.2QSE0rN8_1YVixhXM6gDF1iyc9nQxkugHGQNwotnkBo"
internal const val NOW = 1793800000000L

@RunWith(RobolectricTestRunner::class)
class VisitorChallengeTest {
    @Test fun validRoot() {
        val c = VisitorChallenge.parse(ROOT_CHALLENGE)!!
        assertEquals(ROOT_CHALLENGE, c.text)
        assertEquals("shop.example.com", c.host)
        assertEquals(SignInKey.root, c.visitorKey)
        assertEquals(1793800060000L, c.expiresAtMs)
        assertEquals("6f0c3a9d2b7e41c58a1f0e9d3c2b7a64", c.nonce)
    }

    @Test fun validDomain() {
        assertEquals(SignInKey.domain, VisitorChallenge.parse(challengeOf(claims(key = "domain")))!!.visitorKey)
    }

    @Test fun tooLong() {
        assertNull(VisitorChallenge.parse(challengeOf(claims(nonce = "x".repeat(2000)))))
    }

    @Test fun badShapes() {
        assertNull(VisitorChallenge.parse(ROOT_CHALLENGE.removePrefix("strut-")))
        assertNull(VisitorChallenge.parse("$ROOT_CHALLENGE.extra"))
        assertNull(VisitorChallenge.parse("strut-visitor.abc"))
        assertNull(VisitorChallenge.parse("strut-visitor..abc"))
        assertNull(VisitorChallenge.parse(ROOT_CHALLENGE.replace("qYzk", "q+zk")))
        assertNull(VisitorChallenge.parse(challengeOf("not json")))
        assertNull(VisitorChallenge.parse(challengeOf("[1]")))
    }

    @Test fun expIsReadAsAnInteger() {
        fun raw(exp: String) = challengeOf("""{"host":"h","visitorKey":"root","nonce":"n","exp":$exp}""")
        assertEquals(1793800060000L, VisitorChallenge.parse(raw("1793800060000.0"))!!.expiresAtMs)
        assertNull(VisitorChallenge.parse(raw("\"1793800060000\"")))
        assertNull(VisitorChallenge.parse(raw("0")))
        assertNull(VisitorChallenge.parse(raw("9007199254740992")))
        assertNotNull(VisitorChallenge.parse(raw("9007199254740991")))
        assertNull(VisitorChallenge.parse(raw("true")))
        assertNull(VisitorChallenge.parse(raw("null")))
    }

    @Test fun badClaims() {
        assertNull(VisitorChallenge.parse(challengeOf("""{"visitorKey":"root","nonce":"n","exp":5}""")))
        assertNull(VisitorChallenge.parse(challengeOf(claims(host = ""))))
        assertNull(VisitorChallenge.parse(challengeOf(claims(key = "child"))))
        assertNull(VisitorChallenge.parse(challengeOf(claims(exp = 0))))
        assertNull(VisitorChallenge.parse(challengeOf("""{"host":"h","visitorKey":"root","nonce":"n","exp":1.5}""")))
        assertNull(VisitorChallenge.parse(challengeOf("""{"host":"h","visitorKey":"root","nonce":"","exp":5}""")))
    }
}

@RunWith(RobolectricTestRunner::class)
class SellerTest {
    @Test fun sellerUrls() {
        assertTrue(Seller.isSellerURL("https://shop.example.com"))
        assertTrue(Seller.isSellerURL("https://shop.example.com/lab"))
        assertTrue(Seller.isSellerURL("http://localhost:3000"))
        assertTrue(Seller.isSellerURL("http://app.localhost"))
        assertTrue(Seller.isSellerURL("http://127.0.0.1:8080"))
        assertTrue(Seller.isSellerURL("http://[::1]:3000"))
        assertFalse(Seller.isSellerURL("http://shop.example.com"))
        assertFalse(Seller.isSellerURL("http://127.0.0"))
        assertFalse(Seller.isSellerURL("http://127.0.0.256"))
        assertFalse(Seller.isSellerURL("https://u:p@shop.example.com"))
        assertFalse(Seller.isSellerURL("https://u@shop.example.com"))
        assertFalse(Seller.isSellerURL("https://shop.example.com?x=1"))
        assertFalse(Seller.isSellerURL("https://shop.example.com#top"))
        assertFalse(Seller.isSellerURL("ftp://shop.example.com"))
        assertFalse(Seller.isSellerURL("shop.example.com"))
        assertFalse(Seller.isSellerURL("https://"))
    }

    @Test fun hosts() {
        assertEquals("shop.example.com", Seller.host("https://Shop.Example.com:8443/x"))
        assertEquals("::1", Seller.host("http://[::1]:3000"))
        assertNull(Seller.host("nonsense"))
    }

    @Test fun bases() {
        assertEquals("https://shop.example.com", Seller.base("  HTTPS://Shop.Example.com:443/  "))
        assertEquals("https://shop.example.com", Seller.base("https://shop.example.com/public"))
        assertEquals("https://shop.example.com/lab", Seller.base("https://shop.example.com/lab/public/?app=x#y"))
        assertEquals("http://localhost:3000", Seller.base("http://localhost:3000/"))
        assertEquals("http://localhost", Seller.base("http://localhost:80"))
        assertEquals("https://shop.example.com:8443", Seller.base("https://shop.example.com:8443"))
        assertNull(Seller.base("https://u:p@shop.example.com"))
        assertNull(Seller.base("http://shop.example.com"))
        assertNull(Seller.base("javascript:alert(1)"))
    }
}

@RunWith(RobolectricTestRunner::class)
class SignInRequestTest {
    private fun message(
        challenge: String = ROOT_CHALLENGE, seller: String = "https://shop.example.com", op: String = "sign_in", app: Boolean = true,
    ) = JSONObject().put("v", 1).put("op", op).put("seller", seller).put("challenge", challenge).apply {
        if (app) put("app", JSONObject().put("name", "moon-game").put("version", "v3").put("title", "Moon Game"))
    }

    private fun problems(m: Any?, trusted: String? = null) = SignInRequest.problems(m, NOW, trusted)

    @Test fun valid() {
        assertEquals(emptyList<String>(), problems(message()))
        assertEquals(emptyList<String>(), problems(message(seller = "https://shop.example.com/lab")))
        assertEquals(emptyList<String>(), problems(message(LOCALHOST_CHALLENGE, "http://localhost:3000")))
        val request = SignInRequest.of(message(), NOW)!!
        assertEquals("Moon Game", request.app.title)
        assertEquals("https://shop.example.com", request.seller)
    }

    @Test fun invalid() {
        assertEquals(listOf("a sign-in request must be an object"), problems("x"))
        assertTrue(problems(message(EVIL_CHALLENGE)).single().startsWith("the challenge is for evil.example.net, not shop.example.com"))
        assertTrue(problems(message(seller = "https://example.com")).isNotEmpty())
        assertEquals(listOf("the challenge has ended"), problems(message(challengeOf(claims(exp = 1793799990000)))))
        assertTrue(problems(message("Sign in to shop.example.com")).single().startsWith("challenge must be a strut visitor challenge"))
        assertTrue(problems(message("a".repeat(64))).isNotEmpty())
        assertTrue(problems(message(seller = "http://shop.example.com")).isNotEmpty())
        assertTrue(problems(message(seller = "https://u:p@shop.example.com")).isNotEmpty())
        assertEquals(listOf("app is missing"), problems(message(app = false)))
        assertEquals(listOf("a sign-in request is { v: 1, op: \"sign_in\", … }"), problems(message(op = "sign_message")))
        assertNull(SignInRequest.of(message(op = "sign_message"), NOW))
    }

    @Test fun versionIsReadAsAnInteger() {
        assertEquals(emptyList<String>(), problems(message().put("v", 1.0)))
        assertTrue(problems(message().put("v", 1.5)).isNotEmpty())
        assertTrue(problems(message().put("v", "1")).isNotEmpty())
        assertTrue(problems(message().put("v", true)).isNotEmpty())
    }

    @Test fun appFields() {
        val long = message().put("app", JSONObject().put("name", "x".repeat(1001)).put("version", "").put("title", "t"))
        assertEquals(listOf("app.name is longer than 1000 characters", "app.version must be a string"), problems(long))
    }

    @Test fun trustedHostWinsOverTheSeller() {
        // The page says its seller is evil.example.net, and the challenge is evil's: judged against the loaded host it is refused.
        val lying = message(EVIL_CHALLENGE, "https://evil.example.net")
        assertEquals(emptyList<String>(), problems(lying))
        assertTrue(problems(lying, "shop.example.com").single().contains("not shop.example.com"))
        assertEquals(emptyList<String>(), problems(message(seller = "https://example.com"), "shop.example.com"))
    }

    @Test fun mismatchIsCleanedAndCut() {
        val c = challengeOf(claims(host = "a\\nb" + "c".repeat(200)))
        val text = problems(message(c)).single()
        assertFalse(text.contains("\n"))
        assertTrue(text.length < 200)
    }
}

/// The signature vector needs `libsphinxrs`, which a plain JVM cannot load (docs/phases/27-sign-in-with-sphinx.md).
class VisitorSignatureVectorTest {
    @org.junit.Ignore("needs the native libsphinxrs, which the JVM unit-test classpath does not have: run on a device")
    @Test fun rootSignatureMatchesStrutsVector() {
        val sig = uniffi.sphinxrs.signBytes(
            "6b1e6d2f0a9c4e3b8d7f2a1c5e9b3d7f0a4c8e2b6d1f5a9c3e7b0d4f8a2c6e1b", 0u, "1", "bitcoin", ROOT_CHALLENGE.toByteArray(Charsets.UTF_8),
        )
        assertEquals(
            "1f60c294e6649c67044e90b7aa22d7b03426c3c51fd7dc5d3870b77fd9bc298adf39f874921a1da3c2444d0f705d9b8e15bdf240d70da21eedc326be68569a9fda", sig,
        )
        assertEquals(
            "02dfab7975d5ae97a90c0c92f961d74922186221d36f7f3d20271209f663558e5e",
            uniffi.sphinxrs.pubkeyFromSeed("6b1e6d2f0a9c4e3b8d7f2a1c5e9b3d7f0a4c8e2b6d1f5a9c3e7b0d4f8a2c6e1b", 0u, "1", "bitcoin"),
        )
    }
}
