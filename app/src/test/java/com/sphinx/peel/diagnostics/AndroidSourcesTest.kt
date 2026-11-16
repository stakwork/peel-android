package com.sphinx.peel.diagnostics

import com.sphinx.peel.diagnostics.DiagnosticEvent as E
import java.io.File
import java.io.FileNotFoundException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// The parts with no iOS test because iOS has other sources: the crash handler's record, the system's
/// exit entries (MetricKit's stand-in), the error kinds, and the JSON the files are written in.
class AndroidSourcesTest {
    private val meta = CrashRecordBuilder.Meta("com.sphinx.peel", "1.0", "Android 15", "AP1A", "Google-Pixel_8", "com.sphinx.peel")

    @Test fun aJavaCrashBecomesARecordWithItsFramesAndCauses() {
        val error = IllegalStateException("bad state for Alice", RuntimeException("inner"))
        val record = CrashRecordBuilder.build(error, Thread.currentThread(), meta, emptyList(), now = 5_000, id = "id-1")
        assertEquals("java.lang.IllegalStateException", record.exceptionName)
        assertTrue(record.exceptionReason!!.contains("Caused by: java.lang.RuntimeException: inner"))
        assertTrue(record.threads[0].crashed)
        assertTrue(record.threads[0].frames[0].symbol!!.contains("aJavaCrashBecomesARecordWithItsFramesAndCauses"))
        assertTrue(record.threads[0].frames[0].offset!! > 0)
        // It survives its own file, and the redactor takes the name out of it.
        assertEquals(record, CrashRecord.fromJson(record.toJson()))
        val shown = Redactor(knownNames = listOf("Alice")).redact(record).rendered()
        assertFalse(shown.contains("Alice"))
        assertTrue(shown.contains("Exception Name: java.lang.IllegalStateException"))
        assertTrue(shown.contains("Report ID: id-1"))
    }

    @Test fun aCrashRecordTravelsThroughTheStoreLikeIosPendingReport() {
        val store = DiagnosticsStore(tempDirectory())
        store.prepare()
        val record = CrashRecordBuilder.build(RuntimeException("x"), Thread.currentThread(), meta, listOf(E(E.Kind.launch)), 1, id = "abc-1")
        store.saveRaw(record)
        store.pendingFile.writeText(record.id)
        assertEquals(listOf("abc-1"), store.rawIDs())
        assertEquals("abc-1", store.pendingFile.readText())
        assertEquals(1, store.loadRaw("abc-1")!!.events.size)
        store.deleteAll()
        assertFalse(store.pendingFile.exists())
    }

    private fun exit(reason: Int, status: Int = 0, importance: Int = 400, at: Long = 10) = ExitRecord(at, reason, status, importance, "d")

    @Test fun exitReasonsFallInMetricKitsCategories() {
        assertEquals(AppExit.bgMemoryPressure, ExitInfoMapping.category(exit(ExitInfoMapping.REASON_LOW_MEMORY)))
        assertEquals(AppExit.fgMemoryResourceLimit, ExitInfoMapping.category(exit(ExitInfoMapping.REASON_LOW_MEMORY, importance = 100)))
        assertEquals(AppExit.bgWatchdog, ExitInfoMapping.category(exit(ExitInfoMapping.REASON_ANR)))
        assertEquals(AppExit.fgBadAccess, ExitInfoMapping.category(exit(ExitInfoMapping.REASON_CRASH_NATIVE, status = 11, importance = 100)))
        assertEquals(AppExit.bgIllegalInstruction, ExitInfoMapping.category(exit(ExitInfoMapping.REASON_SIGNALED, status = 4)))
        assertEquals(AppExit.fgAbnormal, ExitInfoMapping.category(exit(ExitInfoMapping.REASON_CRASH, importance = 100)))
        assertEquals(AppExit.bgNormal, ExitInfoMapping.category(exit(ExitInfoMapping.REASON_USER_REQUESTED)))
        assertEquals(mapOf(AppExit.bgNormal to 2, AppExit.bgWatchdog to 1), ExitInfoMapping.counts(listOf(exit(1), exit(10), exit(6))))
    }

    @Test fun crashesAndAnrsAreDiagnosticsAndOthersAreNot() {
        assertEquals(E.MetricDiagnosticKind.crash, ExitInfoMapping.metricKind(exit(ExitInfoMapping.REASON_CRASH_NATIVE)))
        assertEquals(E.MetricDiagnosticKind.hang, ExitInfoMapping.metricKind(exit(ExitInfoMapping.REASON_ANR)))
        assertNull(ExitInfoMapping.metricKind(exit(ExitInfoMapping.REASON_LOW_MEMORY)))
    }

    @Test fun theRunsEndIsTheNewestExitAfterItStarted() {
        val exits = listOf(exit(ExitInfoMapping.REASON_LOW_MEMORY, at = 50), exit(ExitInfoMapping.REASON_CRASH_NATIVE, at = 200), exit(ExitInfoMapping.REASON_EXIT_SELF, at = 100))
        assertEquals(200L, ExitInfoMapping.endOf(exits, startedAt = 90)?.timestamp)
        assertNull(ExitInfoMapping.endOf(exits, startedAt = 300))
        assertTrue(ExitInfoMapping.endedNormally(exit(ExitInfoMapping.REASON_USER_STOPPED)))
        assertFalse(ExitInfoMapping.endedNormally(exit(ExitInfoMapping.REASON_LOW_MEMORY)))
    }

    @Test fun aNativeCrashBecomesARecordFromTheSystemsEntry() {
        val base = CrashRecord(id = "", appIdentifier = "com.sphinx.peel")
        val record = ExitInfoMapping.nativeCrashRecord(ExitRecord(77, ExitInfoMapping.REASON_CRASH_NATIVE, 11, 100, "Native crash of /data/user/0/x"), base)
        assertEquals("SIGSEGV", record.signal)
        assertEquals("Native crash", record.exceptionName)
        assertTrue(record.id.isNotEmpty())
        assertEquals(77L, record.timestamp)
        assertFalse(Redactor().redact(record).rendered().contains("/data/user"))
    }

    @Test fun errorsFallInTheSameKindsAsOnIos() {
        assertEquals(E.ErrorKind.network, ErrorKinds.of(SocketTimeoutException()))
        assertEquals(E.ErrorKind.secureConnection, ErrorKinds.of(SSLHandshakeException("x")))
        assertEquals(E.ErrorKind.cancelled, ErrorKinds.of(kotlinx.coroutines.CancellationException("x")))
        assertEquals(E.ErrorKind.file, ErrorKinds.of(FileNotFoundException("x")))
        assertEquals(E.ErrorKind.connection, ErrorKinds.of(IllegalStateException("Not connected.")))
        assertEquals(E.ErrorKind.network, ErrorKinds.of(RuntimeException("wrapped", java.net.UnknownHostException("h"))))
        assertEquals(E.ErrorKind.other, ErrorKinds.of(IllegalArgumentException("x")))
    }

    @Test fun miniJsonRoundTripsWhatTheFilesHold() {
        val value = mapOf("b" to listOf(1L, 2.5, "x\"\n\u0001"), "a" to mapOf("t" to true), "n" to null)
        val text = MiniJson.write(value)
        assertEquals("""{"a":{"t":true},"b":[1,2.5,"x\"\n\u0001"]}""", text)
        assertEquals(mapOf("a" to mapOf("t" to true), "b" to listOf(1L, 2.5, "x\"\n\u0001")), MiniJson.parse(text))
        assertNull(MiniJson.parse("""{"kind":"err"""))
        assertNull(MiniJson.parse("nonsense"))
    }
}
