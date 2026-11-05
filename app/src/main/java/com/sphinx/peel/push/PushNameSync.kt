package com.sphinx.peel.push

import com.sphinx.peel.engine.Engine
import com.sphinx.peel.storage.Storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/// Keeps the sealed name directory ([PushNameStore]) current while an
/// account is open: rewritten (debounced) on open, when a restore finishes,
/// on every (re)connect and whenever a contact row changes. A contact's or
/// tribe's child index is the core's `my_idx` (`Engine.contactChildIndexes`).
/// Whether a row is a tribe, its name and its chat id come from `Storage`.
/// Not written while a restore runs (the contacts are not all there yet).
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class PushNameSync(private val engine: Engine, private val storage: Storage, private val store: PushNameStore) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val kicks = MutableStateFlow(0)
    @Volatile private var lastWritten: String? = null

    fun start() {
        scope.launch {
            combine(storage.contactsVersion, engine.restoreProgress, kicks) { _, progress, _ -> progress.restoring }
                .debounce(DEBOUNCE_MS)
                .collect { restoring -> if (!restoring) rewrite() }
        }
    }

    /// Something that may have changed names (a reconnect).
    fun kick() { kicks.value += 1 }

    fun stop() = scope.cancel()

    private fun rewrite() {
        val directory = runCatching { build() }.getOrNull() ?: return
        val encoded = directory.encoded()
        if (encoded == lastWritten) return
        if (store.write(directory)) lastWritten = encoded
    }

    private fun build(): PushNameDirectory {
        val contacts = mutableMapOf<ULong, String>()
        val tribes = mutableMapOf<ULong, String>()
        val contactChats = mutableMapOf<ULong, String>()
        val tribeChats = mutableMapOf<ULong, String>()
        for ((chat, index) in engine.contactChildIndexes()) {
            val row = storage.contact(chat)
            val name = row?.alias.orEmpty()
            if (row?.isTribe == true) {
                tribes[index] = name
                tribeChats[index] = chat
            } else {
                contacts[index] = name
                contactChats[index] = chat
            }
        }
        return PushNameDirectory(contacts, tribes, contactChats, tribeChats).cleaned()
    }

    private companion object {
        const val DEBOUNCE_MS = 2_000L
    }
}
