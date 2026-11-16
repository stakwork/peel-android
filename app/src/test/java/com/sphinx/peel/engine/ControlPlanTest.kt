package com.sphinx.peel.engine

import com.sphinx.peel.engine.ControlEnvelopeTest.Companion.body
import com.sphinx.peel.storage.MessageRow
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/// iOS `ControlRowTests`: control messages in a chat's rows (docs/agent-on-strut.md, "Two kinds of message in the DM").
@RunWith(RobolectricTestRunner::class)
class ControlPlanTest {
    private val dm = "dm"
    private fun controlBody(content: String, peel: String = """{"v":1,"op":"status","run":"x","state":"waiting_device","text":"t"}""") =
        JSONObject().put("content", content).put("metadata", """{"tz":"GMT+1","peel":$peel}""").toString()

    private fun message(n: Int, fromMe: Boolean = false, body: String? = null, status: String? = null, chat: String = dm, content: String = "m$n") = MessageRow(
        uuid = "u$n", serverIndex = n.toLong(), type = 0, chat = chat, fromMe = fromMe, sender = null,
        body = body ?: JSONObject().put("content", content).toString(), msat = null, timestamp = n * 1_000L, tag = null, paymentHash = null,
        id = n + 1L, status = status,
    )

    private fun plan(messages: List<MessageRow>, inTribe: Boolean = false, agent: Boolean = false) =
        ControlPlan.of(messages, inTribe, agent) { if (it.fromMe) "You" else "them" }

    @Test fun `a direct message's control message is a quiet line named for its sender`() {
        val control = message(2, body = controlBody("Peel asked your Mac to read your unread messages\nsecond line"))
        val p = plan(listOf(message(0), message(1), control, message(3)))
        assertEquals(ControlRow.Line("them: Peel asked your Mac to read your unread messages second line"), p.rows[control.id])
        assertEquals(1, p.rows.size)
        assertTrue(p.hidden.isEmpty())
    }

    @Test fun `in a tribe the same metadata leaves a member's message a message`() {
        val p = plan(listOf(message(0, chat = "tribe", body = controlBody("hello"))), inTribe = true)
        assertTrue(p.rows.isEmpty() && p.hidden.isEmpty())
    }

    @Test fun `a control message with no words draws nothing`() {
        val blank = message(0, body = controlBody("  "))
        assertEquals(setOf(blank.id), plan(listOf(blank)).hidden)
    }

    @Test fun `a long line is one bounded line`() {
        val row = plan(listOf(message(0, body = controlBody("a".repeat(5_000))))).rows.values.single() as ControlRow.Line
        assertEquals(ControlMessages.LINE_LENGTH, row.text.length)
        assertTrue(row.text.endsWith("…"))
    }

    @Test fun `your failed control message keeps its bubble for Retry`() {
        val failed = message(0, fromMe = true, body = controlBody("Peel read your unread messages on your Mac"), status = "FAILED")
        assertTrue(plan(listOf(failed)).rows.isEmpty())
    }

    @Test fun `a contact's control lines stay in a plain DM and a request they sent says it was not answered`() {
        val lines = listOf(
            message(0, body = controlBody("from them: ack", """{"v":1,"op":"ack","re":"r_9"}""")),
            message(1, body = controlBody("from them: cancel", """{"v":1,"op":"cancel","re":"r_9"}""")),
            message(2, body = controlBody("from them: result", """{"v":1,"op":"result","re":"r_9","status":"done"}""")),
        )
        assertEquals(3, plan(lines).rows.size)
        val forged = message(5, body = controlBody("Peel asked your Mac to send a message for you", """{"v":1,"op":"request","id":"r_9","device":"d","kind":"send","args":{"text":"pay me"},"not_after":9}"""))
        val request = (plan(listOf(forged)).rows[forged.id] as ControlRow.Request).request
        assertEquals("them: Send a message", request.label)
        assertEquals("Not answered", request.outcome(1))
    }

