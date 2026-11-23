package com.sphinx.peel.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/// What the pinned transfer bar shows: an oversized video being compressed, then any attachment uploading.
sealed class TransferState {
    data object Idle : TransferState()
    data class Compressing(val progress: Float) : TransferState()
    data class Uploading(val progress: Float) : TransferState()
    data object Done : TransferState()
    data class Failed(val reason: String) : TransferState()
}

/// The one holder of [TransferState], owned by `Engine`; `ui/dashboard/TransferBar.kt` draws it.
class TransferTracker {
    private val mutable = MutableStateFlow<TransferState>(TransferState.Idle)
    val state: StateFlow<TransferState> get() = mutable

    fun compressing(progress: Float) { mutable.value = TransferState.Compressing(progress.coerceIn(0f, 1f)) }
    fun uploading(progress: Float) { mutable.value = TransferState.Uploading(progress.coerceIn(0f, 1f)) }
    fun done() { mutable.value = TransferState.Done }
    fun failed(reason: String) { mutable.value = TransferState.Failed(reason) }
    fun idle() { mutable.value = TransferState.Idle }

    /// Dismisses a finished or failed bar; a transfer in progress is left alone.
    fun dismiss() {
        val now = mutable.value
        if (now is TransferState.Done || now is TransferState.Failed) mutable.value = TransferState.Idle
    }

    /// Progress of [done] of [total] bytes, as 0..1.
    companion object {
        fun fraction(done: Long, total: Long): Float = if (total <= 0) 0f else (done.toDouble() / total).toFloat().coerceIn(0f, 1f)
    }
}
