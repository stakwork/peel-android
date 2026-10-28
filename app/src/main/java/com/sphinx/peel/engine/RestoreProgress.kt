package com.sphinx.peel.engine

/// How far one side of a restore (contacts, or messages) has got.
/// Mirrored from PeelUI's `AppModel.RestoreProgress.Count`.
data class RestoreCount(val reached: Long, val end: Long?) {
    val fraction: Double?
        get() = end?.takeIf { it > 0 }?.let { (reached.toDouble() / it).coerceIn(0.0, 1.0) }
}

sealed class RestoreOutcome {
    object Finished : RestoreOutcome()
    data class Failed(val message: String) : RestoreOutcome()
}

/// What the "Restoring your account" screen shows. Mirrored from PeelUI's
/// `AppModel.RestoreProgress` — trimmed: no stall/resume handling yet
/// (`Engine.runRestore` runs once, start to finish or to its first
/// failure), and no separate `restoring` persisted flag here beyond
/// `Setting.NEEDS_RESTORE`, which `Engine` already reads.
data class RestoreProgress(
    val restoring: Boolean = false,
    val contacts: RestoreCount? = null,
    val messages: RestoreCount? = null,
    val outcome: RestoreOutcome? = null,
)
