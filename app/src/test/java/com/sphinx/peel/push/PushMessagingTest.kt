package com.sphinx.peel.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/// Robolectric only because `org.json` is a stub on a plain JVM.
@RunWith(RobolectricTestRunner::class)
class PushMessagingTest {
    private val key = "ab".repeat(32)

    @Test fun `flat child`() =
        assertEquals("aa11", PeelMessagingService.encryptedChild(mapOf("child" to "aa11", "title" to "Peel")))

    @Test fun `custom data child`() =
        assertEquals("bb22", PeelMessagingService.encryptedChild(mapOf("custom_data" to """{"child":"bb22"}""")))

    @Test fun `flat child wins over custom data`() =
        assertEquals("aa", PeelMessagingService.encryptedChild(mapOf("child" to "aa", "custom_data" to """{"child":"bb"}""")))

    @Test fun `missing or empty child`() {
        assertNull(PeelMessagingService.encryptedChild(emptyMap()))
        assertNull(PeelMessagingService.encryptedChild(mapOf("child" to "")))
        assertNull(PeelMessagingService.encryptedChild(mapOf("custom_data" to """{"child":""}""")))
        assertNull(PeelMessagingService.encryptedChild(mapOf("custom_data" to "not json")))
    }

    private val names = PushNameDirectory(
        contacts = mapOf(7uL to "Alice"),
        tribes = mapOf(9uL to "Cool Tribe"),
        contactChats = mapOf(7uL to "02abc"),
        tribeChats = mapOf(9uL to "tribe-chat"),
    )

    @Test fun `round trip`() {
        val decoded = PushNameDirectory.decode(names.encoded())
        assertEquals(names, decoded)
    }

    @Test fun `encoded uses ios key names`() {
        val json = org.json.JSONObject(names.encoded())
        assertEquals("Alice", json.getString("contact-7"))
        assertEquals("Cool Tribe", json.getString("tribe-9"))
    }

    @Test fun `cleaning`() {
        val long = "x".repeat(150)
        val dir = PushNameDirectory(
            contacts = mapOf(1uL to "  Bo\u0000b\n ", 2uL to "   ", 3uL to long),
            tribes = mapOf(4uL to "\u0007"),
        ).cleaned()
        assertEquals("Bob", dir.contacts[1uL])
        assertFalse(dir.contacts.containsKey(2uL))
        assertEquals(100, dir.contacts[3uL]!!.length)
        assertTrue(dir.tribes.isEmpty())
        assertNull(PushNameDirectory.cleanName(" \t "))
    }

    @Test fun `decode garbage is empty`() {
        assertTrue(PushNameDirectory.decode("nope").isEmpty)
        assertTrue(PushNameDirectory.decode("""{"contact-x":"a","other":"b"}""").isEmpty)
    }

    @Test fun `resolve contact`() {
        val c = PushNames.resolve("child", key, names, decrypt = { _, _ -> 7uL })
        assertEquals("Peel", c.title)
        assertEquals("You have new messages from Alice", c.body)
        assertEquals(PushRoute(PushRoute.Kind.CONTACT, 7uL, "02abc"), c.route)
    }

    @Test fun `resolve tribe`() {
        val c = PushNames.resolve("child", key, names, decrypt = { _, _ -> 9uL })
        assertEquals("You have new messages in Cool Tribe", c.body)
        assertEquals(PushRoute(PushRoute.Kind.TRIBE, 9uL, "tribe-chat"), c.route)
    }

    @Test fun `unknown index is generic`() =
        assertEquals(PushContent.GENERIC, PushNames.resolve("child", key, names, decrypt = { _, _ -> 99uL }))

    @Test fun `decrypt failure is generic`() =
        assertEquals(PushContent.GENERIC, PushNames.resolve("child", key, names, decrypt = { _, _ -> throw IllegalStateException("bad") }))

    @Test fun `missing child key or names is generic`() {
        val ok: (String, String) -> ULong = { _, _ -> 7uL }
        assertEquals(PushContent.GENERIC, PushNames.resolve(null, key, names, ok))
        assertEquals(PushContent.GENERIC, PushNames.resolve("", key, names, ok))
        assertEquals(PushContent.GENERIC, PushNames.resolve("child", null, names, ok))
        assertEquals(PushContent.GENERIC, PushNames.resolve("child", key, null, ok))
    }

    @Test fun `generic text`() {
        assertEquals("Peel", PushContent.GENERIC.title)
        assertEquals("You have new messages", PushContent.GENERIC.body)
    }

    @Test fun `duplicate decision`() {
        // No engine: nothing else will notify.
        assertEquals(PushPlan(reconnect = false, postResolved = true), PushPlan.decide(false, false, false))
        // Engine, background, down: reconnect and post (MQTT's notification replaces it).
        assertEquals(PushPlan(reconnect = true, postResolved = true), PushPlan.decide(true, false, false))
        // Engine, background, connected: no reconnect, still post the replaceable one.
        assertEquals(PushPlan(reconnect = false, postResolved = true), PushPlan.decide(true, true, false))
        // In front: the screen shows it, nothing is posted.
        assertEquals(PushPlan(reconnect = false, postResolved = false), PushPlan.decide(true, true, true))
        assertEquals(PushPlan(reconnect = true, postResolved = false), PushPlan.decide(true, false, true))
    }
}
