package com.sphinx.peel.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PingsTest {
    private val hash = "ab".repeat(32)
    private val tag = "cd".repeat(32)

    @Test
    fun `a topic's kind is its sixth segment`() {
        assertEquals("ping", InboundTopic.kind("02aa/03bb/0/res/dev/ping/1795129623000"))
        assertEquals("stream", InboundTopic.kind("02aa/03bb/3/res/dev/stream/7/1795129623000/$hash/3000"))
        assertEquals("health", InboundTopic.kind("health"))
    }

    @Test
    fun `a ping's answer and a settle are published, a key exchange only live`() {
        assertTrue(InboundPublish.publishes(keyExchange = false, restoring = false, answersPing = true, settles = false))
        assertTrue(InboundPublish.publishes(keyExchange = false, restoring = false, answersPing = false, settles = true))
        assertTrue(InboundPublish.publishes(keyExchange = true, restoring = false, answersPing = false, settles = false))
        // A restore replays history: a key exchange in it is not answered again, but what only comes live still is.
        assertFalse(InboundPublish.publishes(keyExchange = true, restoring = true, answersPing = false, settles = false))
        assertTrue(InboundPublish.publishes(keyExchange = false, restoring = true, answersPing = true, settles = false))
        assertTrue(InboundPublish.publishes(keyExchange = true, restoring = true, answersPing = false, settles = true))
        // An ordinary message asks for nothing.
        assertFalse(InboundPublish.publishes(keyExchange = false, restoring = false, answersPing = false, settles = false))
    }

    @Test
    fun `a ping is acknowledged once its payment comes, and only once`() {
        val book = PingBook()
        book.note("$hash:1795129623000:$tag")
        assertEquals(emptyList<ULong>(), book.done(listOf("ef".repeat(32)), null))
        assertEquals(listOf(1795129623000uL), book.done(listOf(hash), null))
        assertEquals(emptyList<ULong>(), book.done(listOf(hash), null))
    }

    @Test
    fun `a ping whose payment is gone is acknowledged by its tag`() {
        val book = PingBook()
        book.note("_:42:$tag")
        assertEquals(emptyList<ULong>(), book.done(emptyList(), "async pay not found: ${"ef".repeat(32)}"))
        assertEquals(listOf(42uL), book.done(emptyList(), "async pay not found: $tag"))
        assertEquals(emptyList<ULong>(), book.done(emptyList(), "async pay not found: $tag"))
    }

    @Test
    fun `a ping with no time is not kept, other errors acknowledge nothing`() {
        val book = PingBook()
        book.note("$hash::$tag")
        book.note("garbage")
        assertEquals(emptyList<ULong>(), book.done(listOf(hash), "async pay not found: $tag"))
        book.note("$hash:7:$tag")
        assertEquals(emptyList<ULong>(), book.done(emptyList(), "no contact key found"))
        assertEquals(emptyList<ULong>(), book.done(emptyList(), "async pay not found: short"))
    }
}
