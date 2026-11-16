package com.sphinx.peel.ui.diagnostics

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sphinx.peel.diagnostics.Diagnostics
import com.sphinx.peel.diagnostics.DiagnosticsNames
import com.sphinx.peel.storage.Setting
import com.sphinx.peel.storage.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/// iOS `AppModel+Diagnostics`: whether the notice shows (the last run ended badly), and the record the
/// sheet shows. App-wide, since the notice sits on Chats and is dismissed for the run.
object DiagnosticsModel {
    /// The notice at the top of Chats: set at launch from how the last run ended, cleared by Dismiss or Delete.
    var notice by mutableStateOf(Diagnostics.previousRun().isProblem)

    /// The record as the sheet shows it: redacted text only. Null: the sheet is closed.
    var record by mutableStateOf<String?>(null)

    /// Opens the record: the newest crash record, redacted now with this account's tribe names and keys
    /// kept and its contacts' names removed (built here, never stored), or the ring alone when the run
    /// was ended without a record.
    suspend fun showRecord(storage: Storage) {
        val text = withContext(Dispatchers.IO) {
            val chats = storage.conversations().map { Triple(it.chat, it.alias, it.isTribe) }
            val names = DiagnosticsNames(chats, storage.value(Setting.ALIAS))
            Diagnostics.latestRedactedReport(names.allow, names.knownNames)?.rendered() ?: Diagnostics.snapshot().rendered()
        }
        record = text
    }

    /// Removes the record, raw and redacted, and the notice.
    fun deleteRecord() {
        Diagnostics.deleteRecords()
        record = null
        notice = false
    }

    fun closeRecord() { record = null }
}
