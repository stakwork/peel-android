package com.sphinx.peel.diagnostics

/// The names a diagnostics record is redacted with (iOS `DiagnosticsNames`), built from the chats when
/// it is read and never kept: tribe names and ids may stay, contacts' names and the user's own are removed.
data class DiagnosticsNames(val allow: List<String>, val knownNames: List<String>) {
    /// `chats` as (key, alias, isTribe).
    constructor(chats: List<Triple<String, String?, Boolean>>, myAlias: String?) : this(
        allow = chats.filter { it.third }.mapNotNull { it.second } + chats.filter { it.third }.map { it.first },
        knownNames = chats.filter { !it.third }.mapNotNull { it.second } + listOfNotNull(myAlias),
    )
}
