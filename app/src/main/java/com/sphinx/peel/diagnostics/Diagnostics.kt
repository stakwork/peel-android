package com.sphinx.peel.diagnostics

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import com.sphinx.peel.diagnostics.DiagnosticEvent as E

/// One launch's diagnostics (iOS `DiagnosticsRuntime`): the ring, the run marker and the store, on a
/// folder given by the caller. `Diagnostics` is the app's one instance. JVM-testable: the clock, the
/// memory probe and the environment come in as parameters.
class DiagnosticsRuntime(
    root: File,
    appVersion: String,
    crash: String?,
    state: RunMarker.State = RunMarker.State.foreground,
    environment: RunEnvironment,
    private val probe: MemoryProbe? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val uptimeSeconds: () -> Int = { 0 },
    private val mirror: (String) -> Unit = {},
) {
    val store = DiagnosticsStore(root)
    val ring: DiagnosticsRing
    val marker: RunMarkerStore
    val previous: PreviousRun
    private val lock = Any()
    private var action = E.Action.launch
    private var lastFootprint: Long? = null
    private var lastPhaseChange: Long? = null
    private var sampling: ScheduledFuture<*>? = null

    init {
        store.prepare()
        ring = DiagnosticsRing(store.ringFile)
        marker = RunMarkerStore(store.markerFile)
        val detail = PreviousRunDetail.of(marker.read(), clock(), environment)
        previous = marker.begin(appVersion = appVersion, crashReportID = crash, state = state, now = clock(), environment = environment)
        val front = state == RunMarker.State.foreground
        ring.append(E(E.Kind.launch, timestamp = clock(), lastAction = E.Action.launch, launchedInFront = front, uptimeSeconds = uptimeSeconds()))
        mirror("launch front=$front uptime=${uptimeSeconds()}s previous=${previous.kind.name}")
        ring.append(E(E.Kind.previousRun, timestamp = clock(), previousRun = previous.kind))
        // A kill (no crash record): why, most likely, and what the run was doing out of the front.
        if (detail != null && previous.kind != E.PreviousRunKind.crashed) {
            val event = E(
                E.Kind.previousRunDetail, timestamp = clock(), footprintBytes = detail.backgroundFootprint,
                exitCause = detail.cause, aliveInBackgroundSeconds = detail.aliveInBackground,
                sinceAliveSeconds = detail.sinceAlive, memoryWarnings = detail.memoryWarnings,
                backgroundTaskExpired = detail.backgroundTaskExpired,
            )
            ring.append(event)
            mirror(event.line)
        }
    }

    fun setAction(value: E.Action) = synchronized(lock) { action = value }
    private fun currentAction() = synchronized(lock) { action }

    fun note(kind: E.ErrorKind, detail: String? = null) {
        val current = currentAction()
        ring.append(E(E.Kind.error, timestamp = clock(), errorKind = kind, lastAction = current, errorDetail = detail))
        mirror("error ${kind.name} during ${current.name}" + (detail?.let { " detail=\"$it\"" } ?: ""))
    }

    fun sample(pressure: E.PressureLevel? = null) {
        val footprint = probe?.footprint() ?: return
        val record = synchronized(lock) {
            if (!MemoryPolicy.shouldRecord(lastFootprint, footprint, pressure != null)) false else { lastFootprint = footprint; true }
        }
        if (!record) return
        ring.append(
            E(
                if (pressure == null) E.Kind.memorySample else E.Kind.memoryPressure, timestamp = clock(),
                lastAction = currentAction(), footprintBytes = footprint, availableBytes = probe.available(), pressureLevel = pressure,
            ),
        )
        if (pressure != null) mirror("memory pressure ${pressure.name}")
    }

    /// Appends `event` and mirrors it, enum names and numbers only. Out of the front, it is also a sign
    /// the run was alive then (`RunMarker.lastAliveAt`).
    fun record(event: E) {
        ring.append(event)
        mirror(event.line)
        marker.markAlive(clock())
    }

    fun recordScenePhase(phase: E.ScenePhase) {
        val now = clock()
        val since = synchronized(lock) {
            val last = lastPhaseChange
            lastPhaseChange = now
            last?.let { Math.round((now - it) / 1000.0).toInt() }
        }
        record(E(E.Kind.scenePhase, timestamp = now, scenePhase = phase, sinceSeconds = since))
    }

    /// The footprint at the moment of leaving the front, however close it is to the last sample.
    fun recordBackgroundMemory() {
        val footprint = probe?.footprint() ?: return
        record(E(E.Kind.memoryBackground, timestamp = clock(), footprintBytes = footprint, availableBytes = probe.available()))
    }

    /// Foreground samples every 60 seconds; the background none.
    fun setForeground(foreground: Boolean) {
        if (!foreground) recordBackgroundMemory()
        marker.setState(
            if (foreground) RunMarker.State.foreground else RunMarker.State.background,
            footprint = if (foreground) null else probe?.footprint(), now = clock(),
        )
        synchronized(lock) {
            sampling?.cancel(false)
            sampling = null
            if (foreground && probe != null) {
                sampling = sampler.scheduleWithFixedDelay({ runCatching { sample() } }, 0, 60, TimeUnit.SECONDS)
            }
        }
    }

    /// The newest raw record, redacted now (and the redacted copy kept).
    fun latestRedacted(allow: List<String>, knownNames: List<String>): RedactedRecord? {
        val id = store.rawIDs().firstOrNull() ?: return null
        val record = store.loadRaw(id) ?: return null
        val redacted = Redactor(allow, knownNames).redact(record)
        store.saveRedacted(redacted)
        return redacted
    }

    fun snapshot() = DiagnosticsReport(
        previous.kind, store.rawIDs().isNotEmpty(), marker.read()?.appVersion, probe?.footprint(), ring.read(),
    )

    private companion object {
        val sampler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "peel-diagnostics").apply { isDaemon = true } }
    }
}

