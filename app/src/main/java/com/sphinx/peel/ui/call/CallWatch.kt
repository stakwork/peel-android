package com.sphinx.peel.ui.call

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.sphinx.peel.engine.CALL_MESSAGE_TYPE
import com.sphinx.peel.engine.CallLink
import com.sphinx.peel.engine.CallPresence
import com.sphinx.peel.engine.Engine
import com.sphinx.peel.storage.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject

/// How long a call message may still be live (PeelCore's `CallLink.liveAge`)
/// and how many calls are watched at once (`WatchedCall.limit`).
private const val LIVE_AGE_MS = 12L * 60 * 60 * 1000
private const val CLOCK_SKEW_MS = 5L * 60 * 1000
private const val WATCH_LIMIT = 8

/// How many people are on a call in each chat now, from the call server's
/// presence feed (`Engine.callPresence`) for each chat's newest recent call
/// message — PeelCore's `WatchedCall.watched` + `CallStore.liveCounts`
/// (what iOS's chat row reads as `liveCount(in:)`). Chats with nobody on
/// a call are absent.
@Composable
fun rememberLiveCallCounts(engine: Engine, storage: Storage): Map<String, Int> {
    var counts by remember { mutableStateOf(emptyMap<String, Int>()) }
    LaunchedEffect(engine) {
        val server = engine.callServer() ?: return@LaunchedEffect
        var watched = emptyMap<String, CallLink>()
        coroutineScope {
            val jobs = mutableMapOf<String, kotlinx.coroutines.Job>()
            val live = mutableMapOf<String, Int>()
            while (true) {
                val now = System.currentTimeMillis()
                val rows = withContext(Dispatchers.Default) { storage.recentCallMessages(now - LIVE_AGE_MS) }
                val wanted = linkedMapOf<String, CallLink>()
                for (row in rows) {
                    if (wanted.size >= WATCH_LIMIT) break
                    if (row.chat in wanted || (row.timestamp ?: 0) - now > CLOCK_SKEW_MS) continue
                    val content = runCatching { JSONObject(row.body ?: "").optString("content") }.getOrDefault("")
                    CallLink.inMessage(content, isCallType = row.type == CALL_MESSAGE_TYPE, server = server)
                        ?.let { wanted[row.chat] = it }
                }
                for ((chat, job) in jobs.toMap()) {
                    if (wanted[chat]?.room != watched[chat]?.room) {
                        job.cancel(); jobs.remove(chat); live.remove(chat)
                        counts = live.toMap()
                    }
                }
                for ((chat, link) in wanted) {
                    if (chat in jobs) continue
                    jobs[chat] = launch {
                        engine.callPresence(link).collect { presence ->
                            val n = (presence as? CallPresence.Live)?.people?.size ?: 0
                            if (n > 0) live[chat] = n else live.remove(chat)
                            counts = live.toMap()
                        }
                    }
                }
                watched = wanted
                delay(15_000)
            }
        }
    }
    return counts
}
