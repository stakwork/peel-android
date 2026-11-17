package com.sphinx.peel.push

import android.Manifest
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.sphinx.peel.storage.MessageRow
import com.sphinx.peel.storage.Storage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/// What a background message notifies (`deliver`, the decision `MessageNotifier` runs per row, needs no engine). The point of this file: a control
/// message in a direct message is a line, never news (iOS `NotificationPolicy`: `isControl != true`, docs/phases/40-control-messages.md), while
/// the same metadata in a tribe is a member's message and notifies.
@RunWith(RobolectricTestRunner::class)
class MessageNotifierTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val now = 1_800_000_000_000L
    private val ack = """{"peel":{"v":1,"op":"ack","re":"r"}}"""
    private val status = """{"peel":{"v":1,"op":"status","run":"x","state":"s","text":"t"}}"""
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val posted get() = shadowOf(manager).allNotifications
    private lateinit var storage: Storage
    private var next = 0L

    @Before fun setUp() {
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        storage = Storage(context, "notifier-${System.nanoTime()}")
        storage.upsertContact("dm", "Agent", null, isTribe = false, ready = true)
        storage.upsertContact("tribe", "Tribe", null, isTribe = true, ready = true)
    }

    private fun row(chat: String, content: String, metadata: String? = null, fromMe: Boolean = false): MessageRow {
        next++
        val body = JSONObject().put("content", content).apply { if (metadata != null) put("metadata", metadata) }.toString()
        return MessageRow(uuid = "u$next", serverIndex = next, type = 0, chat = chat, fromMe = fromMe, sender = null, body = body, msat = null, timestamp = now, tag = null, paymentHash = null)
    }

    @Test fun `an ordinary message in a direct message notifies`() {
        deliver(context, storage, row("dm", "hello"), now)
        assertEquals(1, posted.size)
    }

    @Test fun `a control message in a direct message never notifies`() {
        deliver(context, storage, row("dm", "Peel asked your Mac", ack), now)
        deliver(context, storage, row("dm", "working on it", status), now)
        assertEquals(0, posted.size)
    }

    @Test fun `talk extras without an op are an ordinary message and notify`() {
        deliver(context, storage, row("dm", "Sent.", """{"peel":{"tools":[{"name":"x"}]}}"""), now)
        assertEquals(1, posted.size)
    }

    @Test fun `the same metadata in a tribe is a member's message and notifies`() {
        deliver(context, storage, row("tribe", "hi", ack), now)
        assertEquals(1, posted.size)
    }

    @Test fun `our own and stale messages do not notify`() {
        deliver(context, storage, row("dm", "mine", fromMe = true), now)
        deliver(context, storage, row("dm", "old"), now + 10 * 60_000L)
        assertEquals(0, posted.size)
    }
}