/// On-device crash and memory diagnostics (iOS `Diagnostics`; docs/phases/28-diagnostics.md). Nothing is
/// uploaded; the redacted record is read by the person. Every function is safe to call before
/// `CrashRecorder.install` and does nothing then.
object Diagnostics {
    @Volatile private var runtime: DiagnosticsRuntime? = null

    /// The live mirror (iOS's `os.Logger`): enum names only, never a message. `CrashRecorder` points it at Logcat.
    @Volatile var mirrorSink: (String) -> Unit = {}
    internal fun mirror(line: String) = mirrorSink(line)

    internal fun start(value: DiagnosticsRuntime) { runtime = value }
    internal val current: DiagnosticsRuntime? get() = runtime

    /// The app's lifecycle phase (the process lifecycle observer calls this).
    fun recordScenePhase(phase: E.ScenePhase) { current?.recordScenePhase(phase) }

    /// The lock screen was shown: at a cold launch, or by the idle timeout.
    fun recordLock(reason: E.LockReason, idleSeconds: Int? = null, timeoutHours: Int? = null) {
        current?.record(E(E.Kind.lock, lockReason = reason, idleSeconds = idleSeconds, timeoutHours = timeoutHours))
    }

    /// A raw change of the system's network path.
    fun recordNetwork(status: E.NetworkStatus, networkInterface: E.NetworkInterface, isExpensive: Boolean, isConstrained: Boolean) {
        current?.record(E(E.Kind.network, networkStatus = status, networkInterface = networkInterface, isExpensive = isExpensive, isConstrained = isConstrained))
    }

