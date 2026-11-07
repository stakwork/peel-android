package com.sphinx.peel.engine

import org.json.JSONObject

/// How this account appears in one tribe: a name and an https photo sent with
/// its messages there in place of the account's (PeelCore's `Tribe.myAlias` /
/// `myPhotoURL`, the official apps' `my_alias` / `my_photo_url`). Kept on this
/// device only, as iOS keeps it; members see it from the next message on.
/// Pure, so JVM-tested.
object TribeIdentity {
    data class Identity(val alias: String? = null, val photoUrl: String? = null)

    /// A tribe alias: ASCII letters, digits and underscores, spaces as underscores
    /// (`tribeAlias`); "Unknown" when nothing is left.
    fun tribeAlias(alias: String): String =
        alias.replace(' ', '_').filter { it.code < 128 && (it.isLetterOrDigit() || it == '_') }.ifEmpty { "Unknown" }

    /// What members will read for a typed name, only when it is not what was typed.
    fun aliasNote(typed: String): String? {
        val text = typed.trim().takeIf { it.isNotEmpty() } ?: return null
        val sent = tribeAlias(text)
        return if (sent == text) null else "Members see \u201C$sent\u201D."
    }

    /// Trimmed, empty as none; a failure when the photo is not https (refused).
    fun cleanPhoto(photo: String?): Result<String?> {
        val text = photo?.trim()?.takeIf { it.isNotEmpty() } ?: return Result.success(null)
        return if (text.startsWith("https://", ignoreCase = true)) Result.success(text)
        else Result.failure(IllegalArgumentException("The photo must be an https link."))
    }

    fun decode(stored: String?): Map<String, Identity> {
        val root = stored?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyMap()
        val out = mutableMapOf<String, Identity>()
        for (key in root.keys()) {
            val item = root.optJSONObject(key) ?: continue
            val identity = Identity(
                item.optString("a").takeIf { it.isNotEmpty() },
                item.optString("p").takeIf { it.isNotEmpty() },
            )
            if (identity != Identity()) out[key] = identity
        }
        return out
    }

    fun encode(store: Map<String, Identity>): String {
        val root = JSONObject()
        for ((chat, identity) in store) {
            if (identity == Identity()) continue
            root.put(chat, JSONObject().apply {
                identity.alias?.let { put("a", it) }
                identity.photoUrl?.let { put("p", it) }
            })
        }
        return root.toString()
    }

    /// What goes out with a message to a tribe: its own alias (as a tribe alias) and
    /// photo where set, else the account's.
    fun sent(identity: Identity?, accountAlias: String, accountPhoto: String): Pair<String, String> =
        (identity?.alias?.let(::tribeAlias) ?: accountAlias) to (identity?.photoUrl ?: accountPhoto)
}
