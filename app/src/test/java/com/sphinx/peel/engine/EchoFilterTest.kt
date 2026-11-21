package com.sphinx.peel.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/// What Peel says aloud is told from the user (`EchoFilter`). Ported from peel's `EchoFilterTests.swift`.
class EchoFilterTest {
    private val t0 = 1_000L
    private val reply = listOf("I have added the login page to the backlog.", "Say read it all for the rest.")

    private fun speaking(): EchoFilter {
        val filter = EchoFilter()
        filter.speechStarted(reply, t0)
        return filter
    }

    @Test fun `a run of three words of the speech is an echo`() {
        val filter = speaking()
        assertTrue(filter.isEcho("added the login page", t0))
        assertTrue(filter.isEcho("Hello there, ADDED the login", t0))
        assertTrue(filter.isEcho("page to the backlog say read it", t0 + 1_000))
    }

    @Test fun `other words are not an echo`() {
        val filter = speaking()
        assertFalse(filter.isEcho("send the report to Bob", t0))
        assertFalse(filter.isEcho("login screen", t0))
        assertFalse(filter.isEcho("", t0))
    }

    @Test fun `a short transcript is an echo when wholly contained`() {
        val filter = speaking()
        assertTrue(filter.isEcho("login page", t0))
        assertTrue(filter.isEcho("backlog", t0))
        assertFalse(filter.isEcho("login backlog", t0))
    }

    @Test fun `not guarding before speech starts`() {
        val filter = EchoFilter()
        assertFalse(filter.isGuarding(t0))
        assertFalse(filter.isEcho("added the login page", t0))
    }

    @Test fun `tail echo is caught for a short while after the speech`() {
        val filter = speaking()
        filter.speechEnded(t0 + 5_000)
        assertFalse(filter.speaking)
        assertTrue(filter.isGuarding(t0 + 6_000))
        assertTrue(filter.isEcho("added the login page", t0 + 6_000))
        assertFalse(filter.isGuarding(t0 + 6_600))
        assertFalse(filter.isEcho("added the login page", t0 + 6_600))
    }

    @Test fun `spoken text counts for three seconds after it ended`() {
        val filter = speaking()
        filter.speechEnded(t0)
        filter.speechStarted(listOf("The build is green."), t0 + 2_000)
        assertTrue(filter.isEcho("the build is green", t0 + 2_500))
        assertTrue(filter.isEcho("added the login page", t0 + 2_500))
        assertFalse(filter.isEcho("added the login page", t0 + 3_500))
        assertTrue(filter.isEcho("the build is green", t0 + 3_500))
    }

    @Test fun `stopping replaces what is being spoken`() {
        val filter = speaking()
        filter.speechStarted(listOf("Something else entirely."), t0 + 1_000)
        assertTrue(filter.isEcho("something else entirely", t0 + 1_000))
        assertTrue(filter.isEcho("added the login page", t0 + 1_000))
    }

    @Test fun `currentlyContains only the text being spoken`() {
        val filter = speaking()
        assertTrue(filter.currentlyContains("login page"))
        assertFalse(filter.currentlyContains("peel stop"))
        assertFalse(filter.currentlyContains(""))
        filter.speechEnded(t0)
        assertFalse(filter.currentlyContains("login page"))
    }

    @Test fun `reset forgets everything`() {
        val filter = speaking()
        filter.reset()
        assertFalse(filter.speaking)
        assertFalse(filter.isEcho("added the login page", t0))
    }

    @Test fun `the rule is pure`() {
        assertTrue(EchoFilter.matches(listOf("a", "b", "c"), listOf("x", "a", "b", "c", "y")))
        assertFalse(EchoFilter.matches(listOf("a", "c", "b"), listOf("x", "a", "b", "c", "y")))
        assertFalse(EchoFilter.matches(emptyList(), listOf("a")))
        assertFalse(EchoFilter.matches(listOf("a"), emptyList()))
    }
}