    /// The push fetch's background work: `begin`, `end` and `expired` with the seconds since `begin`.
    fun recordPushFetch(stage: E.PushFetchStage, secondsLeft: Double? = null, elapsed: Double? = null) {
        current?.record(
            E(
                E.Kind.pushFetch, pushStage = stage,
                backgroundSecondsLeft = secondsLeft?.takeIf { it.isFinite() }?.let { minOf(it, 86_400.0).toInt() },
                elapsedSeconds = elapsed?.let { Math.round(it * 10) / 10.0 },
            ),
        )
    }

    fun recordForegroundPing(answered: Boolean, milliseconds: Int) {
        current?.record(E(E.Kind.foregroundPing, pingAnswered = answered, milliseconds = maxOf(milliseconds, 0)))
    }

    fun recordForegroundDecision(action: E.ForegroundAction, reason: E.ForegroundCause, awaySeconds: Int) {
        current?.record(E(E.Kind.foregroundDecision, foregroundAction = action, foregroundCause = reason, awaySeconds = maxOf(awaySeconds, 0)))
    }

    fun recordForcedReconnect(reason: E.ReconnectReason) {
        current?.record(E(E.Kind.forcedReconnect, reconnectReason = reason))
    }

    fun recordPingResult(ok: Boolean, milliseconds: Int, timedOut: Boolean) {
        current?.record(E(E.Kind.pingResult, pingAnswered = ok, milliseconds = maxOf(milliseconds, 0), pingTimedOut = timedOut))
    }

    fun recordBackground(inFlightCatchUp: Boolean) {
        current?.record(E(E.Kind.background, inFlightCatchUp = inFlightCatchUp))
    }

    fun recordPathChangeFlagged(awaySeconds: Int?) {
        current?.record(E(E.Kind.pathChangeFlagged, awaySeconds = awaySeconds?.let { maxOf(it, 0) }))
    }

    fun recordCaughtUp(milliseconds: Int) { current?.record(E(E.Kind.caughtUp, milliseconds = maxOf(milliseconds, 0))) }

    fun recordStallReconnect() { current?.record(E(E.Kind.stallReconnect)) }

    fun recordCatchUp(stage: E.CatchUpStage, reason: E.CatchUpReason, milliseconds: Int? = null) {
        current?.record(E(E.Kind.catchUp, catchUpStage = stage, catchUpReason = reason, milliseconds = milliseconds?.let { maxOf(it, 0) }))
    }

    fun recordCatchUpCursor(from: Long, next: Long, reason: E.CatchUpReason) {
        current?.record(E(E.Kind.catchUpCursor, cursorFrom = from.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(), cursorNext = next.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(), catchUpReason = reason))
    }

    /// A page of history was handled: its indices, the highest, and how many stored a new row.
    fun recordHistoryPage(count: Int, maxIndex: Int?, new: Int) {
        current?.record(E(E.Kind.historyPage, pageCount = count, pageMaxIndex = maxIndex?.let { maxOf(it, 0) }, pageNew = new, pageDup = maxOf(count - new, 0)))
    }

    /// The core refused one or more onions in a history page and they were left
    /// out (`HistoryPage.readablePart`): how many, the highest server index among
    /// them, and the refusal's redacted text. The cursor still moves past the
    /// whole page by design, so this is the only record of a restore/catch-up
    /// having actually lost something, rather than it looking merely slow.
    fun recordHistorySkip(count: Int, highestIndex: Long, detail: String) {
        current?.record(
            E(
                E.Kind.historySkip, pageCount = count,
                highestIndex = highestIndex.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
                errorDetail = redact(detail),
            ),
        )
    }

    /// A message came live, as numbers: never its content.
    fun recordLiveMessage(index: Int, fromMe: Boolean?, type: Int?, new: Boolean) {
        current?.record(E(E.Kind.liveMessage, liveIndex = maxOf(index, 0), liveFromMe = fromMe, liveType = type, liveNew = new))
    }

