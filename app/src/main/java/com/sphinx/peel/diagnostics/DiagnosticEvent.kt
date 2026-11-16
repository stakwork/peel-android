@file:Suppress("EnumEntryName", "unused")

package com.sphinx.peel.diagnostics

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale

/// One entry in the event ring: iOS `DiagnosticEvent` (Sources/PeelDiagnostics/DiagnosticEvent.swift).
/// Enums and numbers, with ONE string field, `errorDetail`/`messageKind`, which only
/// `Diagnostics.redact` output may fill. The enum entries keep iOS's names exactly, so a record reads
/// the same on both. Some entries have no Android source (CallKit, MetricKit, the strut): they stay so
/// the model is the same; see docs/phases/28-diagnostics.md, PORT_NOTES.
data class DiagnosticEvent(
    val kind: Kind,
    val timestamp: Long = System.currentTimeMillis(),
    val count: Int = 1,
    val errorKind: ErrorKind? = null,
    val lastAction: Action? = null,
    val footprintBytes: Long? = null,
    val availableBytes: Long? = null,
    val pressureLevel: PressureLevel? = null,
    val previousRun: PreviousRunKind? = null,
    val exitReason: ExitReason? = null,
    val exitStatus: Int? = null,
    val errorDetail: String? = null,
    val launchedInFront: Boolean? = null,
    val uptimeSeconds: Int? = null,
    val scenePhase: ScenePhase? = null,
    val sinceSeconds: Int? = null,
    val lockReason: LockReason? = null,
    val idleSeconds: Int? = null,
    val timeoutHours: Int? = null,
    val networkStatus: NetworkStatus? = null,
    val networkInterface: NetworkInterface? = null,
    val isExpensive: Boolean? = null,
    val isConstrained: Boolean? = null,
    val pushStage: PushFetchStage? = null,
    val backgroundSecondsLeft: Int? = null,
    val elapsedSeconds: Double? = null,
    val exitCounts: Map<String, Int>? = null,
    val metricKind: MetricDiagnosticKind? = null,
    val signal: Int? = null,
    val exceptionType: Int? = null,
    val exceptionCode: Int? = null,
    val hangSeconds: Double? = null,
    val pingAnswered: Boolean? = null,
    val milliseconds: Int? = null,
    val catchUpStage: CatchUpStage? = null,
    val catchUpReason: CatchUpReason? = null,
    val foregroundAction: ForegroundAction? = null,
    val foregroundCause: ForegroundCause? = null,
    val awaySeconds: Int? = null,
    val reconnectReason: ReconnectReason? = null,
    val pingTimedOut: Boolean? = null,
    val inFlightCatchUp: Boolean? = null,
    val cursorFrom: Int? = null,
    val cursorNext: Int? = null,
    val pageCount: Int? = null,
    val pageMaxIndex: Int? = null,
    val pageNew: Int? = null,
    val pageDup: Int? = null,
    val liveIndex: Int? = null,
    val liveFromMe: Boolean? = null,
    val liveType: Int? = null,
    val liveNew: Boolean? = null,
    val lifecycleStage: LifecycleStage? = null,
    val liveTransports: Int? = null,
    val subscribedTopics: Int? = null,
    val subscribedOk: Boolean? = null,
    val selfCheckOutcome: SelfCheckOutcome? = null,
    val selfCheckTrigger: SelfCheckTrigger? = null,
    val selfCheckMissed: Int? = null,
    val highestIndex: Int? = null,
    val failureSite: CoreFailureSite? = null,
    val messageKind: String? = null,
    val recoveryReason: CoreRecoveryReason? = null,
    val recoveryStage: CoreRecoveryStage? = null,
    val exitCause: ExitCause? = null,
    val aliveInBackgroundSeconds: Int? = null,
    val sinceAliveSeconds: Int? = null,
    val memoryWarnings: Int? = null,
    val backgroundTaskExpired: Boolean? = null,
    val tribeTag: Int? = null,
    val tribeStage: TribeTraceStage? = null,
    val tribeMiss: TribeMiss? = null,
    val tribeLeft: Boolean? = null,
    val tribeStatus: Int? = null,
    val tribeMessageType: Int? = null,
    val tribeRestoring: Boolean? = null,
    val tribeFromMe: Boolean? = null,
    val callAudioStage: CallAudioStage? = null,
    val callAudioOk: Boolean? = null,
    val audioCategory: AudioCategory? = null,
    val micTrigger: MicTrigger? = null,
    val micEnabled: Boolean? = null,
    val micPublicationMuted: Boolean? = null,
    val wantsMic: Boolean? = null,
    val routeReason: Int? = null,
    val callKitError: Int? = null,
) {
    enum class Kind {
        error, memorySample, memoryPressure, launch, previousRun, strutExit,
        scenePhase, memoryBackground, lock, network, pushFetch, appExits, metricDiagnostic,
        foregroundPing, caughtUp, stallReconnect,
        catchUp, readFailure,
        foregroundDecision, forcedReconnect, pingResult, background, pathChangeFlagged,
        catchUpCursor, historyPage, liveMessage, transportLifecycle, subscribed, selfCheck,
        coreFailure, coreRecovery,
        previousRunDetail, backgroundExpired,
        tribeTrace, tribeState,
        callAudio,
    }
    enum class CallAudioStage {
        sessionActivated, interruptionBegan, interruptionEnded, routeChanged, micState,
        cameraPaused, cameraResumed, scene, callKitStarted, callKitEnded, callKitFailed,
    }
    enum class MicTrigger { foreground, background, toggle, reacquire, interruptionEnded, route, system }
    enum class AudioCategory { playAndRecord, playback, record, other }
    enum class TribeTraceStage {
        discovered, notDiscovered, held, confirmed, notListed, registryDeleted, confirmFailed,
        unknownSigner, droppedLeft, markedLeft, markedJoined, statusChanged, membershipMessage,
    }
    enum class TribeMiss { untrusted, noHost, unknownToCore, person }
    enum class CoreFailureSite { receive, commit, cameOnline, checkPending, resumeHistory, catchUp }
    enum class CoreRecoveryReason { pageFailed, repeated, reinit, cameOnline, syncBound, catchUp, brokenStreams }
    enum class CoreRecoveryStage { reloadState, reinit, streams }
    enum class LifecycleStage { opened, closed, abandoned, establish, leak }
    enum class SelfCheckOutcome { ok, missed }
    enum class SelfCheckTrigger { afterCaughtUp, periodic }
    enum class ForegroundAction { trust, verify, rebuild, reconnect }
    enum class ForegroundCause { away, pathChange, unverified, readFailure, notCaughtUp, offline, verifyTimeout, pingFailed, none }
    enum class ReconnectReason {
        pingFailed, pingTimeout, stall, syncBound, readFailure, pathChange, catchUpError,
        foregroundRebuild, cameOnlineTimeout, selfCheckMissed, coreFailure,
    }
    enum class CatchUpStage { start, finish, cancel, abandon }
    enum class CatchUpReason { foreground, push, watchdog, cameOnline, stuckFlag, readFailure, syncBound, appLeftForeground, error, cancelled, selfCheck }
    enum class ErrorKind { database, network, connection, server, secureConnection, file, cancelled, core, decoding, engine, call, refused, other }
    enum class Action { tribeJoin, send, sync, launch, other }
    enum class PressureLevel { warning, critical }
    enum class ExitReason { exit, uncaughtSignal }
    enum class ExitCause { restarted, osUpdated, reinstalled, system }
    enum class PreviousRunKind { clean, crashed, killedForeground, killedBackground, unknown }
    enum class ScenePhase { active, inactive, background }
    enum class LockReason { coldLaunch, idleTimeout, everyOpen, biometricCheck, rememberedPIN }
    enum class NetworkStatus { satisfied, unsatisfied, requiresConnection }
    enum class NetworkInterface { wifi, cellular, wiredEthernet, other, none }
    enum class PushFetchStage { begin, end, expired }
    enum class MetricDiagnosticKind { crash, hang, cpuException, diskWriteException }

    /// Whether `other` is the same event again (everything but when and how often), so the ring counts it.
    fun repeats(other: DiagnosticEvent): Boolean = other.copy(timestamp = timestamp, count = count) == this

    /// One JSON object, keys sorted, times as seconds since 1970 (the iOS ring's format).
    fun toJson(): String = MiniJson.write(toMap())

    fun toMap(): Map<String, Any?> = mapOf(
        "kind" to kind.name, "timestamp" to timestamp / 1000.0, "count" to count,
        "errorKind" to errorKind?.name, "lastAction" to lastAction?.name,
        "footprintBytes" to footprintBytes, "availableBytes" to availableBytes,
        "pressureLevel" to pressureLevel?.name, "previousRun" to previousRun?.name,
        "exitReason" to exitReason?.name, "exitStatus" to exitStatus, "errorDetail" to errorDetail,
        "launchedInFront" to launchedInFront, "uptimeSeconds" to uptimeSeconds,
        "scenePhase" to scenePhase?.name, "sinceSeconds" to sinceSeconds,
        "lockReason" to lockReason?.name, "idleSeconds" to idleSeconds, "timeoutHours" to timeoutHours,
        "networkStatus" to networkStatus?.name, "networkInterface" to networkInterface?.name,
        "isExpensive" to isExpensive, "isConstrained" to isConstrained,
        "pushStage" to pushStage?.name, "backgroundSecondsLeft" to backgroundSecondsLeft,
        "elapsedSeconds" to elapsedSeconds, "exitCounts" to exitCounts,
        "metricKind" to metricKind?.name, "signal" to signal, "exceptionType" to exceptionType,
        "exceptionCode" to exceptionCode, "hangSeconds" to hangSeconds,
        "pingAnswered" to pingAnswered, "milliseconds" to milliseconds,
        "catchUpStage" to catchUpStage?.name, "catchUpReason" to catchUpReason?.name,
        "foregroundAction" to foregroundAction?.name, "foregroundCause" to foregroundCause?.name,
        "awaySeconds" to awaySeconds, "reconnectReason" to reconnectReason?.name,
        "pingTimedOut" to pingTimedOut, "inFlightCatchUp" to inFlightCatchUp,
        "cursorFrom" to cursorFrom, "cursorNext" to cursorNext,
        "pageCount" to pageCount, "pageMaxIndex" to pageMaxIndex, "pageNew" to pageNew, "pageDup" to pageDup,
        "liveIndex" to liveIndex, "liveFromMe" to liveFromMe, "liveType" to liveType, "liveNew" to liveNew,
        "lifecycleStage" to lifecycleStage?.name, "liveTransports" to liveTransports,
        "subscribedTopics" to subscribedTopics, "subscribedOk" to subscribedOk,
        "selfCheckOutcome" to selfCheckOutcome?.name, "selfCheckTrigger" to selfCheckTrigger?.name,
        "selfCheckMissed" to selfCheckMissed, "highestIndex" to highestIndex,
        "failureSite" to failureSite?.name, "messageKind" to messageKind,
        "recoveryReason" to recoveryReason?.name, "recoveryStage" to recoveryStage?.name,
        "exitCause" to exitCause?.name, "aliveInBackgroundSeconds" to aliveInBackgroundSeconds,
        "sinceAliveSeconds" to sinceAliveSeconds, "memoryWarnings" to memoryWarnings,
        "backgroundTaskExpired" to backgroundTaskExpired,
        "tribeTag" to tribeTag, "tribeStage" to tribeStage?.name, "tribeMiss" to tribeMiss?.name,
        "tribeLeft" to tribeLeft, "tribeStatus" to tribeStatus, "tribeMessageType" to tribeMessageType,
        "tribeRestoring" to tribeRestoring, "tribeFromMe" to tribeFromMe,
        "callAudioStage" to callAudioStage?.name, "callAudioOk" to callAudioOk,
        "audioCategory" to audioCategory?.name, "micTrigger" to micTrigger?.name,
        "micEnabled" to micEnabled, "micPublicationMuted" to micPublicationMuted, "wantsMic" to wantsMic,
        "routeReason" to routeReason, "callKitError" to callKitError,
    )

    /// One line for a record: when, what and the numbers, in enum names (iOS `DiagnosticEvent.line`).
    val line: String
        get() {
            val parts = mutableListOf(isoTime(timestamp), kind.name)
            errorKind?.let { parts += "error=${it.name}" }
            lastAction?.let { parts += "action=${it.name}" }
            footprintBytes?.let { parts += "footprint=${it / 1_048_576} MiB" }
            availableBytes?.let { parts += "available=${it / 1_048_576} MiB" }
            pressureLevel?.let { parts += "pressure=${it.name}" }
            previousRun?.let { parts += "previous=${it.name}" }
            exitReason?.let { parts += "exit=${it.name}" }
            exitStatus?.let { parts += "status=$it" }
            launchedInFront?.let { parts += if (it) "front" else "background-launch" }
            uptimeSeconds?.let { parts += "uptime=${it}s" }
            scenePhase?.let { parts += "phase=${it.name}" }
            sinceSeconds?.let { parts += "since=${it}s" }
            lockReason?.let { parts += "lock=${it.name}" }
            idleSeconds?.let { parts += "idle=${it}s" }
            timeoutHours?.let { parts += "timeout=${it}h" }
            networkStatus?.let { parts += "net=${it.name}" }
            networkInterface?.let { parts += "via=${it.name}" }
            isExpensive?.let { parts += "expensive=$it" }
            isConstrained?.let { parts += "constrained=$it" }
            pushStage?.let { parts += "push=${it.name}" }
            backgroundSecondsLeft?.let { parts += "left=${it}s" }
            elapsedSeconds?.let { parts += "elapsed=${tenth(it)}s" }
            exitCounts?.let { counts -> parts += counts.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}=${it.value}" } }
            metricKind?.let { parts += "metric=${it.name}" }
            signal?.let { parts += "signal=$it" }
            exceptionType?.let { parts += "exceptionType=$it" }
            exceptionCode?.let { parts += "exceptionCode=$it" }
            hangSeconds?.let { parts += "hang=${tenth(it)}s" }
            pingAnswered?.let { parts += if (it) "ping=ok" else "ping=failed" }
            catchUpStage?.let { parts += "stage=${it.name}" }
            catchUpReason?.let { parts += "reason=${it.name}" }
            foregroundAction?.let { parts += "decision=${it.name}" }
            foregroundCause?.let { parts += "cause=${it.name}" }
            awaySeconds?.let { parts += "away=${it}s" }
            reconnectReason?.let { parts += "reason=${it.name}" }
            pingTimedOut?.let { parts += if (it) "timedOut" else "inTime" }
            inFlightCatchUp?.let { parts += "inFlight=$it" }
            cursorFrom?.let { parts += "from=$it" }
            cursorNext?.let { parts += "next=$it" }
            pageCount?.let { parts += "count=$it" }
            pageMaxIndex?.let { parts += "maxIndex=$it" }
            pageNew?.let { parts += "new=$it" }
            pageDup?.let { parts += "dup=$it" }
            liveIndex?.let { parts += "index=$it" }
            liveFromMe?.let { parts += "fromMe=$it" }
            liveType?.let { parts += "type=$it" }
            liveNew?.let { parts += "new=$it" }
            lifecycleStage?.let { parts += "stage=${it.name}" }
            liveTransports?.let { parts += "live=$it" }
            subscribedTopics?.let { parts += "topics=$it" }
            subscribedOk?.let { parts += if (it) "subscribed=ok" else "subscribed=failed" }
            selfCheckTrigger?.let { parts += "trigger=${it.name}" }
            selfCheckOutcome?.let { parts += "outcome=${it.name}" }
            selfCheckMissed?.let { parts += "missed=$it" }
            highestIndex?.let { parts += "highest=$it" }
            failureSite?.let { parts += "site=${it.name}" }
            messageKind?.let { parts += "msg=$it" }
            recoveryReason?.let { parts += "recovery=${it.name}" }
            recoveryStage?.let { parts += "stage=${it.name}" }
            exitCause?.let { parts += "cause=${it.name}" }
            aliveInBackgroundSeconds?.let { parts += "aliveInBackground=${it}s" }
            sinceAliveSeconds?.let { parts += "sinceAlive=${it}s" }
            memoryWarnings?.let { parts += "memoryWarnings=$it" }
            backgroundTaskExpired?.let { parts += "backgroundTaskExpired=$it" }
            tribeTag?.let { parts += "tribe=${String.format(Locale.ROOT, "%06x", it)}" }
            tribeStage?.let { parts += "stage=${it.name}" }
            tribeMiss?.let { parts += "miss=${it.name}" }
            tribeLeft?.let { parts += "left=$it" }
            tribeStatus?.let { parts += "status=$it" }
            tribeMessageType?.let { parts += "type=$it" }
            tribeRestoring?.let { parts += "restoring=$it" }
            tribeFromMe?.let { parts += "fromMe=$it" }
            callAudioStage?.let { parts += "stage=${it.name}" }
            callAudioOk?.let { parts += if (it) "ok" else "failed" }
            audioCategory?.let { parts += "category=${it.name}" }
            micTrigger?.let { parts += "trigger=${it.name}" }
            micEnabled?.let { parts += "micEnabled=$it" }
            micPublicationMuted?.let { parts += "micMuted=$it" }
            wantsMic?.let { parts += "wantsMic=$it" }
            routeReason?.let { parts += "routeReason=$it" }
            callKitError?.let { parts += "callKitError=$it" }
            errorDetail?.let { parts += "detail=\"$it\"" }
            milliseconds?.let { parts += "took=${it}ms" }
            if (count > 1) parts += "x$count"
            return parts.joinToString(" ")
        }

    companion object {
        private fun tenth(value: Double) = String.format(Locale.ROOT, "%.1f", value)

        /// ISO 8601 in UTC to the second, as iOS's `ISO8601DateFormatter` writes it.
        fun isoTime(millis: Long): String = Instant.ofEpochMilli(millis).truncatedTo(ChronoUnit.SECONDS).toString()

        /// Null for a line that is not an event (a write cut short, damage).
        fun fromJson(text: String): DiagnosticEvent? {
            val m = MiniJson.parseObject(text) ?: return null
            val kind = m.enum<Kind>("kind") ?: return null
            val seconds = m.dbl("timestamp") ?: return null
            return DiagnosticEvent(
                kind = kind, timestamp = Math.round(seconds * 1000), count = m.int("count") ?: 1,
                errorKind = m.enum("errorKind"), lastAction = m.enum("lastAction"),
                footprintBytes = m.long("footprintBytes"), availableBytes = m.long("availableBytes"),
                pressureLevel = m.enum("pressureLevel"), previousRun = m.enum("previousRun"),
                exitReason = m.enum("exitReason"), exitStatus = m.int("exitStatus"), errorDetail = m.str("errorDetail"),
                launchedInFront = m.bool("launchedInFront"), uptimeSeconds = m.int("uptimeSeconds"),
                scenePhase = m.enum("scenePhase"), sinceSeconds = m.int("sinceSeconds"),
                lockReason = m.enum("lockReason"), idleSeconds = m.int("idleSeconds"), timeoutHours = m.int("timeoutHours"),
                networkStatus = m.enum("networkStatus"), networkInterface = m.enum("networkInterface"),
                isExpensive = m.bool("isExpensive"), isConstrained = m.bool("isConstrained"),
                pushStage = m.enum("pushStage"), backgroundSecondsLeft = m.int("backgroundSecondsLeft"),
                elapsedSeconds = m.dbl("elapsedSeconds"),
                exitCounts = (m["exitCounts"] as? Map<*, *>)?.entries
                    ?.mapNotNull { (k, v) -> if (k is String && v is Number) k to v.toInt() else null }?.toMap(),
                metricKind = m.enum("metricKind"), signal = m.int("signal"), exceptionType = m.int("exceptionType"),
                exceptionCode = m.int("exceptionCode"), hangSeconds = m.dbl("hangSeconds"),
                pingAnswered = m.bool("pingAnswered"), milliseconds = m.int("milliseconds"),
                catchUpStage = m.enum("catchUpStage"), catchUpReason = m.enum("catchUpReason"),
                foregroundAction = m.enum("foregroundAction"), foregroundCause = m.enum("foregroundCause"),
                awaySeconds = m.int("awaySeconds"), reconnectReason = m.enum("reconnectReason"),
                pingTimedOut = m.bool("pingTimedOut"), inFlightCatchUp = m.bool("inFlightCatchUp"),
                cursorFrom = m.int("cursorFrom"), cursorNext = m.int("cursorNext"),
                pageCount = m.int("pageCount"), pageMaxIndex = m.int("pageMaxIndex"),
                pageNew = m.int("pageNew"), pageDup = m.int("pageDup"),
                liveIndex = m.int("liveIndex"), liveFromMe = m.bool("liveFromMe"),
                liveType = m.int("liveType"), liveNew = m.bool("liveNew"),
                lifecycleStage = m.enum("lifecycleStage"), liveTransports = m.int("liveTransports"),
                subscribedTopics = m.int("subscribedTopics"), subscribedOk = m.bool("subscribedOk"),
                selfCheckOutcome = m.enum("selfCheckOutcome"), selfCheckTrigger = m.enum("selfCheckTrigger"),
                selfCheckMissed = m.int("selfCheckMissed"), highestIndex = m.int("highestIndex"),
                failureSite = m.enum("failureSite"), messageKind = m.str("messageKind"),
                recoveryReason = m.enum("recoveryReason"), recoveryStage = m.enum("recoveryStage"),
                exitCause = m.enum("exitCause"), aliveInBackgroundSeconds = m.int("aliveInBackgroundSeconds"),
                sinceAliveSeconds = m.int("sinceAliveSeconds"), memoryWarnings = m.int("memoryWarnings"),
                backgroundTaskExpired = m.bool("backgroundTaskExpired"),
                tribeTag = m.int("tribeTag"), tribeStage = m.enum("tribeStage"), tribeMiss = m.enum("tribeMiss"),
                tribeLeft = m.bool("tribeLeft"), tribeStatus = m.int("tribeStatus"),
                tribeMessageType = m.int("tribeMessageType"), tribeRestoring = m.bool("tribeRestoring"),
                tribeFromMe = m.bool("tribeFromMe"),
                callAudioStage = m.enum("callAudioStage"), callAudioOk = m.bool("callAudioOk"),
                audioCategory = m.enum("audioCategory"), micTrigger = m.enum("micTrigger"),
                micEnabled = m.bool("micEnabled"), micPublicationMuted = m.bool("micPublicationMuted"),
                wantsMic = m.bool("wantsMic"), routeReason = m.int("routeReason"), callKitError = m.int("callKitError"),
            )
        }
    }
}
