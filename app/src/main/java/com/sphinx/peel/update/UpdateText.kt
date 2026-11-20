package com.sphinx.peel.update

import java.text.DateFormat
import java.util.Date

/// The words of the You row and the dialog, pure so they are tested.
object UpdateText {
    fun mb(bytes: Long): String = "%.1f MB".format(java.util.Locale.US, bytes / 1_048_576.0)

    fun progress(bytes: Long, total: Long): String =
        if (total > 0) "${mb(bytes)} of ${mb(total)}" else mb(bytes)

    fun fraction(bytes: Long, total: Long): Float? =
        if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else null

    /// The row's right-hand text.
    fun rowValue(state: UpdateState, installedName: String): String = when (state) {
        UpdateState.Idle -> "Version $installedName"
        UpdateState.Checking -> "Checking…"
        is UpdateState.UpToDate -> "You're on the latest version"
        is UpdateState.Available -> "Version ${state.release.version.name} available"
        is UpdateState.Downloading -> "Downloading…"
        is UpdateState.NeedsPermission -> "Needs permission"
        is UpdateState.Installing -> "Installing…"
        is UpdateState.Failed -> "Version $installedName"
    }

    /// The line under the row, for a failed check; a rate limit says when to try again.
    fun rowDetail(state: UpdateState, now: () -> Long = System::currentTimeMillis): String? {
        val failure = (state as? UpdateState.Failed)?.failure ?: return null
        if (failure is UpdateFailure.RateLimited && failure.resetAtMillis != null && failure.resetAtMillis > now()) {
            val at = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(failure.resetAtMillis))
            return "GitHub is limiting update checks right now. Try again after $at."
        }
        return failure.message
    }
}
