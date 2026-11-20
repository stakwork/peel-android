package com.sphinx.peel.update

import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException

/// An open download: the bytes, and how many the server says there are (-1 unknown). Closing it ends the request.
interface ApkStream : AutoCloseable {
    val length: Long
    val input: InputStream
}

/// The seam over OkHttp. Throws [IOException] for no answer or a non-200 one.
fun interface ApkFetch {
    fun open(url: String): ApkStream
}

/// Where updates are kept (`cacheDir/updates/`, shared through file_paths.xml's `updates` entry).
object UpdateFiles {
    const val DIR = "updates"
    const val PART = ".part"

    fun apkName(version: ReleaseVersion) = "peel-${version.name}.apk"

    /// Names to delete at launch: every partial file, and any APK not newer than the installed build (installed, or
    /// never will be). Only we write here, so an unknown name is ours to delete too.
    fun stale(names: List<String>, installedCode: Int): List<String> = names.filter { name ->
        if (name.endsWith(PART)) return@filter true
        val v = Regex("""^peel-(.+)\.apk$""").matchEntire(name)?.groupValues?.get(1)?.let { ReleaseVersion.parseTag("v$it") }
        v == null || v.code <= installedCode
    }

    fun clean(dir: File, installedCode: Int) {
        val names = dir.list()?.toList() ?: return
        stale(names, installedCode).forEach { File(dir, it).delete() }
    }

    fun clearAll(dir: File) { dir.listFiles()?.forEach { it.delete() } }
}

/// Fetches an APK into `<dir>/peel-X.Y.Z.apk` through a `.part` file, so a half-finished download never has the
/// installable name. Whatever goes wrong (error, cancel, wrong size) leaves nothing behind.
class ApkDownloader(private val fetch: ApkFetch) {
    sealed class Outcome {
        data class Done(val file: File) : Outcome()
        data class Failed(val failure: UpdateFailure) : Outcome()
    }

    /// [onProgress] gets (bytes so far, total or -1). To cancel, throw [CancellationException] from [checkCancelled];
    /// the partial file is removed and it propagates.
    fun download(
        release: ReleaseInfo, dir: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        checkCancelled: () -> Unit = {},
    ): Outcome {
        if (!release.apk.url.startsWith("https://")) return Outcome.Failed(UpdateFailure.Insecure)
        if (!dir.exists() && !dir.mkdirs()) return Outcome.Failed(UpdateFailure.Storage("mkdirs"))
        UpdateFiles.clearAll(dir)
        val target = File(dir, UpdateFiles.apkName(release.version))
        val part = File(dir, target.name + UpdateFiles.PART)
        try {
            fetch.open(release.apk.url).use { stream ->
                val total = if (release.apk.size > 0) release.apk.size else stream.length
                var done = 0L
                val buffer = ByteArray(64 * 1024)
                part.outputStream().use { out ->
                    onProgress(0, total)
                    while (true) {
                        checkCancelled()
                        val n = stream.input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            // The size GitHub lists is the integrity check we have without a published digest.
            if ((release.apk.size > 0 && part.length() != release.apk.size) || part.length() == 0L) {
                part.delete()
                return Outcome.Failed(UpdateFailure.SizeMismatch)
            }
            if (!part.renameTo(target)) { part.delete(); return Outcome.Failed(UpdateFailure.Storage("rename")) }
            return Outcome.Done(target)
        } catch (e: CancellationException) {
            part.delete()
            throw e
        } catch (e: IOException) {
            part.delete()
            return Outcome.Failed(if (e.message?.contains("ENOSPC") == true) UpdateFailure.Storage(e.message) else UpdateFailure.Offline)
        } catch (e: Exception) {
            part.delete()
            return Outcome.Failed(UpdateFailure.Storage(e.javaClass.simpleName))
        }
    }
}

/// What the system reads from an APK file (or the installed app): package, version code, signing certs (hex SHA-256).
data class PackageFacts(val packageName: String, val versionCode: Long, val signers: Set<String>)

/// Refuses early what the system installer would refuse late (after the person went through its screens): another
/// package, another signing key, not newer.
object ArchiveCheck {
    fun verdict(installed: PackageFacts, archive: PackageFacts?): UpdateFailure? = when {
        archive == null || archive.signers.isEmpty() -> UpdateFailure.UnreadableApk
        archive.packageName != installed.packageName -> UpdateFailure.UnreadableApk
        archive.signers != installed.signers -> UpdateFailure.WrongSignature
        archive.versionCode <= installed.versionCode -> UpdateFailure.NotNewer
        else -> null
    }
}
