package com.sphinx.peel.diagnostics

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// iOS MarkerTests.
class RunMarkerTest {
    private fun file() = File(tempDirectory(), "marker.json")
    private val start = 1_000_000_000L
    private fun env(boot: Long = 0, os: String = "26.1", stamp: Long = 0) = RunEnvironment(boot, os, stamp)
    private fun begin(store: RunMarkerStore, crash: String? = null, state: RunMarker.State = RunMarker.State.foreground) =
        store.begin(appVersion = "1", crashReportID = crash, state = state, environment = env())

    /// A run left in the background, alive 30 s after leaving the front, with one memory warning.
    private fun killedRun(): RunMarker? {
        val first = RunMarkerStore(file())
        first.begin(appVersion = "1", crashReportID = null, now = start, environment = env())
        first.setState(RunMarker.State.background, footprint = 50L shl 20, now = start + 60_000)
        first.markAlive(now = start + 90_000)
        first.noteMemoryWarning()
        return first.read()
    }

    @Test fun aKillWithNothingElseChangedIsTheSystems() {
        val detail = PreviousRunDetail.of(killedRun(), now = start + 1_090_000, environment = env())!!
        assertEquals(DiagnosticEvent.ExitCause.system, detail.cause)
        assertEquals(30, detail.aliveInBackground)
        assertEquals(1_000, detail.sinceAlive)
        assertEquals(50L shl 20, detail.backgroundFootprint)
        assertEquals(1, detail.memoryWarnings)
        assertFalse(detail.backgroundTaskExpired)
    }

    @Test fun aNewBootIsARestartAndANewOSAnUpdate() {
        assertEquals(DiagnosticEvent.ExitCause.restarted, PreviousRunDetail.of(killedRun(), environment = env(boot = 5_000_000))?.cause)
        assertEquals(DiagnosticEvent.ExitCause.osUpdated, PreviousRunDetail.of(killedRun(), environment = env(boot = 5_000_000, os = "26.2"))?.cause)
        // The boot clock read again drifts a little: not a restart.
        assertEquals(DiagnosticEvent.ExitCause.system, PreviousRunDetail.of(killedRun(), environment = env(boot = 2_000))?.cause)
    }

    @Test fun aNewBuildIsAReinstall() {
        assertEquals(DiagnosticEvent.ExitCause.reinstalled, PreviousRunDetail.of(killedRun(), environment = env(stamp = 99))?.cause)
    }

    @Test fun aRunThatQuitHasNoDetail() {
        val first = RunMarkerStore(file())
        begin(first)
        first.markClean()
        assertNull(PreviousRunDetail.of(first.read(), environment = env()))
    }

    @Test fun signsOfLifeComeOnlyOutOfTheFrontAndNotTooOften() {
        val first = RunMarkerStore(file())
        first.begin(appVersion = "1", crashReportID = null, now = start, environment = env())
        first.markAlive(now = start + 10_000)
        assertNull(first.read()?.lastAliveAt)
        first.setState(RunMarker.State.background, now = start + 20_000)
        first.markAlive(now = start + 22_000)
        assertEquals(start + 20_000, first.read()?.lastAliveAt)
        first.markAlive(now = start + 30_000)
        assertEquals(start + 30_000, first.read()?.lastAliveAt)
    }

    @Test fun anOlderMarkerStillReads() {
        val f = file()
        f.writeText("""{"launchID":"a","appVersion":"1","startedAt":1000000,"phase":"running","lastKnownState":"background"}""")
        assertEquals(PreviousRun.KilledBackground, begin(RunMarkerStore(f)))
    }

    @Test fun firstLaunchIsClean() = assertEquals(PreviousRun.Clean, begin(RunMarkerStore(file())))

    @Test fun cleanExit() {
        val f = file()
        val first = RunMarkerStore(f)
        begin(first)
        first.markClean()
        assertEquals(PreviousRun.Clean, begin(RunMarkerStore(f)))
    }

    @Test fun killedInFront() {
        val f = file()
        begin(RunMarkerStore(f))
        assertEquals(PreviousRun.KilledForeground, begin(RunMarkerStore(f)))
    }

    @Test fun backgroundLaunchIsKilledInBackground() {
        val f = file()
        begin(RunMarkerStore(f), state = RunMarker.State.background)
        val previous = begin(RunMarkerStore(f))
        assertEquals(PreviousRun.KilledBackground, previous)
        assertFalse(previous.isProblem)
    }

    @Test fun killedInBackgroundIsExpected() {
        val f = file()
        val first = RunMarkerStore(f)
        begin(first)
        first.setState(RunMarker.State.background)
        val previous = begin(RunMarkerStore(f))
        assertEquals(PreviousRun.KilledBackground, previous)
        assertFalse(previous.isProblem)
    }

    @Test fun comingBackToTheFrontIsRemembered() {
        val f = file()
        val first = RunMarkerStore(f)
        begin(first)
        first.setState(RunMarker.State.background)
        first.setState(RunMarker.State.foreground)
        assertEquals(PreviousRun.KilledForeground, begin(RunMarkerStore(f)))
    }

    @Test fun aReportMakesItCrashed() {
        val f = file()
        val first = RunMarkerStore(f)
        begin(first)
        first.setState(RunMarker.State.background)
        val previous = begin(RunMarkerStore(f), crash = "abc")
        assertEquals(PreviousRun.Crashed("abc"), previous)
        assertTrue(previous.isProblem)
    }

    @Test fun anUnreadableMarkerIsUnknown() {
        val f = file()
        f.writeText("not json")
        val store = RunMarkerStore(f)
        assertEquals(PreviousRun.Unknown, begin(store))
        // And the new run's marker replaced it.
        assertEquals(RunMarker.Phase.running, store.read()?.phase)
    }
}
