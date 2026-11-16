package com.sphinx.peel.diagnostics

import com.sphinx.peel.diagnostics.DiagnosticEvent as E
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// iOS TraceEventTests and ClearEventsTests.
class TraceEventTest {
    private fun ringFile() = File(tempDirectory(), "ring")

    @Test fun newFieldsRoundTripThroughTheRing() {
        val file = ringFile()
        val network = E(E.Kind.network, timestamp = 5000, networkStatus = E.NetworkStatus.satisfied, networkInterface = E.NetworkInterface.cellular, isExpensive = true, isConstrained = false)
        val exits = E(E.Kind.appExits, timestamp = 6000, exitCounts = mapOf("bgMemoryPressure" to 3, "fgNormal" to 1))
        val lock = E(E.Kind.lock, timestamp = 7000, lockReason = E.LockReason.idleTimeout, idleSeconds = 90_000, timeoutHours = 24)
        val ring = DiagnosticsRing(file)
        for (event in listOf(network, exits, lock)) ring.append(event)
        assertEquals(listOf(network, exits, lock), DiagnosticsRing(file).read())
    }

    @Test fun oldRingLinesStillDecode() {
        val events = DiagnosticsRing.decode("""{"kind":"launch","timestamp":100,"count":1,"lastAction":"launch"}""".toByteArray() + 0x0A)
        assertEquals(1, events.size)
        assertEquals(E.Kind.launch, events[0].kind)
        assertNull(events[0].launchedInFront)
        assertNull(events[0].exitCounts)
    }

    @Test fun linesNameEveryNewFieldWithoutFreeText() {
        val event = E(E.Kind.launch, timestamp = 0, launchedInFront = false, uptimeSeconds = 42)
        assertTrue(event.line.contains("background-launch"))
        assertTrue(event.line.contains("uptime=42s"))
        assertTrue(E(E.Kind.scenePhase, scenePhase = E.ScenePhase.background, sinceSeconds = 12).line.contains("phase=background since=12s"))
        val push = E(E.Kind.pushFetch, pushStage = E.PushFetchStage.expired, elapsedSeconds = 29.9)
        assertTrue(push.line.contains("push=expired"))
        assertTrue(push.line.contains("elapsed=29.9s"))
        assertTrue(E(E.Kind.appExits, exitCounts = mapOf(AppExit.bgWatchdog.name to 2)).line.contains("bgWatchdog=2"))
        assertTrue(E(E.Kind.metricDiagnostic, metricKind = E.MetricDiagnosticKind.crash, signal = 9).line.contains("metric=crash signal=9"))
    }

    @Test fun linesNameTheConnectionRecords() {
        assertTrue(E(E.Kind.foregroundPing, pingAnswered = false, milliseconds = 120).line.contains("foregroundPing ping=failed took=120ms"))
        assertTrue(E(E.Kind.foregroundDecision, foregroundAction = E.ForegroundAction.reconnect, foregroundCause = E.ForegroundCause.away, awaySeconds = 61).line
            .contains("decision=reconnect cause=away away=61s"))
        assertTrue(E(E.Kind.forcedReconnect, reconnectReason = E.ReconnectReason.stall).line.contains("forcedReconnect reason=stall"))
        assertTrue(E(E.Kind.transportLifecycle, lifecycleStage = E.LifecycleStage.opened, liveTransports = 2).line.contains("stage=opened live=2"))
        assertTrue(E(E.Kind.subscribed, subscribedTopics = 3, subscribedOk = true).line.contains("topics=3 subscribed=ok"))
        val missed = E(E.Kind.selfCheck, cursorFrom = 7, selfCheckTrigger = E.SelfCheckTrigger.periodic, selfCheckOutcome = E.SelfCheckOutcome.missed, selfCheckMissed = 2, highestIndex = 9)
        assertTrue(missed.line.contains("selfCheck from=7 trigger=periodic outcome=missed missed=2 highest=9"))
        val tribe = E(E.Kind.tribeTrace, tribeTag = 0xabc, tribeStage = E.TribeTraceStage.held, tribeMiss = E.TribeMiss.noHost)
        assertTrue(tribe.line.contains("tribe=000abc stage=held miss=noHost"))
    }

    @Test fun reportRendersNewEventsAndThePreviousRun() {
        val report = DiagnosticsReport(E.PreviousRunKind.killedBackground, false, null, null, listOf(E(E.Kind.lock, lockReason = E.LockReason.coldLaunch)))
        val text = report.rendered()
        assertTrue(text.contains("Previous run: killedBackground"))
        assertTrue(text.contains("lock=coldLaunch"))
    }

