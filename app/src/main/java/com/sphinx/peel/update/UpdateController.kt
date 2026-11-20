package com.sphinx.peel.update

import com.sphinx.peel.diagnostics.DiagnosticEvent
import com.sphinx.peel.diagnostics.Diagnostics
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/// What the update UI shows.
sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    data class UpToDate(val installedName: String) : UpdateState()
    data class Available(val release: ReleaseInfo) : UpdateState()
    data class Downloading(val release: ReleaseInfo, val bytes: Long, val total: Long) : UpdateState()
    /// Downloaded and checked; the person must allow "install unknown apps" for Peel, then [UpdateController.resume] installs.
    data class NeedsPermission(val release: ReleaseInfo, val file: File) : UpdateState()
    /// The system installer was started; Android replaces the app if the person confirms.
    data class Installing(val release: ReleaseInfo) : UpdateState()
    /// [fromUpdate]: failed while downloading/installing (a dialog says so); false for a tapped check (the You row says so).
    data class Failed(val failure: UpdateFailure, val fromUpdate: Boolean = false) : UpdateState()
}

/// The platform half of installing, behind an interface so the state machine runs on the JVM.
interface ApkInstaller {
    fun canInstall(): Boolean
    /// Opens the "install unknown apps" screen for Peel.
    fun openPermissionSettings()
    fun installedFacts(): PackageFacts?
    fun archiveFacts(file: File): PackageFacts?
    /// Starts the system installer; false if it could not be started (no installer activity, a security refusal).
    fun install(file: File): Boolean
}

/// Process-wide owner of the check/download/install flow, so a download survives the screen that started it.
/// Launch check -> [UpdateState.Available] + prompt -> Update -> download -> archive check -> permission -> installer.
class UpdateController(
    private val checker: UpdateChecker,
    private val downloader: ApkDownloader,
    private val installer: ApkInstaller,
    private val dir: File,
    private val installedCode: Int,
    /// False for debug and unversioned local builds: they can't update in place (`BuildConfig.UPDATES_ENABLED`).
    private val enabled: Boolean,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /// Whether the "Update / Later" dialog is open. Separate from the state so "Later" closes it but the You row still says "available".
    private val _prompt = MutableStateFlow(false)
    val prompt: StateFlow<Boolean> = _prompt.asStateFlow()

    private var job: Job? = null

    init { if (enabled) runCatching { UpdateFiles.clean(dir, installedCode) } }

    /// The check on launch: silent unless there is something to offer, and never on a build that can't update.
    fun checkOnLaunch() { if (enabled) check(manual = false) }

    /// The You row's tap.
    fun checkNow() {
        if (!enabled) { _state.value = UpdateState.Failed(UpdateFailure.Disabled); return }
        check(manual = true)
    }

    private fun check(manual: Boolean) {
        if (busy()) return
        job = scope.launch {
            if (manual) _state.value = UpdateState.Checking
            val result = try {
                withContext(io) { checker.check(manual) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                CheckResult.Failed(UpdateFailure.BadResponse) // never let a check crash the app
            }
            when (result) {
                is CheckResult.Skipped -> Unit
                is CheckResult.UpToDate -> if (manual) _state.value = UpdateState.UpToDate(result.installedName)
                is CheckResult.Available -> {
                    _state.value = UpdateState.Available(result.release)
                    _prompt.value = true
                    Diagnostics.note(DiagnosticEvent.ErrorKind.other, "update: available ${result.release.version.name}")
                }
                is CheckResult.Failed -> {
                    Diagnostics.note(DiagnosticEvent.ErrorKind.network, "update check: ${result.failure.javaClass.simpleName}")
                    // A launch check that fails stays silent (a release whose APK isn't up yet included); a tap shows why.
                    if (manual) _state.value = UpdateState.Failed(result.failure)
                }
            }
        }
    }

    /// The row in You: show the dialog again for a known release.
    fun showPrompt() { if (_state.value is UpdateState.Available) _prompt.value = true }

    /// "Later".
    fun later() {
        _prompt.value = false
        (_state.value as? UpdateState.Available)?.let { checker.later(it.release) }
    }

    fun dismissFailure() { if (_state.value is UpdateState.Failed) _state.value = UpdateState.Idle }

    /// "Update": download, check, install.
    fun update() {
        val release = (_state.value as? UpdateState.Available)?.release ?: return
        if (busy()) return
        _prompt.value = false
        job = scope.launch {
            _state.value = UpdateState.Downloading(release, 0, release.apk.size)
            var lastShown = 0L
            val outcome = try {
                withContext(io) {
                    val ctx = coroutineContext
                    downloader.download(release, dir,
                        onProgress = { b, t ->
                            // A 1% step is enough to look smooth and keeps recomposition cheap.
                            if (b == t || b - lastShown >= maxOf(t / 100, 32_768)) { lastShown = b; _state.value = UpdateState.Downloading(release, b, t) }
                        },
                        checkCancelled = { ctx.ensureActive() })
                }
            } catch (e: CancellationException) {
                // The downloader removed the partial file; the offer stays so it can be started again.
                _state.value = UpdateState.Available(release)
                throw e
            }
            when (outcome) {
                is ApkDownloader.Outcome.Failed -> fail(outcome.failure)
                is ApkDownloader.Outcome.Done -> {
                    val installed = installer.installedFacts()
                    val bad = if (installed == null) UpdateFailure.UnreadableApk
                    else ArchiveCheck.verdict(installed, installer.archiveFacts(outcome.file))
                    if (bad != null) { outcome.file.delete(); fail(bad) } else proceed(release, outcome.file)
                }
            }
        }
    }

    /// Cancels a download in progress.
    fun cancel() { if (_state.value is UpdateState.Downloading) job?.cancel() }

    /// The person came back (from the unknown-sources screen or the installer): continue, or offer again.
    fun resume() {
        when (val s = _state.value) {
            is UpdateState.NeedsPermission -> if (installer.canInstall()) proceed(s.release, s.file)
            // Back from the system installer with Peel still running: it was declined or failed, so offer the update again.
            is UpdateState.Installing -> _state.value = UpdateState.Available(s.release)
            else -> Unit
        }
    }

    /// The person gave up at the permission step: the file goes, the offer stays.
    fun abandonPermission() {
        val s = _state.value as? UpdateState.NeedsPermission ?: return
        s.file.delete()
        _state.value = UpdateState.Available(s.release)
    }

    fun openPermissionSettings() { installer.openPermissionSettings() }

    private fun proceed(release: ReleaseInfo, file: File) {
        if (!installer.canInstall()) { _state.value = UpdateState.NeedsPermission(release, file); return }
        if (installer.install(file)) {
            _state.value = UpdateState.Installing(release)
            Diagnostics.note(DiagnosticEvent.ErrorKind.other, "update: installer started ${release.version.name}")
        } else { file.delete(); fail(UpdateFailure.InstallBlocked) }
    }

    private fun fail(failure: UpdateFailure) {
        Diagnostics.note(DiagnosticEvent.ErrorKind.other, "update failed: ${failure.javaClass.simpleName}")
        _state.value = UpdateState.Failed(failure, fromUpdate = true)
    }

    private fun busy() = _state.value is UpdateState.Downloading || _state.value is UpdateState.Checking
}
