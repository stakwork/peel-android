package com.sphinx.peel.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/// iOS `ControlEnvelopeTests` (reading side): what anyone else's metadata is read as without ever being taken for more than it is.
@RunWith(RobolectricTestRunner::class)
class ControlEnvelopeTest {
    companion object {
        /// A body whose metadata is [metadata], written into it as a string the way the official apps and the bridge write it.
        fun body(metadata: String, content: String = "Peel asked your Mac to read your unread messages"): String =
            JSONObject().put("content", content).put("metadata", metadata).toString()

        fun control(body: String, type: Int = 0, deleted: Boolean = false) = ControlMessages.control(type, deleted, body)

        fun envelope(metadata: String): ControlEnvelope? = (control(body(metadata)) as? ControlMessage.Envelope)?.envelope
    }

    @Test fun `each op reads with the fields it needs`() {
        val request = envelope("""{"peel":{"v":1,"op":"request","id":"r_1","device":"mac-3f2a","kind":"chats.unseen","args":{"since":"x"},"not_after":1760610000}}""")!!
        assertEquals(ControlEnvelope.Op.REQUEST, request.op)
        assertEquals("r_1", request.id)
        assertEquals(1_760_610_000L, request.notAfter)
        assertEquals("x", (request.args as JSONObject).getString("since"))
        assertEquals("r_1", envelope("""{"peel":{"v":1,"op":"ack","re":"r_1"}}""")!!.re)
        val done = envelope("""{"peel":{"v":1,"op":"result","re":"r_1","status":"done","result":["a",3]}}""")!!
        assertEquals(ControlEnvelope.Status.DONE, done.status)
        assertEquals(ControlEnvelope.Status.FAILED, envelope("""{"peel":{"v":1,"op":"result","re":"r_1","status":"failed","error":"unsupported"}}""")!!.status)
        assertEquals("r_1", envelope("""{"peel":{"v":1,"op":"cancel","re":"r_1"}}""")!!.re)
        assertEquals("run_9", envelope("""{"peel":{"v":1,"op":"cancel","run":"run_9"}}""")!!.run)
        // Stop with no run named: the turn in flight.
        assertEquals(ControlEnvelope.Op.CANCEL, envelope("""{"peel":{"v":1,"op":"cancel"}}""")!!.op)
        val status = envelope("""{"tz":"GMT+1","peel":{"v":1,"op":"status","run":"run_9","state":"waiting","text":"Waiting for your Mac"}}""")!!
        assertEquals("Waiting for your Mac", status.text)
    }

    @Test fun `unreadable envelopes are unsupported`() {
        listOf(
            """{"peel":{"v":2,"op":"request","id":"r","device":"d","kind":"chats.list","not_after":1}}""",
            """{"peel":{"v":"1","op":"ack","re":"r"}}""",
            """{"peel":{"op":"ack","re":"r"}}""",
            """{"peel":{"v":1,"op":"launch","re":"r"}}""",
            """{"peel":{"v":1,"op":7}}""",
            """{"peel":{"v":1,"op":"ack"}}""",
            """{"peel":{"v":1,"op":"request","id":"r","device":"d","kind":"chats.list"}}""",
            """{"peel":{"v":1,"op":"request","id":"r","device":"d","kind":"chats.list","not_after":"soon"}}""",
            """{"peel":{"v":1,"op":"result","re":"r"}}""",
            """{"peel":{"v":1,"op":"result","re":"r","status":"maybe"}}""",
            """{"peel":{"v":1,"op":"status","run":"x","text":"Waiting"}}""",
            """{"peel":{"v":1,"op":"ack","re":["r"]}}""",
        ).forEach { assertEquals(it, ControlMessage.Unsupported, control(body(it))) }
    }

    @Test fun `other metadata is not control`() {
        listOf(
            """{"tz":"GMT+1"}""",
            """{"peel":{"tools":[{"name":"search_messages"}]}}""",
            """{"peel":{"automation":"Weekly recap"}}""",
            """{"peel":"request"}""",
            """{"peel":null}""",
            """{"peel":[{"op":"ack"}]}""",
            """["peel"]""",
            """{"peel":{"op":"ack"""",
            "\"peel\"",
            "",
        ).forEach { assertNull(it, control(body(it))) }
    }

    @Test fun `malformed bodies are not control`() {
        listOf(
            // The key in the content, not the metadata.
            """{"content":"\"peel\" {\"op\":\"ack\"}"}""",
            // Metadata that is not a string.
            """{"content":"x","metadata":{"peel":{"v":1,"op":"ack","re":"r"}}}""",
            """{"content":"x","metadata":5,"peel":{"v":1,"op":"ack","re":"r"}}""",
            // Not JSON at all.
            """{"content":"x","metadata":"{\"peel\":{\"v\":1""",
        ).forEach { assertNull(it, control(it)) }
    }

