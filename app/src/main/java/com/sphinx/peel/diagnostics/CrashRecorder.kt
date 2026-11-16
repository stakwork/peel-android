package com.sphinx.peel.diagnostics

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.io.File
import java.util.UUID

/// Builds the raw record of a Java crash: the stand-in for what PLCrashReporter writes on iOS. Pure.
object CrashRecordBuilder {
    data class Meta(
        val appIdentifier: String?, val appVersion: String?, val osVersion: String?, val osBuild: String?,
        val machineModel: String?, val processName: String?,
    )

    fun build(error: Throwable, thread: Thread, meta: Meta, events: List<DiagnosticEvent>, now: Long, id: String = UUID.randomUUID().toString()): CrashRecord {
        val frames = error.stackTrace.map {
            CrashFrame(0, it.fileName ?: it.className, "${it.className}.${it.methodName}", it.lineNumber.takeIf { n -> n >= 0 }?.toLong())
        }
        // The cause chain is the words (a wrapped exception's own class and message), bounded.
        val causes = generateSequence(error.cause) { it.cause?.takeIf { next -> next !== it } }.take(5)
            .joinToString("") { "\nCaused by: ${it.javaClass.name}: ${it.message ?: ""}" }
        return CrashRecord(
            id = id, appIdentifier = meta.appIdentifier, appVersion = meta.appVersion, osVersion = meta.osVersion,
            osBuild = meta.osBuild, machineModel = meta.machineModel, timestamp = now,
            exceptionName = error.javaClass.name, exceptionReason = (error.message ?: "") + causes,
            processName = meta.processName,
            threads = listOf(CrashThread(thread.id.toInt(), true, frames, emptyList())),
            events = events,
        )
    }
}

/// The Android pieces of crash recording (iOS `CrashRecorder`): installed once, first thing in
/// `Application.onCreate`. A Java crash is caught by an uncaught-exception handler, which writes the raw
/// record and a pending note before the process dies; the next launch finds it (`DiagnosticsStore.pendingFile`).
/// A native crash is found at the next launch in `ApplicationExitInfo`. The process lifecycle drives the
/// foreground state, and `onTrimMemory` (`Application`) the memory warnings.
object CrashRecorder {
    private var started = false

    fun defaultRoot(context: Context): File = File(context.noBackupFilesDir, "Diagnostics")

    @Synchronized
    fun install(application: Application) {
        if (started) return
        started = true
        Diagnostics.mirrorSink = { Log.i("PeelDiagnostics", it) }
        val root = defaultRoot(application)
        val store = DiagnosticsStore(root)
        store.prepare()
        val info = runCatching { application.packageManager.getPackageInfo(application.packageName, 0) }.getOrNull()
        val version = info?.versionName ?: "?"
        val environment = RunEnvironment(
            bootTime = System.currentTimeMillis() - SystemClock.elapsedRealtime(),
            osVersion = "Android ${Build.VERSION.RELEASE} (${Build.VERSION.INCREMENTAL})",
            binaryStamp = info?.lastUpdateTime,
        )
        val exits = ExitInfoReader.read(application)
        val crash = collect(store, exits, environment, version, application.packageName)
        val front = launchedInFront(application)
        Diagnostics.start(
            DiagnosticsRuntime(
                root, version, crash, if (front) RunMarker.State.foreground else RunMarker.State.background, environment,
                AndroidMemoryProbe(application), uptimeSeconds = { (SystemClock.elapsedRealtime() / 1000).toInt() }, mirror = Diagnostics::mirror,
            ),
        )
        ExitInfoReader.record(application, exits)
        install(store, application.packageName, version)
        Diagnostics.setForeground(front)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                Diagnostics.recordScenePhase(DiagnosticEvent.ScenePhase.active)
                Diagnostics.setForeground(true)
            }

