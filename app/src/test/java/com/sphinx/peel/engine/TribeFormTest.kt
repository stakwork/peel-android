package com.sphinx.peel.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TribeFormTest {
    private val full = TribeForm(
        name = " Peel fans ", description = " about peels ", img = "https://x/y.jpg",
        priceToJoinSat = 21, pricePerMessageSat = 2, escrowSat = 5, escrowHours = 2,
        tags = listOf("fruit", "chat"), unlisted = true, appUrl = "https://app", feedUrl = "https://feed", approvalRequired = true,
    )

    @Test
    fun `the create body has the registry's fields, amounts in msat`() {
        val json = JSONObject(full.toJson())
        assertEquals("Peel fans", json.getString("name"))
        assertEquals("about peels", json.getString("description"))
        assertEquals("fruit", json.getJSONArray("tags").getString(0))
        assertEquals("https://x/y.jpg", json.getString("img"))
        assertEquals(21_000L, json.getLong("price_to_join"))
        assertEquals(2_000L, json.getLong("price_per_message"))
        assertEquals(5_000L, json.getLong("escrow_amount"))
        assertEquals(7_200_000L, json.getLong("escrow_millis"))
        assertTrue(json.getBoolean("unlisted"))
        assertTrue(json.getBoolean("private"))
        assertEquals("https://app", json.getString("app_url"))
        assertEquals("https://feed", json.getString("feed_url"))
        assertFalse(json.has("pubkey"))
        assertFalse(json.has("pin"))
    }

    @Test
    fun `empty optional fields are left out and an edit names its tribe`() {
        val json = JSONObject(TribeForm(name = "a", pubkey = "k", uuid = "u", pin = "").toJson())
        assertFalse(json.has("img"))
        assertFalse(json.has("app_url"))
        assertFalse(json.has("feed_url"))
        assertEquals("k", json.getString("pubkey"))
        assertEquals("u", json.getString("uuid"))
        assertEquals("", json.getString("pin"))
        assertEquals(0L, json.getLong("price_to_join"))
    }

    @Test
    fun `what cannot be sent has a word for the person`() {
        assertNull(full.problem())
        assertTrue(TribeForm(name = "  ").problem()!!.contains("name"))
        assertTrue(full.copy(img = "http://x").problem()!!.contains("https"))
        assertTrue(full.copy(escrowHours = 0).problem()!!.contains("hours"))
        assertNull(full.copy(escrowSat = 0, escrowHours = 0).problem())
        assertTrue(full.copy(priceToJoinSat = -1).problem()!!.contains("negative"))
        assertTrue(full.copy(appUrl = "http://app").problem()!!.contains("app URL"))
        assertTrue(full.copy(feedUrl = "ftp://feed").problem()!!.contains("feed URL"))
        assertTrue(full.copy(tags = List(11) { "t$it" }).problem()!!.contains("tags"))
        assertTrue(full.copy(name = "x".repeat(101)).problem()!!.contains("name"))
    }

    @Test
    fun `tags are trimmed, lower-cased and unique`() {
        assertEquals(listOf("art", "music"), TribeForm.tagsFrom("Art, music ,, ART"))
        assertTrue(TribeForm.tagsFrom(" , ").isEmpty())
    }

    @Test
    fun `the edit form is filled from the registry's entry`() {
        val info = TribeInfo(
            pubkey = "k", name = "Peel", img = null, pricePerMessageMsat = 2_000, escrowMsat = 5_000, description = "d",
            tags = listOf("a"), isPrivate = true, priceToJoinMsat = 21_000, escrowMillis = 7_200_000, uuid = "u",
            unlisted = true, appUrl = "https://app", pin = "p",
        )
        val form = TribeForm.from(info)
        assertEquals(TribeForm(
            name = "Peel", description = "d", priceToJoinSat = 21, pricePerMessageSat = 2, escrowSat = 5, escrowHours = 2,
            tags = listOf("a"), unlisted = true, appUrl = "https://app", approvalRequired = true, pubkey = "k", uuid = "u", pin = "p",
        ), form)
    }

    @Test
    fun `a registry answer is read, with the fields the form and join need`() {
        val info = TribeInfo.parse(
            """{"pubkey":"k","name":"Peel","uuid":"u","price_to_join":21000,"private":true,"unlisted":true,
               "app_url":"https://app","pin":"p","owner_pubkey":"o","owner_route_hint":"03aa_1","tags":["a"," "]}""",
        )
        assertEquals("u", info.uuid)
        assertEquals(21_000L, info.priceToJoinMsat)
        assertEquals(true, info.isPrivate)
        assertTrue(info.unlisted)
        assertEquals("https://app", info.appUrl)
        assertNull(info.feedUrl)
        assertEquals("p", info.pin)
        assertEquals("o", info.ownerPubkey)
        assertEquals("03aa_1", info.ownerRouteHint)
        assertEquals(listOf("a"), info.tags)
    }
}
