package com.sphinx.peel.diagnostics

import java.util.UUID

/// One entry of Android's `ApplicationExitInfo` as plain values (the stand-in for what iOS reads from
/// MetricKit's `MXAppExitMetric` and `MXDiagnosticPayload`). `reason` is `ApplicationExitInfo.REASON_*`,
/// `status` the signal or exit code, `importance` `RunningAppProcessInfo.IMPORTANCE_*` at the end.
data class ExitRecord(val timestamp: Long, val reason: Int, val status: Int, val importance: Int, val description: String?)

/// What the system's exit reasons mean for the diagnostics: pure, so JVM-tested (`ExitInfoMappingTest`).
/// The reason numbers are the platform's constants, written out so no Android class is needed here.
object ExitInfoMapping {
    const val REASON_EXIT_SELF = 1
    const val REASON_SIGNALED = 2
    const val REASON_LOW_MEMORY = 3
    const val REASON_CRASH = 4
    const val REASON_CRASH_NATIVE = 5
    const val REASON_ANR = 6
    const val REASON_EXCESSIVE_RESOURCE_USAGE = 9
    const val REASON_USER_REQUESTED = 10
    const val REASON_USER_STOPPED = 11
    const val REASON_PACKAGE_UPDATED = 16
    private const val SIGILL = 4
    private const val SIGSEGV = 11
    private const val SIGBUS = 7
    /// `IMPORTANCE_FOREGROUND_SERVICE`: at this or above (a lower number) the process was in front.
    private const val FRONT_IMPORTANCE = 125

    fun inFront(exit: ExitRecord) = exit.importance <= FRONT_IMPORTANCE

    /// The category MetricKit would have put it in.
    fun category(exit: ExitRecord): AppExit {
        val front = inFront(exit)
        fun pick(fg: AppExit, bg: AppExit) = if (front) fg else bg
        return when (exit.reason) {
            REASON_EXIT_SELF, REASON_USER_REQUESTED, REASON_USER_STOPPED, REASON_PACKAGE_UPDATED -> pick(AppExit.fgNormal, AppExit.bgNormal)
            REASON_LOW_MEMORY -> pick(AppExit.fgMemoryResourceLimit, AppExit.bgMemoryPressure)
            REASON_EXCESSIVE_RESOURCE_USAGE -> pick(AppExit.fgMemoryResourceLimit, AppExit.bgCPUResourceLimit)
            REASON_ANR -> pick(AppExit.fgWatchdog, AppExit.bgWatchdog)
            REASON_SIGNALED, REASON_CRASH_NATIVE -> when (exit.status) {
                SIGSEGV, SIGBUS -> pick(AppExit.fgBadAccess, AppExit.bgBadAccess)
                SIGILL -> pick(AppExit.fgIllegalInstruction, AppExit.bgIllegalInstruction)
                else -> pick(AppExit.fgAbnormal, AppExit.bgAbnormal)
            }
            else -> pick(AppExit.fgAbnormal, AppExit.bgAbnormal)
        }
    }

    fun counts(exits: List<ExitRecord>): Map<AppExit, Int> = exits.groupingBy { category(it) }.eachCount()

    /// MetricKit's diagnostics have a kind: a native crash, or a hang (an ANR). Others have none.
    fun metricKind(exit: ExitRecord): DiagnosticEvent.MetricDiagnosticKind? = when (exit.reason) {
        REASON_CRASH_NATIVE, REASON_CRASH -> DiagnosticEvent.MetricDiagnosticKind.crash
        REASON_ANR -> DiagnosticEvent.MetricDiagnosticKind.hang
        else -> null
    }

    /// A run the person or the app ended on purpose, not a kill: not a problem to tell them about.
    fun endedNormally(exit: ExitRecord) = exit.reason in setOf(REASON_EXIT_SELF, REASON_USER_REQUESTED, REASON_USER_STOPPED, REASON_PACKAGE_UPDATED)

    /// How the run that started at `startedAt` ended: the newest exit at or after it.
    fun endOf(exits: List<ExitRecord>, startedAt: Long): ExitRecord? =
        exits.filter { it.timestamp >= startedAt }.maxByOrNull { it.timestamp }

    fun signalName(signal: Int): String? = when (signal) {
        4 -> "SIGILL"; 6 -> "SIGABRT"; 7 -> "SIGBUS"; 8 -> "SIGFPE"; 9 -> "SIGKILL"; 11 -> "SIGSEGV"; 13 -> "SIGPIPE"
        else -> null
    }

    /// A native crash (no Java handler runs for one) as a raw record, made at the next launch from the
    /// system's exit entry: the signal and the system's own words, no frames.
    fun nativeCrashRecord(exit: ExitRecord, base: CrashRecord): CrashRecord = base.copy(
        id = UUID.randomUUID().toString(), timestamp = exit.timestamp,
        signal = signalName(exit.status) ?: "SIG${exit.status}", exceptionName = "Native crash",
        exceptionReason = exit.description,
    )
}