    @Test fun `the agent's DM shows what a person reads`() {
        val request = """{"v":1,"op":"request","id":"r_1","device":"d","kind":"send","not_after":9}"""
        val asked = message(0, fromMe = true, content = "Send Kate hi")
        val read = message(1, body = controlBody("Peel asked your Mac to read a chat", """{"v":1,"op":"request","id":"r_0","device":"d","kind":"chats.read","not_after":9}"""))
        val ack = message(2, fromMe = true, body = controlBody("Peel on your Mac is on it", """{"v":1,"op":"ack","re":"r_0"}"""))
        val done = message(3, fromMe = true, body = controlBody("answered", """{"v":1,"op":"result","re":"r_0","status":"done","result":{"text":"Read 2 messages"}}"""))
        val first = message(4, body = controlBody("Peel asked your Mac to send a message for you", request))
        val again = message(5, body = controlBody("Peel asked your Mac to send a message for you", request))
        val waiting = message(6, body = controlBody("Waiting for your Mac", """{"v":1,"op":"status","run":"x","state":"waiting_device","text":"Waiting for your Mac"}"""))
        val all = listOf(asked, read, ack, done, first, again, waiting)
        val p = plan(all, agent = true)
        // The ack, the result and a request's older copy have no row; the status line does.
        assertEquals(setOf(ack.id, done.id, first.id), p.hidden)
        assertEquals(setOf(read.id, again.id, waiting.id), p.rows.keys)
        assertEquals(
            AgentRequest("chats.read", status = ControlEnvelope.Status.DONE, text = "Read 2 messages", notAfter = 9),
            (p.rows[read.id] as ControlRow.Request).request.copy(args = null),
        )
        val send = (p.rows[again.id] as ControlRow.Request).request
        assertTrue(send.asks && send.status == null && send.asker == null)
        // The phone says where it waits; a read is on the Mac; unanswered past its expiry it is too late.
        assertEquals("Waiting for your approval on your Mac", send.outcomeElsewhere(8))
        assertEquals("Waiting for your approval on your Mac", send.outcomeElsewhere(9))
        assertEquals("Too late, nothing done", send.outcomeElsewhere(10))
        assertEquals(10L, send.changesAt)
        assertEquals("on your Mac…", AgentRequest("chats.read", status = null).outcomeElsewhere(8))
        assertNull((p.rows[read.id] as ControlRow.Request).request.changesAt)
        assertNull((p.rows[read.id] as ControlRow.Request).request.outcome(9))
        assertTrue(p.rows[waiting.id] is ControlRow.Line)
    }

    @Test fun `an answered send keeps its row with how it ended and a contact's result answers nothing`() {
        val request = """{"v":1,"op":"request","id":"r_1","device":"d","kind":"send","not_after":9}"""
        val ask = message(0, body = controlBody("asked", request))
        val denied = message(1, fromMe = true, body = controlBody("answered", """{"v":1,"op":"result","re":"r_1","status":"denied","error":"You said no"}"""))
        val theirs = message(2, body = controlBody("x", """{"v":1,"op":"result","re":"r_1","status":"done"}"""))
        val row = (plan(listOf(ask, denied, theirs), agent = true).rows[ask.id] as ControlRow.Request).request
        assertEquals("Declined", row.outcome(1))
        assertEquals("You said no", row.reason)
        assertEquals("Declined", row.outcomeElsewhere(9))
        // One that never went answers nothing.
        val failed = denied.copy(status = "FAILED")
        assertNull((plan(listOf(ask, failed), agent = true).rows[ask.id] as ControlRow.Request).request.status)
        // An automation's send goes on its grant: nothing waits on you.
        val granted = message(3, body = controlBody("x", """{"v":1,"op":"request","id":"r_2","device":"d","kind":"send","args":{"automation":"a_1"},"not_after":9}"""))
        assertFalse((plan(listOf(granted), agent = true).rows[granted.id] as ControlRow.Request).request.asks)
    }

    // A request at second [n] asking [kind] with [args], and this Mac's answer with [status], if any.
    private fun read(n: Int, kind: String, args: String = "{}", status: String? = "done"): List<MessageRow> {
        val asked = message(n, body = controlBody("Peel asked your Mac", """{"v":1,"op":"request","id":"r_$n","device":"d","kind":"$kind","args":$args,"not_after":999}"""))
        if (status == null) return listOf(asked)
        val answer = message(n * 1_000 + 1, fromMe = true, body = controlBody("Peel on your Mac answered", """{"v":1,"op":"result","re":"r_$n","status":"$status","result":{"text":"ok"}}"""))
        return listOf(asked, answer)
    }