    /// What became of a tribe, or a message from one; `tag` is `tribeTag` of its key.
    fun recordTribeTrace(
        stage: E.TribeTraceStage, tag: Int, miss: E.TribeMiss? = null, status: Int? = null,
        messageType: Int? = null, restoring: Boolean? = null, fromMe: Boolean? = null,
    ) {
        current?.record(
            E(E.Kind.tribeTrace, tribeFromMe = fromMe, tribeTag = tag, tribeStage = stage, tribeMiss = miss, tribeStatus = status, tribeMessageType = messageType, tribeRestoring = restoring),
        )
    }

    fun recordTribeState(tag: Int, left: Boolean, status: Int) {
        current?.record(E(E.Kind.tribeState, tribeTag = tag, tribeLeft = left, tribeStatus = status))
    }

    /// A call's audio: enums and flags only.
    fun recordCallAudio(
        stage: E.CallAudioStage, ok: Boolean? = null, category: E.AudioCategory? = null, micTrigger: E.MicTrigger? = null,
        micEnabled: Boolean? = null, micPublicationMuted: Boolean? = null, wantsMic: Boolean? = null,
        routeReason: Int? = null, scenePhase: E.ScenePhase? = null, callKitError: Int? = null,
    ) {
        current?.record(
            E(
                E.Kind.callAudio, callAudioStage = stage, callAudioOk = ok, audioCategory = category, micTrigger = micTrigger,
                micEnabled = micEnabled, micPublicationMuted = micPublicationMuted, wantsMic = wantsMic,
                routeReason = routeReason, scenePhase = scenePhase, callKitError = callKitError,
            ),
        )
    }

    /// The tag a tribe goes by in the diagnostics: the first 24 bits of the SHA-256 of its key.
    fun tribeTag(pubkey: String): Int {
        val digest = MessageDigest.getInstance("SHA-256").digest(pubkey.toByteArray(Charsets.UTF_8))
        return digest.take(3).fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
    }

    /// A transport was made, closed or abandoned, or a connect began: the clients alive then.
    fun recordTransportLifecycle(stage: E.LifecycleStage, live: Int) {
        current?.record(E(E.Kind.transportLifecycle, lifecycleStage = stage, liveTransports = maxOf(live, 0)))
    }

    fun recordSubscribed(topics: Int, ok: Boolean) {
        current?.record(E(E.Kind.subscribed, subscribedTopics = topics, subscribedOk = ok))
    }

    fun recordSelfCheck(trigger: E.SelfCheckTrigger, from: Long, missed: Int, highest: Int?) {
        current?.record(
            E(
                E.Kind.selfCheck, selfCheckTrigger = trigger, selfCheckOutcome = if (missed > 0) E.SelfCheckOutcome.missed else E.SelfCheckOutcome.ok,
                cursorFrom = from.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(), selfCheckMissed = maxOf(missed, 0), highestIndex = highest?.let { maxOf(it, 0) },
            ),
        )
    }

    /// A read failed on the connection's socket (no error text kept).
    fun recordReadFailure() { current?.record(E(E.Kind.readFailure)) }

    /// How the system ended earlier runs (`ApplicationExitInfo`), by `AppExit` (zero counts left out).
    fun recordAppExits(counts: Map<AppExit, Int>) {
        val named = counts.filter { it.value > 0 }
        if (named.isEmpty()) return
        current?.record(E(E.Kind.appExits, exitCounts = named.entries.associate { it.key.name to it.value }))
    }

    /// One system diagnostic, as its kind and numbers.
    fun recordMetricDiagnostic(kind: E.MetricDiagnosticKind, signal: Int? = null, exceptionType: Int? = null, exceptionCode: Int? = null, hangSeconds: Double? = null) {
        current?.record(E(E.Kind.metricDiagnostic, metricKind = kind, signal = signal, exceptionType = exceptionType, exceptionCode = exceptionCode, hangSeconds = hangSeconds?.let { Math.round(it * 10) / 10.0 }))
    }

    /// An error the person was told about, as its kind only.
    fun note(errorKind: E.ErrorKind) { current?.note(errorKind) }