    @Test fun `escaped keys are read as any reader reads them`() {
        assertTrue(control(body("""{"\u0070eel":{"v":1,"op":"ack","re":"r"}}""")) is ControlMessage.Envelope)
        assertTrue(control("""{"content":"x","metadata":"{\"\u0070eel\":{\"v\":1,\"op\":\"ack\",\"re\":\"r\"}}"}""") is ControlMessage.Envelope)
    }

    @Test fun `only text carries an envelope and a deleted message is only deleted`() {
        val ack = body("""{"peel":{"v":1,"op":"ack","re":"r"}}""")
        for (type in listOf(DIRECT_PAYMENT_MESSAGE_TYPE, ATTACHMENT_MESSAGE_TYPE, INVOICE_MESSAGE_TYPE, CALL_MESSAGE_TYPE)) assertNull(control(ack, type))
        assertNull(control(ack, deleted = true))
        assertTrue(control(ack) != null)
    }

    @Test fun `hostile metadata costs a bounded read`() {
        val padding = " ".repeat(MessageMetadata.MAX_LENGTH)
        assertNull(control(body("""{"peel":{"v":1,"op":"ack","re":"r"}}$padding""")))
        val deep = "[".repeat(20_000) + "]".repeat(20_000)
        assertNull(control(body("""{"peel":{"v":1,"op":"ack","re":"r","args":$deep}}""")))
    }

    @Test fun `a reply's tool rows are the bridge's list as written`() {
        val reply = body(
            """{"tz":"GMT+1","peel":{"tools":[{"name":"sphinx_unseen","summary":"{}"},
            {"name":"sphinx_send","summary":"{\"chat\":\"Kate\"}","failed":true},
            {"summary":"no name"},{"name":""},{"name":"memory_recall","summary":5,"failed":"yes"},"junk"]}}""",
            content = "You have 3 unread chats.",
        )
        assertEquals(
            listOf(AgentToolLine("sphinx_unseen", "{}"), AgentToolLine("sphinx_send", """{"chat":"Kate"}""", failed = true), AgentToolLine("memory_recall")),
            AgentExtras.tools(0, false, reply),
        )
        // A control message's `tools`, or none at all, are no tool rows.
        assertTrue(AgentExtras.tools(0, false, body("""{"peel":{"v":1,"op":"ack","re":"r","tools":[{"name":"x"}]}}""")).isEmpty())
        assertTrue(AgentExtras.tools(0, false, body("""{"peel":{"automation":"Weekly recap"}}""")).isEmpty())
        assertTrue(AgentExtras.tools(ATTACHMENT_MESSAGE_TYPE, false, body("""{"peel":{"tools":[{"name":"x"}]}}""")).isEmpty())
    }

    @Test fun `a job names its job and what it points to`() {
        val reply = body(
            """{"peel":{"job":{"id":"j_7","title":"Lisbon flights","state":"asking"},
            "artifacts":[{"id":"plan","title":"Plan","label":"Plan","url":"https://example.com/plan"},{"title":""},"junk"],
            "tools":[{"name":"web_search"}]}}""",
            content = "Which dates?",
        )
        assertEquals(AgentJob("j_7", "Lisbon flights", AgentJob.State.ASKING), AgentExtras.job(0, false, reply))
        assertEquals(listOf("web_search"), AgentExtras.tools(0, false, reply).map { it.name })
        assertEquals(listOf("Plan"), AgentMessages.artifacts(reply).map { it.title })
        // A state it does not know is no state; a job with no id is none.
        assertEquals(AgentJob("j_8"), AgentExtras.job(0, false, body("""{"peel":{"job":{"id":"j_8","state":"exploded"}}}""")))
        assertNull(AgentExtras.job(0, false, body("""{"peel":{"job":{"title":"x"}}}""")))
        // A control message's job is no job.
        assertNull(AgentExtras.job(0, false, body("""{"peel":{"v":1,"op":"ack","re":"r","job":{"id":"j"}}}""")))
    }

    @Test fun `a control line names who sent it, on one line, bounded`() {
        val long = body("""{"peel":{"v":1,"op":"ack","re":"r"}}""", content = "a".repeat(5_000))
        val line = ControlMessages.line(long, "them")
        assertEquals(ControlMessages.LINE_LENGTH, line.length)
        assertTrue(line.endsWith("…"))
        assertEquals("them: Peel asked second line", ControlMessages.line(body("{}", "Peel asked\nsecond line"), "them"))
        assertEquals("", ControlMessages.line(body("{}", "  "), "them"))
    }
}
