package com.sphinx.peel.diagnostics

import java.io.File

/// The Diagnostics folder (iOS `DiagnosticsStore`): `raw/` (private: the records as found) and
/// `redacted/` (only what `Redactor` made), the ring and the run marker. Takes its folder as a
/// parameter. The strut's stderr tail is Mac-only and not ported. Nothing here is public: the raw
/// record never leaves the module.
class DiagnosticsStore(val root: File) {
    val raw: File get() = File(root, "raw")
    val redacted: File get() = File(root, "redacted")
    val ringFile: File get() = File(root, "events.ring")
    val markerFile: File get() = File(root, "run-marker.json")
    /// Written by the uncaught-exception handler: the id of the record it just saved, read (and removed)
    /// by the next launch. Stands in for PLCrashReporter's pending report.
    val pendingFile: File get() = File(root, "pending-crash")

    /// Creates the folders. The app's private storage is owner-only already; the caller gives a folder
    /// outside backups (`Context.noBackupFilesDir`).
    fun prepare() {
        for (folder in listOf(root, raw, redacted)) folder.mkdirs()
    }

    /// Ids are ours (a UUID), but a name read from disk is still checked.
    private fun file(id: String, folder: File, ext: String): File? =
        if (id.isNotEmpty() && id.all { it.isLetterOrDigit() || it == '-' }) File(folder, "$id.$ext") else null

    fun saveRaw(record: CrashRecord) {
        val target = file(record.id, raw, "json") ?: return
        raw.mkdirs()
        target.writeText(record.toJson())
    }

    /// Ids of the raw records, newest first.
    fun rawIDs(): List<String> =
        (raw.listFiles() ?: emptyArray()).filter { it.extension == "json" }
            .sortedByDescending { it.lastModified() }.map { it.nameWithoutExtension }

    fun loadRaw(id: String): CrashRecord? = file(id, raw, "json")?.takeIf { it.exists() }?.let { CrashRecord.fromJson(it.readText()) }

    fun saveRedacted(record: RedactedRecord) {
        val target = file(record.id, redacted, "json") ?: return
        redacted.mkdirs()
        target.writeText(record.toJson())
    }

    fun loadRedacted(id: String): RedactedRecord? =
        file(id, redacted, "json")?.takeIf { it.exists() }?.let { RedactedRecord.fromJson(it.readText()) }

    /// Removes the raw and redacted copies of every record.
    fun deleteAll() {
        for (folder in listOf(raw, redacted)) (folder.listFiles() ?: emptyArray()).forEach { it.delete() }
        pendingFile.delete()
    }
}

/// When a memory sample is worth a ring entry: the first, or one that moved by 5% or more since the
/// last written; pressure always (iOS `MemoryPolicy`).
object MemoryPolicy {
    fun shouldRecord(last: Long?, new: Long, pressure: Boolean = false): Boolean {
        if (pressure) return true
        if (last == null || last <= 0) return true
        val change = if (new > last) new - last else last - new
        return change.toDouble() / last.toDouble() >= 0.05
    }
}

/// The process's memory. Android: `Debug.MemoryInfo`'s PSS for the footprint (the closest to iOS's
/// `phys_footprint`), the system's available memory for `available` (`AndroidMemoryProbe`).
interface MemoryProbe {
    fun footprint(): Long?
    fun available(): Long?
}
