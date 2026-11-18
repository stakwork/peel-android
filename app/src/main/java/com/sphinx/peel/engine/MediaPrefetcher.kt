package com.sphinx.peel.engine

import com.sphinx.peel.storage.MessageRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/// What an attachment row shows. Absent from `MediaPrefetcher.states` = not looked at yet.
sealed class AttachmentState {
    /// Queued for the network, not started.
    data object Waiting : AttachmentState()
    /// Downloading; `fraction` is 0..1 when the server announced a length.
    data class Loading(val fraction: Float?) : AttachmentState()
    /// Not fetched unasked (setting off, a file, or over the cap): a tile with a download button.
    data object AwaitsDownload : AttachmentState()
    /// A transient failure: retries by itself (2 s, 10 s, 30 s) and when the connection returns
    /// or the app comes back to the front; a tap retries now.
    data object Retryable : AttachmentState()
    /// A permanent failure, with a short cause.
    data class Failed(val cause: String) : AttachmentState()
    data class Loaded(val attachment: DownloadedAttachment) : AttachmentState()
}

/// Downloads attachments for the whole engine, not for a composable: a download
/// outlives the chat screen, a live message does not wait for its row to be shown, and a
/// transient failure is retried here rather than lost with the composition. Bounded
/// concurrency, one job per attachment (deduped by cache key), and every automatic fetch
/// goes through `AutoDownloadPolicy` — the same gate for a row showing, a live message and a retry.
///
/// WorkManager is deliberately not used: the memes sign-in needs the account seed, which
/// only exists while the engine is unlocked in this process, so a Worker could not download
/// while locked and would only add a dependency.
class MediaPrefetcher(
    private val scope: CoroutineScope,
    /// (row, cachedOnly, upTo, progress(read, total)) -> the attachment; `Engine.downloadAttachment`.
    private val fetch: suspend (MessageRow, Boolean, Int?, (Long, Long) -> Unit) -> DownloadedAttachment,
    private val autoOn: () -> Boolean,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    concurrency: Int = 2,
) {
    private val gate = Semaphore(concurrency)
    private val lock = Any()
    private val jobs = HashMap<String, Job>()
    private val retryable = HashMap<String, MessageRow>()
    private val mutable = MutableStateFlow<Map<String, AttachmentState>>(emptyMap())

    /// The state of every attachment seen, by [keyFor].
    val states: StateFlow<Map<String, AttachmentState>> = mutable.asStateFlow()

    /// An automatic fetch for a row that is showing, or a message that just arrived.
    fun request(row: MessageRow) {
        val key = keyFor(row)
        when (val known = mutable.value[key]) {
            is AttachmentState.Loaded -> if (known.attachment.file.exists()) return
            is AttachmentState.Failed, is AttachmentState.Retryable -> return
            else -> {}
        }
        start(key, row, explicit = false)
    }

    /// The person tapped: download now, up to the full limit, with the attempts reset.
    fun tap(row: MessageRow) {
        val key = keyFor(row)
        synchronized(lock) { jobs.remove(key)?.cancel() }
        start(key, row, explicit = true)
    }

    /// The connection came back, or the app returned to the front: try the ones waiting on a retry again.
    fun retryPending() {
        val pending = synchronized(lock) { retryable.entries.map { it.key to it.value } }
        for ((key, row) in pending) {
            synchronized(lock) { jobs.remove(key)?.cancel() }
            start(key, row, explicit = false)
        }
    }

    private fun start(key: String, row: MessageRow, explicit: Boolean) {
        synchronized(lock) {
            if (jobs[key]?.isActive == true) return
            jobs[key] = scope.launch { run(key, row, explicit) }
        }
    }

    private fun set(key: String, state: AttachmentState) {
        mutable.update { it + (key to state) }
    }

    private suspend fun run(key: String, row: MessageRow, explicit: Boolean) {
        synchronized(lock) { retryable.remove(key) }
        val kind = attachmentKind(AttachmentFields.decode(row.body)?.mediaType)
        var attempt = 0
        // A copy already on disk is shown at once, whatever the setting.
        try {
            set(key, AttachmentState.Loaded(fetch(row, true, null) { _, _ -> }))
            return
        } catch (e: CancellationException) {
            throw e
        } catch (_: AttachmentNotCached) {
        } catch (e: Exception) {
            set(key, AttachmentState.Failed(AttachmentFailure.describe(e)))
            return
        }
        val upTo: Int? = if (explicit) {
            null
        } else {
            when (val decision = AutoDownloadPolicy.decide(kind, null, autoOn())) {
                is AutoDownloadPolicy.Decision.FetchNow -> decision.upTo
                AutoDownloadPolicy.Decision.CachedOnly, AutoDownloadPolicy.Decision.Hold -> {
                    set(key, AttachmentState.AwaitsDownload)
                    return
                }
            }
        }
        while (true) {
            try {
                set(key, AttachmentState.Waiting)
                val loaded = gate.withPermit {
                    set(key, AttachmentState.Loading(null))
                    var shown = 0f
                    fetch(row, false, upTo) { read, total ->
                        if (total > 0) {
                            val fraction = (read.toFloat() / total).coerceIn(0f, 1f)
                            if (fraction - shown >= 0.01f) {
                                shown = fraction
                                set(key, AttachmentState.Loading(fraction))
                            }
                        }
                    }
                }
                set(key, AttachmentState.Loaded(loaded))
                return
            } catch (e: CancellationException) {
                throw e
            } catch (_: AttachmentNotCached) {
                set(key, AttachmentState.AwaitsDownload)
                return
            } catch (_: AttachmentHeld) {
                set(key, AttachmentState.AwaitsDownload)
                return
            } catch (e: Exception) {
                if (AttachmentFailure.classify(e) == AttachmentFailure.Kind.PERMANENT) {
                    set(key, AttachmentState.Failed(AttachmentFailure.describe(e)))
                    return
                }
                set(key, AttachmentState.Retryable)
                synchronized(lock) { retryable[key] = row }
                val wait = RetryBackoff.delayMs(attempt++) ?: return
                pause(wait)
                synchronized(lock) { retryable.remove(key) }
            }
        }
    }

    companion object {
        /// What a row's state is filed under: the cache key (so two rows naming the same file share
        /// one download), or the row itself when it names nothing readable (its failure is then shown).
        fun keyFor(row: MessageRow): String {
            val fields = AttachmentFields.decode(row.body)
            val muid = fields?.mediaToken?.let { MediaToken.parse(it) }?.muid?.takeIf { MediaToken.isSafeMuid(it) }
            val key = fields?.ownKey
            return if (muid != null && key != null) AttachmentCache.fileKey(muid, key) else "row-${row.id}-${row.uuid}"
        }
    }
}