            override fun onStop(owner: LifecycleOwner) {
                Diagnostics.recordScenePhase(DiagnosticEvent.ScenePhase.background)
                Diagnostics.setForeground(false)
            }
        })
    }

    /// The id of the record the last run left (a Java crash's pending note, else a native crash from the
    /// system's exit entry), or null; a run the system saw end normally is marked clean first.
    private fun collect(store: DiagnosticsStore, exits: List<ExitRecord>, environment: RunEnvironment, version: String, pkg: String): String? {
        val pending = runCatching { store.pendingFile.readText().trim() }.getOrNull()
        store.pendingFile.delete()
        if (!pending.isNullOrEmpty() && store.loadRaw(pending) != null) return pending
        val marker = store.markerFile.takeIf { it.exists() }?.let { runCatching { RunMarker.fromJson(it.readText()) }.getOrNull() }
        if (marker == null || marker.phase != RunMarker.Phase.running) return null
        val end = ExitInfoMapping.endOf(exits, marker.startedAt) ?: return null
        if (end.reason == ExitInfoMapping.REASON_CRASH_NATIVE) {
            val base = CrashRecord(
                id = "", appIdentifier = pkg, appVersion = version, osVersion = environment.osVersion, osBuild = Build.ID,
                machineModel = modelName(), processName = pkg, events = DiagnosticsRing(store.ringFile).read(),
            )
            val record = ExitInfoMapping.nativeCrashRecord(end, base)
            return runCatching { store.saveRaw(record); record.id }.getOrNull()
        }
        // Ended on purpose (the person force-stopped it, the app exited itself): not a kill.
        if (ExitInfoMapping.endedNormally(end)) {
            runCatching { store.markerFile.writeText(marker.copy(phase = RunMarker.Phase.clean).toJson()) }
        }
        return null
    }

    private fun install(store: DiagnosticsStore, pkg: String, version: String) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val meta = CrashRecordBuilder.Meta(pkg, version, "Android ${Build.VERSION.RELEASE} (${Build.VERSION.INCREMENTAL})", Build.ID, modelName(), pkg)
                val record = CrashRecordBuilder.build(error, thread, meta, Diagnostics.current?.ring?.read() ?: emptyList(), System.currentTimeMillis())
                store.saveRaw(record)
                store.pendingFile.writeText(record.id)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /// An identifier-shaped model name (the redactor keeps only that alphabet).
    private fun modelName() = "${Build.MANUFACTURER}-${Build.MODEL}".replace(Regex("[^A-Za-z0-9._,+-]+"), "_")

    /// False for a process the system started in the background (a push, a job): the activity never showed.
    private fun launchedInFront(context: Context): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        runCatching { ActivityManager.getMyMemoryState(info) }.onFailure { return true }
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }
}

class AndroidMemoryProbe(private val context: Context) : MemoryProbe {
    /// The proportional set size, what Android settings count for an app (the closest to `phys_footprint`).
    override fun footprint(): Long? = runCatching {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        info.totalPss.toLong() * 1024
    }.getOrNull()

    /// The system's free memory (iOS's `os_proc_available_memory` is per-process; Android has no such number).
    override fun available(): Long? = runCatching {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        info.availMem
    }.getOrNull()
}

/// Reads `ApplicationExitInfo` (API 30+; nothing before it) and records what is new since the last read.
object ExitInfoReader {
    fun read(context: Context): List<ExitRecord> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return runCatching {
            context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(null, 0, 16).map {
                ExitRecord(it.timestamp, it.reason, it.status, it.importance, it.description)
            }
        }.getOrDefault(emptyList())
    }

    /// `appExits` for the entries not yet seen (the iOS ring repeats MetricKit's past payloads; this does not),
    /// and a `metricDiagnostic` for each crash or ANR among them.
    fun record(context: Context, exits: List<ExitRecord>) {
        val seenFile = File(CrashRecorder.defaultRoot(context), "exits-seen")
        val seen = runCatching { seenFile.readText().trim().toLong() }.getOrDefault(0L)
        val fresh = exits.filter { it.timestamp > seen }
        if (fresh.isEmpty()) return
        Diagnostics.recordAppExits(ExitInfoMapping.counts(fresh))
        for (exit in fresh) {
            ExitInfoMapping.metricKind(exit)?.let { kind ->
                Diagnostics.recordMetricDiagnostic(kind, signal = exit.status.takeIf { exit.reason == ExitInfoMapping.REASON_CRASH_NATIVE })
            }
        }
        runCatching { seenFile.writeText(fresh.maxOf { it.timestamp }.toString()) }
    }
}
