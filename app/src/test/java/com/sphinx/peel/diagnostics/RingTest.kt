package com.sphinx.peel.diagnostics

import com.sphinx.peel.diagnostics.DiagnosticEvent.Action
import com.sphinx.peel.diagnostics.DiagnosticEvent.ErrorKind
import com.sphinx.peel.diagnostics.DiagnosticEvent.Kind
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/// iOS RingTests.
class RingTest {
    private fun ringFile() = File(tempDirectory(), "ring")

    @Test fun roundTripsEvents() {
        val file = ringFile()
        val ring = DiagnosticsRing(file)
        val a = DiagnosticEvent(Kind.error, timestamp = 100_000, errorKind = ErrorKind.network, lastAction = Action.tribeJoin)
        val b = DiagnosticEvent(Kind.memorySample, timestamp = 200_000, footprintBytes = 123_456_789, availableBytes = 42)
        ring.append(a)
        ring.append(b)
        assertEquals(listOf(a, b), ring.read())
        // A new process reads the same from the file.
        assertEquals(listOf(a, b), DiagnosticsRing(file).read())
    }

    @Test fun coalescesIdenticalConsecutiveEvents() {
        val ring = DiagnosticsRing(ringFile())
        ring.append(DiagnosticEvent(Kind.error, timestamp = 1000, errorKind = ErrorKind.server, lastAction = Action.send))
        ring.append(DiagnosticEvent(Kind.error, timestamp = 2000, errorKind = ErrorKind.server, lastAction = Action.send))
        ring.append(DiagnosticEvent(Kind.error, timestamp = 3000, errorKind = ErrorKind.database, lastAction = Action.send))
        ring.append(DiagnosticEvent(Kind.error, timestamp = 4000, errorKind = ErrorKind.server, lastAction = Action.send))
        val events = ring.read()
        assertEquals(listOf(2, 1, 1), events.map { it.count })
        assertEquals(2000L, events[0].timestamp)
        assertTrue(DiagnosticsRing(File(tempDirectory(), "x")).read().isEmpty())
    }

    @Test fun rotationKeepsTheNewest() {
        val file = ringFile()
        val ring = DiagnosticsRing(file, maxBytes = 4096, maxEvents = 2000)
        for (index in 0 until 200) ring.append(DiagnosticEvent(Kind.memorySample, timestamp = index * 1000L, footprintBytes = (index + 1) * 1000L))
        val events = ring.read()
        assertEquals(200_000L, events.last().footprintBytes)
        assertTrue(events.size < 200)
        assertTrue(events.size > 10)
        assertTrue(file.length() <= 4096)
        val sizes = events.mapNotNull { it.footprintBytes }
        assertEquals(sizes.sorted(), sizes)
        assertEquals(events, DiagnosticsRing(file).read())
    }

    @Test fun eventCountCapRotates() {
        val ring = DiagnosticsRing(ringFile(), maxBytes = 10_000_000, maxEvents = 10)
        for (index in 0 until 25) ring.append(DiagnosticEvent(Kind.memorySample, footprintBytes = index + 1L))
        val events = ring.read()
        assertTrue(events.size <= 10)
        assertEquals(25L, events.last().footprintBytes)
    }

    @Test fun toleratesACorruptOrTruncatedTail() {
        val file = ringFile()
        val ring = DiagnosticsRing(file)
        val a = DiagnosticEvent(Kind.error, timestamp = 1000, errorKind = ErrorKind.file)
        ring.append(a)
        file.appendBytes("{\"kind\":\"err".toByteArray())
        val reopened = DiagnosticsRing(file)
        assertEquals(listOf(a), reopened.read())
        val b = DiagnosticEvent(Kind.error, timestamp = 2000, errorKind = ErrorKind.core)
        reopened.append(b)
        assertEquals(listOf(a, b), DiagnosticsRing(file).read())
    }

    @Test fun encodedEventHasOnlyExpectedKeys() {
        val event = DiagnosticEvent(
            Kind.error, errorKind = ErrorKind.network, lastAction = Action.send, footprintBytes = 1, availableBytes = 2,
            pressureLevel = DiagnosticEvent.PressureLevel.critical, previousRun = DiagnosticEvent.PreviousRunKind.crashed,
            exitReason = DiagnosticEvent.ExitReason.exit, exitStatus = 3,
        )
        val map = MiniJson.parseObject(event.toJson())!!
        val allowed = setOf("kind", "timestamp", "count", "errorKind", "lastAction", "footprintBytes", "availableBytes", "pressureLevel", "previousRun", "exitReason", "exitStatus")
        assertEquals(allowed, map.keys)
        for ((key, value) in map) if (value is String) {
            assertTrue(key, key in setOf("kind", "errorKind", "lastAction", "pressureLevel", "previousRun", "exitReason"))
        }
    }

    @Test fun concurrentAppendsAreAllKept() {
        val file = ringFile()
        val ring = DiagnosticsRing(file)
        val pool = Executors.newFixedThreadPool(8)
        for (index in 0 until 100) pool.execute { ring.append(DiagnosticEvent(Kind.memorySample, footprintBytes = index + 1L)) }
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        assertEquals(100, ring.read().size)
        assertEquals(100, ring.read().mapNotNull { it.footprintBytes }.toSet().size)
        assertEquals(100, DiagnosticsRing(file).read().size)
    }

    @Test fun memorySamplesSkipSmallChanges() {
        assertTrue(MemoryPolicy.shouldRecord(null, 100))
        assertTrue(!MemoryPolicy.shouldRecord(1000, 1040))
        assertTrue(MemoryPolicy.shouldRecord(1000, 1050))
        assertTrue(MemoryPolicy.shouldRecord(1000, 940))
        assertTrue(MemoryPolicy.shouldRecord(1000, 1001, pressure = true))
    }
}
