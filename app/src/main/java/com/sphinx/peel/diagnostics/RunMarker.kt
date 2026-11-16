package com.sphinx.peel.diagnostics

import java.io.File
import java.util.UUID

/// How the run before this one ended (iOS `PreviousRun`).
sealed class PreviousRun {
    /// Quit normally, or there was no run before.
    object Clean : PreviousRun()
    /// A crash record was left; its id is the record's.
    data class Crashed(val reportID: String) : PreviousRun()
    /// Gone without quitting or a report while in front: killed.
    object KilledForeground : PreviousRun()
    /// The same while in the background: the system's doing, expected.
    object KilledBackground : PreviousRun()
    /// A marker that could not be read.
    object Unknown : PreviousRun()

    val kind: DiagnosticEvent.PreviousRunKind
        get() = when (this) {
            Clean -> DiagnosticEvent.PreviousRunKind.clean
            is Crashed -> DiagnosticEvent.PreviousRunKind.crashed
            KilledForeground -> DiagnosticEvent.PreviousRunKind.killedForeground
            KilledBackground -> DiagnosticEvent.PreviousRunKind.killedBackground
            Unknown -> DiagnosticEvent.PreviousRunKind.unknown
        }

    /// Worth telling the person: a crash, or a kill while they were using Peel.
    val isProblem: Boolean get() = this is Crashed || this == KilledForeground
}

/// The marker file: `running` from launch, `clean` once the app terminates normally. Android has no
/// normal-termination callback for a process, so `clean` is written only where the app itself ends
/// (see `Diagnostics.markCleanExit`'s callers and PORT_NOTES).
data class RunMarker(
    val launchID: String,
    val appVersion: String,
    /// Milliseconds since 1970 (the iOS marker keeps seconds; the file here is its own).
    val startedAt: Long,
    val phase: Phase,
    val lastKnownState: State,
    /// What it ran on: the device's boot, the OS, and its own build (a new install changes the date).
    val bootTime: Long? = null,
    val osVersion: String? = null,
    val binaryStamp: Long? = null,
    /// Out of the front: since when, the footprint then, and when it was last known alive.
    val backgroundedAt: Long? = null,
    val backgroundFootprint: Long? = null,
    val lastAliveAt: Long? = null,
    val memoryWarnings: Int? = null,
    val backgroundTaskExpired: Boolean? = null,
) {
    enum class Phase { running, clean }
    enum class State { foreground, background }

    fun toJson(): String = MiniJson.write(
        mapOf(
            "launchID" to launchID, "appVersion" to appVersion, "startedAt" to startedAt / 1000.0, "phase" to phase.name,
            "lastKnownState" to lastKnownState.name, "bootTime" to bootTime?.let { it / 1000.0 }, "osVersion" to osVersion,
            "binaryStamp" to binaryStamp?.let { it / 1000.0 }, "backgroundedAt" to backgroundedAt?.let { it / 1000.0 },
            "backgroundFootprint" to backgroundFootprint, "lastAliveAt" to lastAliveAt?.let { it / 1000.0 },
            "memoryWarnings" to memoryWarnings, "backgroundTaskExpired" to backgroundTaskExpired,
        ),
    )

    companion object {
        private fun ms(m: Map<String, Any?>, key: String) = m.dbl(key)?.let { Math.round(it * 1000) }

        fun fromJson(text: String): RunMarker? {
            val m = MiniJson.parseObject(text) ?: return null
            return RunMarker(
                launchID = m.str("launchID") ?: return null,
                appVersion = m.str("appVersion") ?: return null,
                startedAt = ms(m, "startedAt") ?: return null,
                phase = m.enum<Phase>("phase") ?: return null,
                lastKnownState = m.enum<State>("lastKnownState") ?: return null,
                bootTime = ms(m, "bootTime"), osVersion = m.str("osVersion"), binaryStamp = ms(m, "binaryStamp"),
                backgroundedAt = ms(m, "backgroundedAt"), backgroundFootprint = m.long("backgroundFootprint"),
                lastAliveAt = ms(m, "lastAliveAt"), memoryWarnings = m.int("memoryWarnings"),
                backgroundTaskExpired = m.bool("backgroundTaskExpired"),
            )
        }
    }
}

/// The device and the build a run is on, read at launch (iOS `RunEnvironment`).
data class RunEnvironment(val bootTime: Long?, val osVersion: String, val binaryStamp: Long?)

