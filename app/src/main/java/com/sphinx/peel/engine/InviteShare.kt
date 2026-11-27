package com.sphinx.peel.engine

/// What Copy and Share send for an invite someone made: a short message with the context a stranger needs and an https
/// join link, in place of the bare `sphinx.chat://?…` link (which a messenger does not make tappable and a phone without
/// Peel cannot open). The words are string resources, passed in as templates so this stays pure and JVM-tested. The QR
/// code keeps the raw link. [InviteLink.find] reads the link back out of the whole message when it is pasted.
object InviteShare {
    /// [named] takes the alias (`%1$s`) and the link (`%2$s`); [anonymous] takes the link (`%1$s`). A personal [note] goes
    /// first as its own paragraph. When the link cannot be made into a join link the raw one is sent instead.
    fun message(link: String, alias: String?, note: String?, named: String, anonymous: String, host: String = InviteLink.JOIN_HOST): String {
        val shown = InviteLink.joinLink(link, host) ?: link
        val name = alias?.trim()?.takeIf { it.isNotEmpty() }
        val body = if (name != null) String.format(named, name, shown) else String.format(anonymous, shown)
        val personal = note?.trim()?.takeIf { it.isNotEmpty() }
        return if (personal != null) "$personal\n\n$body" else body
    }
}