    /// The same with the failure's text, redacted here (`redact`) whatever the caller passes.
    fun note(errorKind: E.ErrorKind, detail: String?) { current?.note(errorKind, detail?.let(::redact)) }

    /// Notes an error by its kind, never its message.
    fun note(error: Throwable) = note(ErrorKinds.of(error))

    /// The core refused a call: where, the kind of message being handled, and the redacted text.
    fun recordCoreFailure(site: E.CoreFailureSite, kind: String?, detail: String) {
        current?.record(E(E.Kind.coreFailure, failureSite = site, messageKind = kind?.let { redact(it).take(40) }, errorDetail = redact(detail)))
    }

    /// The engine reloaded its state from storage.
    fun recordCoreRecovery(reason: E.CoreRecoveryReason, stage: E.CoreRecoveryStage) {
        current?.record(E(E.Kind.coreRecovery, recoveryReason = reason, recoveryStage = stage))
    }

    const val DETAIL_LIMIT = DetailRedaction.DETAIL_LIMIT

    /// Makes a failure's text safe to keep (see `DetailRedaction`).
    fun redact(text: String): String = DetailRedaction.redact(text)

    /// What the person just asked for, attached to the events that follow.
    fun setAction(action: E.Action) { current?.setAction(action) }

    fun setForeground(foreground: Boolean) { current?.setForeground(foreground) }

    /// Normal termination: the next launch will not call this run killed.
    fun markCleanExit() { current?.marker?.markClean() }

    /// A memory warning (`Application.onTrimMemory`).
    fun memoryWarning(level: E.PressureLevel = E.PressureLevel.warning) {
        current?.marker?.noteMemoryWarning()
        current?.sample(level)
    }

    /// The app is out of the front and still running: noted in the run marker.
    fun markAlive() { current?.marker?.markAlive() }

    /// The background work ended before it finished (the push fetch's `onStopJob`-style expiry).
    fun backgroundTaskExpired() {
        current?.marker?.noteBackgroundTaskExpired()
        current?.record(E(E.Kind.backgroundExpired))
    }

    fun previousRun(): PreviousRun = current?.previous ?: PreviousRun.Clean

    fun recentEvents(limit: Int = 100): List<E> = (current?.ring?.read() ?: emptyList()).takeLast(maxOf(limit, 0))

    fun snapshot(): DiagnosticsReport =
        current?.snapshot() ?: DiagnosticsReport(E.PreviousRunKind.clean, false, null, null, emptyList())

    /// The newest crash record, redacted (and the redacted copy kept). The only way a record leaves the
    /// raw folder. `allow` is the tribes' names and keys that may stay; `knownNames` the contacts'.
    fun latestRedactedReport(allow: List<String> = emptyList(), knownNames: List<String> = emptyList()): RedactedRecord? =
        current?.latestRedacted(allow, knownNames)

    /// Empties the event ring only: crash records and the run marker stay.
    fun clearEvents() { current?.ring?.clear() }

    /// Removes every record, raw and redacted.
    fun deleteRecords() { current?.store?.deleteAll() }

    /// Crashes the app on purpose, to see a record made (iOS `forceTestCrash`).
    fun forceTestCrash(): Nothing = throw RuntimeException("Test crash")
}

/// What the system's exit reasons are counted as (iOS `AppExit`, MetricKit's categories); Android's
/// `ApplicationExitInfo` fills the same names (`ExitInfoMapping`).
enum class AppExit {
    fgNormal, fgAbnormal, fgMemoryResourceLimit, fgWatchdog, fgBadAccess, fgIllegalInstruction,
    bgNormal, bgAbnormal, bgMemoryResourceLimit, bgMemoryPressure, bgWatchdog, bgCPUResourceLimit,
    bgBadAccess, bgIllegalInstruction, bgSuspendedWithLockedFile, bgBackgroundTaskAssertionTimeout,
}