/// What a run that never quit left in its marker, read at the next launch: the likeliest cause and what
/// it was doing. Only enums and numbers (iOS `PreviousRunDetail`).
data class PreviousRunDetail(
    val cause: DiagnosticEvent.ExitCause,
    /// Known alive out of the front for this long (from leaving it to the last sign of life).
    val aliveInBackground: Int?,
    /// From the last sign of life to this launch: the kill came somewhere in it.
    val sinceAlive: Int?,
    val backgroundFootprint: Long?,
    val memoryWarnings: Int,
    val backgroundTaskExpired: Boolean,
) {
    companion object {
        /// A boot clock read twice can differ by a little; a restart moves it by far more.
        const val BOOT_TOLERANCE_MS = 60_000L

        /// Null for a run that quit, or no marker.
        fun of(marker: RunMarker?, now: Long = System.currentTimeMillis(), environment: RunEnvironment): PreviousRunDetail? {
            if (marker == null || marker.phase != RunMarker.Phase.running) return null
            val was = marker.bootTime
            val boot = environment.bootTime
            val stamp = marker.binaryStamp
            val cause = when {
                was != null && boot != null && Math.abs(was - boot) > BOOT_TOLERANCE_MS ->
                    if (marker.osVersion != null && marker.osVersion != environment.osVersion) DiagnosticEvent.ExitCause.osUpdated
                    else DiagnosticEvent.ExitCause.restarted
                stamp != null && environment.binaryStamp != null && stamp != environment.binaryStamp -> DiagnosticEvent.ExitCause.reinstalled
                else -> DiagnosticEvent.ExitCause.system
            }
            val alive = marker.lastAliveAt
            val left = marker.backgroundedAt
            return PreviousRunDetail(
                cause = cause,
                aliveInBackground = if (left != null && alive != null) maxOf(((alive - left) / 1000).toInt(), 0) else null,
                sinceAlive = maxOf(((now - (alive ?: marker.startedAt)) / 1000).toInt(), 0),
                backgroundFootprint = marker.backgroundFootprint,
                memoryWarnings = marker.memoryWarnings ?: 0,
                backgroundTaskExpired = marker.backgroundTaskExpired ?: false,
            )
        }
    }
}

class RunMarkerStore(private val file: File) {
    private val lock = Any()
    private var current: RunMarker? = null

    /// Classifies the last run and starts this one's marker.
    fun begin(
        launchID: String = UUID.randomUUID().toString(), appVersion: String, crashReportID: String?,
        state: RunMarker.State = RunMarker.State.foreground, now: Long = System.currentTimeMillis(),
        environment: RunEnvironment,
    ): PreviousRun {
        val data = runCatching { file.readText() }.getOrNull()
        val marker = data?.let { RunMarker.fromJson(it) }
        val previous = classify(marker, markerReadable = data == null || marker != null, crashReportID = crashReportID)
        var next = RunMarker(launchID, appVersion, now, RunMarker.Phase.running, state)
            .copy(bootTime = environment.bootTime, osVersion = environment.osVersion, binaryStamp = environment.binaryStamp)
        if (state == RunMarker.State.background) next = next.copy(backgroundedAt = now, lastAliveAt = now)
        synchronized(lock) { current = next }
        save(next)
        return previous
    }

    /// Into the front or out of it; out, with the footprint then.
    fun setState(state: RunMarker.State, footprint: Long? = null, now: Long = System.currentTimeMillis()) = update {
        if (state == RunMarker.State.background) {
            it.copy(lastKnownState = state, backgroundedAt = now, lastAliveAt = now, backgroundFootprint = footprint)
        } else {
            it.copy(lastKnownState = state, backgroundedAt = null)
        }
    }

    /// Something happened out of the front: the run was alive then. At most once every few seconds.
    fun markAlive(now: Long = System.currentTimeMillis()) = update { marker ->
        if (marker.lastKnownState != RunMarker.State.background) return@update marker
        val last = marker.lastAliveAt
        if (last != null && now - last < 5_000) marker else marker.copy(lastAliveAt = now)
    }

    fun noteMemoryWarning() = update { it.copy(memoryWarnings = (it.memoryWarnings ?: 0) + 1) }

    fun noteBackgroundTaskExpired() = update { it.copy(backgroundTaskExpired = true) }

    /// Normal termination.
    fun markClean() = update { it.copy(phase = RunMarker.Phase.clean) }

    fun read(): RunMarker? = runCatching { file.readText() }.getOrNull()?.let { RunMarker.fromJson(it) }

    private fun update(change: (RunMarker) -> RunMarker) {
        val saved = synchronized(lock) {
            val before = current ?: return
            val after = change(before)
            if (after == before) return
            current = after
            after
        }
        save(saved)
    }

    private fun save(marker: RunMarker) {
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(marker.toJson())
            if (!temp.renameTo(file)) { file.writeText(marker.toJson()); temp.delete() }
        }
    }

    companion object {
        /// The previous run's end, from the marker left by it and the crash record it may have left.
        fun classify(marker: RunMarker?, markerReadable: Boolean = true, crashReportID: String?): PreviousRun {
            if (crashReportID != null) return PreviousRun.Crashed(crashReportID)
            if (!markerReadable) return PreviousRun.Unknown
            if (marker == null) return PreviousRun.Clean
            return when (marker.phase) {
                RunMarker.Phase.clean -> PreviousRun.Clean
                RunMarker.Phase.running ->
                    if (marker.lastKnownState == RunMarker.State.foreground) PreviousRun.KilledForeground else PreviousRun.KilledBackground
            }
        }
    }
}
