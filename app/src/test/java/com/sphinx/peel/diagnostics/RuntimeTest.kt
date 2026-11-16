package com.sphinx.peel.diagnostics

import com.sphinx.peel.account.LockDecision
import com.sphinx.peel.diagnostics.DiagnosticEvent as E
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeTest {
    private val env = RunEnvironment(0, "Android 15", 0)
    private fun runtime(root: File = tempDirectory(), crash: String? = null, probe: MemoryProbe? = null, mirror: (String) -> Unit = {}) =
        DiagnosticsRuntime(root, "9", crash, environment = env, probe = probe, mirror = mirror)

    private class Probe(var bytes: Long?) : MemoryProbe {
        override fun footprint() = bytes
        override fun available() = 1L shl 30
    }

    @Test fun runtimeRecordsAndReadsBack() {
        val lines = mutableListOf<String>()
        val rt = runtime(mirror = { lines += it })
        rt.setAction(E.Action.tribeJoin)
        rt.note(E.ErrorKind.network)
        assertEquals(listOf(E.Kind.launch, E.Kind.previousRun, E.Kind.error), rt.ring.read().map { it.kind })
        assertEquals(E.Action.tribeJoin, rt.ring.read()[2].lastAction)
        assertTrue(lines.any { it.startsWith("launch front=true") })
        assertNull(rt.latestRedacted(emptyList(), emptyList()))
    }

    @Test fun aKilledRunGetsAPreviousRunDetail() {
        val root = tempDirectory()
        runtime(root)
        val second = runtime(root)
        assertEquals(PreviousRun.KilledForeground, second.previous)
        assertTrue(second.previous.isProblem)
        val kinds = second.ring.read().map { it.kind }
        assertTrue(kinds.containsAll(listOf(E.Kind.previousRun, E.Kind.previousRunDetail)))
        assertEquals(E.ExitCause.system, second.ring.read().first { it.kind == E.Kind.previousRunDetail }.exitCause)
    }

    @Test fun aCrashedRunHasNoDetailLine() {
        val root = tempDirectory()
        runtime(root)
        val second = runtime(root, crash = "abc")
        assertEquals(PreviousRun.Crashed("abc"), second.previous)
        assertFalse(second.ring.read().any { it.kind == E.Kind.previousRunDetail })
    }

    @Test fun memorySamplingFollowsThePolicy() {
        val probe = Probe(100L shl 20)
        val rt = runtime(probe = probe)
        rt.sample()
        probe.bytes = 102L shl 20
        rt.sample() // within 5%: skipped
        probe.bytes = 120L shl 20
        rt.sample()
        probe.bytes = 121L shl 20
        rt.sample(E.PressureLevel.warning) // pressure always
        val kinds = rt.ring.read().map { it.kind }.filter { it == E.Kind.memorySample || it == E.Kind.memoryPressure }
        assertEquals(listOf(E.Kind.memorySample, E.Kind.memorySample, E.Kind.memoryPressure), kinds)
    }

    @Test fun scenePhaseCarriesSecondsSinceTheLastChange() {
        var now = 1_000_000L
        val rt = DiagnosticsRuntime(tempDirectory(), "9", null, environment = env, clock = { now })
        rt.recordScenePhase(E.ScenePhase.active)
        now += 12_400
        rt.recordScenePhase(E.ScenePhase.background)
        val phases = rt.ring.read().filter { it.kind == E.Kind.scenePhase }
        assertNull(phases[0].sinceSeconds)
        assertEquals(12, phases[1].sinceSeconds)
    }

    @Test fun leavingTheFrontKeepsTheFootprintInTheMarker() {
        val rt = runtime(probe = Probe(64L shl 20))
        rt.setForeground(false)
        assertEquals(64L shl 20, rt.marker.read()?.backgroundFootprint)
        assertTrue(rt.ring.read().any { it.kind == E.Kind.memoryBackground })
        rt.setForeground(true)
        assertNull(rt.marker.read()?.backgroundedAt)
    }

    @Test fun storeKeepsRawAndRedactedApartAndDeletesBoth() {
        val store = DiagnosticsStore(tempDirectory())
        store.prepare()
        val record = Fixtures.record
        store.saveRaw(record)
        assertEquals(listOf(record.id), store.rawIDs())
        val redacted = Redactor().redact(record)
        store.saveRedacted(redacted)
        assertEquals(redacted, store.loadRedacted(record.id))
        assertFalse(File(store.redacted, "${record.id}.json").readText().contains("alice"))
        store.deleteAll()
        assertTrue(store.rawIDs().isEmpty())
        assertNull(store.loadRedacted(record.id))
    }

    @Test fun theNewestRawRecordIsRedactedWithTheNamesGiven() {
        val rt = runtime()
        rt.store.saveRaw(Fixtures.record)
        val redacted = rt.latestRedacted(listOf(Fixtures.tribeName), listOf("Alice Nakamoto"))!!
        assertFalse(redacted.rendered().contains("Alice"))
        assertTrue(redacted.exceptionReason!!.contains(Fixtures.tribeName))
        assertNotNull(rt.store.loadRedacted(redacted.id))
    }

    @Test fun anIdFromDiskThatIsNotOursIsRefused() {
        val store = DiagnosticsStore(tempDirectory())
        store.prepare()
        assertNull(store.loadRaw("../x"))
        store.saveRaw(Fixtures.record.copy(id = "../evil"))
        assertTrue(store.rawIDs().isEmpty())
    }

    @Test fun lockReasonsAreNamedAsOnIos() {
        assertEquals(E.LockReason.biometricCheck, LockTrace.reason(LockDecision.Step.BiometricCover, 24))
        assertEquals(E.LockReason.everyOpen, LockTrace.reason(LockDecision.Step.AskPin, 0))
        assertEquals(E.LockReason.idleTimeout, LockTrace.reason(LockDecision.Step.AskPin, 24))
        assertEquals(E.LockReason.rememberedPIN, LockTrace.reason(LockDecision.Step.OpenWithRememberedPin, 24))
    }

    @Test fun tribeTagIsTheFirst24BitsOfSha256() {
        // sha256("abc") = ba7816bf8f01cfea...
        assertEquals(0xba7816, Diagnostics.tribeTag("abc"))
    }

    @Test fun namesKeepTribesAndRemoveContactsAndMe() {
        val names = DiagnosticsNames(
            listOf(Triple("tribekey", "Builders", true), Triple("ckey", "Alice", false), Triple("c2", null, false)), "Me",
        )
        assertEquals(listOf("Builders", "tribekey"), names.allow)
        assertEquals(listOf("Alice", "Me"), names.knownNames)
    }

    @Test fun screenTextHasTheTimeThenThePreviousRunThenTheEvents() {
        val report = DiagnosticsReport(E.PreviousRunKind.clean, false, "1.0", null, listOf(E(E.Kind.launch, timestamp = 0)))
        val text = DiagnosticsExport.screenText(now = 0, report = report)
        assertTrue(text.startsWith("Now: 1970-01-01T00:00:00Z\nPrevious run: clean\nVersion: 1.0\n\nRecent events (newest last):\n"))
        assertTrue(text.contains("1970-01-01T00:00:00Z launch"))
    }
}