    @Test fun linesNameTheCoreFailuresAndRecoveries() {
        val failure = E(E.Kind.coreFailure, timestamp = 0, failureSite = E.CoreFailureSite.receive, messageKind = "batch", errorDetail = "core refused the page")
        assertTrue(failure.line.contains("coreFailure site=receive msg=batch detail=\"core refused the page\""))
        val recovery = E(E.Kind.coreRecovery, timestamp = 0, recoveryReason = E.CoreRecoveryReason.pageFailed, recoveryStage = E.CoreRecoveryStage.reloadState)
        assertTrue(recovery.line.contains("coreRecovery recovery=pageFailed stage=reloadState"))
        val error = E(E.Kind.error, errorKind = E.ErrorKind.core, errorDetail = "x")
        assertTrue(error.line.contains("error=core"))
        assertTrue(error.line.contains("detail=\"x\""))
        assertFalse(E(E.Kind.error, errorKind = E.ErrorKind.core).line.contains("detail="))
    }

    @Test fun theDetailRoundTripsThroughTheRingAndOldLinesDecodeWithout() {
        val file = ringFile()
        val failure = E(E.Kind.coreFailure, timestamp = 5000, failureSite = E.CoreFailureSite.cameOnline, errorDetail = "boom")
        DiagnosticsRing(file).append(failure)
        assertEquals(listOf(failure), DiagnosticsRing(file).read())
        val events = DiagnosticsRing.decode("""{"kind":"error","timestamp":100,"count":1,"errorKind":"core"}""".toByteArray() + 0x0A)
        assertEquals(1, events.size)
        assertNull(events[0].errorDetail)
    }

    @Test fun redactingRemovesKeysInvoicesUrlsAndEmails() {
        val key = "ab".repeat(33)
        val hash = "0f".repeat(32)
        val invoice = "lnbc" + "q".repeat(40)
        val url = "https://" + "example.com/path/to?token=1"
        val email = "someone" + "@" + "example.org"
        val redacted = Diagnostics.redact("bad key $key hash $hash pay $invoice at $url mail $email")
        for (leak in listOf(key, hash, invoice, "path/to", "token=1", email)) assertFalse(leak, redacted.contains(leak))
        assertTrue(redacted.contains("https://example.com"))
        assertTrue(redacted.contains("<redacted>"))
        assertTrue(redacted.startsWith("bad key"))
    }

    @Test fun redactingRemovesLongTokensAndKeepsShortWords() {
        val token = "Zx9".repeat(12)
        val redacted = Diagnostics.redact("batch: could not read $token (server index 7)")
        assertFalse(redacted.contains(token))
        assertTrue(redacted.contains("could not read"))
        assertTrue(redacted.contains("server index 7"))
    }

    @Test fun redactingCollapsesWhitespaceAndCapsTheLength() {
        assertEquals("a b c", Diagnostics.redact("a \n\n  b\t c"))
        assertEquals(Diagnostics.DETAIL_LIMIT, Diagnostics.redact("word ".repeat(200)).length)
        assertEquals(200, Diagnostics.DETAIL_LIMIT)
    }

    @Test fun everyKindIsUnique() {
        assertEquals(E.Kind.values().size, E.Kind.values().map { it.name }.toSet().size)
    }

    @Test fun callAudioRoundTripsAndHasNoFreeText() {
        val file = ringFile()
        val event = E(
            E.Kind.callAudio, timestamp = 9000, callAudioStage = E.CallAudioStage.micState, micTrigger = E.MicTrigger.foreground,
            micEnabled = true, micPublicationMuted = false, wantsMic = true, audioCategory = E.AudioCategory.playAndRecord,
        )
        DiagnosticsRing(file).append(event)
        assertEquals(listOf(event), DiagnosticsRing(file).read())
        for (part in listOf("callAudio", "stage=micState", "trigger=foreground", "micEnabled=true", "micMuted=false", "wantsMic=true")) assertTrue(part, event.line.contains(part))
    }

    // ClearEventsTests

    @Test fun clearingLeavesPreviousRunAndEmptiesTheList() {
        val file = ringFile()
        val ring = DiagnosticsRing(file)
        ring.append(E(E.Kind.error, errorKind = E.ErrorKind.file))
        ring.clear()
        assertTrue(ring.read().isEmpty())
        assertTrue(DiagnosticsRing(file).read().isEmpty())
        val report = DiagnosticsReport(E.PreviousRunKind.killedBackground, false, null, null, ring.read())
        assertTrue(report.rendered().contains("Previous run: killedBackground"))
    }

    @Test fun renderedLimitKeepsTheNewestAndTimestampsAreISO() {
        val events = (0 until 100).map { E(E.Kind.scenePhase, timestamp = it * 1_000_000L, count = it + 1) }
        val report = DiagnosticsReport(E.PreviousRunKind.clean, false, null, null, events)
        fun lines(text: String) = text.split("\n").count { it.contains("scenePhase") }
        assertEquals(60, lines(report.rendered()))
        assertEquals(100, lines(report.rendered(limit = 500)))
        assertEquals(5, lines(report.rendered(limit = 5)))
        assertTrue(report.rendered(limit = 1).contains("1970-01-"))
    }

    @Test fun clearingBeforeStartIsSafe() {
        Diagnostics.clearEvents()
        Diagnostics.note(E.ErrorKind.other)
        Diagnostics.setAction(E.Action.send)
        assertEquals(PreviousRun.Clean, Diagnostics.previousRun())
    }
}