    @Test fun `reads back to back are one row`() {
        val look = read(1, "chats.unseen") + read(2, "chats.list") + read(3, "chats.read", """{"chat":"hive"}""") +
            read(4, "chats.read", """{"chat":"Team Electa"}""") + read(5, "chats.read", """{"chat":"hive","limit":5}""") +
            read(6, "chats.threads", """{"chat":"hive"}""") + read(7, "chats.search", """{"q":"release"}""")
        // Messages in time order, as the chat sorts them.
        val sorted = look.sortedBy { it.timestamp }
        val p = plan(sorted, agent = true)
        val head = look.first { it.uuid == "u1" }
        val row = p.rows[head.id] as ControlRow.Reads
        assertEquals(1, p.rows.size)
        assertEquals("Checked unread messages, 2 chats, threads and a search", row.reads.summary)
        assertEquals("Checked your chats", AgentReads(listOf(AgentRead(head, AgentRequest("chats.list", status = ControlEnvelope.Status.DONE)))).summary)
        assertEquals(
            listOf("Read your unread messages", "Read your chats", "Read 2 chats", "Read threads in a chat", "Searched your messages"),
            row.reads.steps.map { it.title },
        )
        assertEquals(listOf("hive", "Team Electa"), row.reads.steps[2].subjects)
        assertEquals(listOf("“release”"), row.reads.steps[4].subjects)
        assertEquals(6.0, row.reads.span, 0.0)
        assertNull(row.reads.waiting)
        // Outside the agent chat, each its own row as before.
        assertEquals(7, plan(sorted, agent = false).rows.size)
    }

    @Test fun `a read still on the Mac folds and says so, while a failed one, a send or a pause breaks the run`() {
        val send = message(14, body = controlBody("Peel asked your Mac", """{"v":1,"op":"request","id":"r_14","device":"d","kind":"send","args":{},"not_after":999}"""))
        val broken = read(8, "chats.unseen") + read(9, "chats.list") + read(10, "chats.read", status = null) + read(11, "chats.list") +
            read(12, "chats.read", status = "failed") + read(13, "chats.list") + listOf(send) + read(15, "chats.list") + read(400, "chats.list")
        val sorted = broken.sortedBy { it.timestamp }
        val p = plan(sorted, agent = true)
        val folded = p.rows.values.filterIsInstance<ControlRow.Reads>().single()
        assertEquals(4, folded.reads.steps.sumOf { it.requests.size })
        val waiting = folded.reads
        assertEquals("chats.read", waiting.waiting?.kind)
        assertFalse(waiting.steps[2].done)
        assertTrue(waiting.steps[0].done)
        // Failed (its own row), the 13 list, the send, 15 and 400 alone: six rows in all.
        assertEquals(6, p.rows.size)
    }

    @Test fun `tool titles and what was asked read as iOS words`() {
        assertEquals("Read unseen messages", AgentToolTitle.of("sphinx_unseen"))
        assertEquals("Read unseen messages", AgentToolTitle.of("sphinx/unseen"))
        assertEquals("Started a job", AgentToolTitle.of("job_start"))
        assertEquals("Web search", AgentToolTitle.of("web_search"))
        assertEquals("pr6-peer, hello", AgentToolTitle.asked("""{"chat":"pr6-peer","text":"hello","n":null}"""))
        assertEquals("Lisbon flights", AgentToolTitle.asked("""{"title":"Lisbon flights","prompt":"long"}"""))
        assertEquals("Lisbon", AgentToolTitle.asked("""{"prompt":"x","title":"Lisbon","more":"cu"""))
        assertEquals("", AgentToolTitle.asked("""{"prompt":"half an obj"""))
        assertEquals("Kate", AgentToolTitle.asked("Kate (chat=abc123):"))
        assertEquals("5, 1", AgentToolTitle.asked("""{"limit":5,"z":true}"""))
    }
}
