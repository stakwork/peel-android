package com.sphinx.peel.engine

import com.sphinx.peel.storage.MessageRow
import org.json.JSONArray
import org.json.JSONObject

/// A member of a tribe, or someone asking to join it ([pending]). [requestUuid] is the
/// uuid of the join request message an approve or reject answers.
data class TribeMember(
    val pubkey: String,
    val alias: String?,
    val photoUrl: String?,
    val pending: Boolean = false,
    val requestUuid: String? = null,
)

/// Message types a tribe's owner sends to run it (the relay's `MessageType`: the same numbers
/// as `GROUP_JOIN_MESSAGE_TYPE` and `MEMBER_APPROVE_MESSAGE_TYPE`, which are in `Attachment.kt`).
const val GROUP_LEAVE_MESSAGE_TYPE = 15
const val GROUP_KICK_MESSAGE_TYPE = 16
const val MEMBER_REQUEST_MESSAGE_TYPE = 19
const val MEMBER_REJECT_MESSAGE_TYPE = 21

/// Reading the member list, working out who is waiting to be let in, and the bodies of the
/// messages that approve, reject, kick and leave. Pure, so JVM-tested.
object TribeMembers {
    /// The core's `RunReturn.tribeMembers`: an array of members, or an object holding `members`
    /// and/or `pending` arrays. A member's key is `pubkey`; its name `alias` (or `nickname`); its
    /// picture `photo_url` (or `img`). Anything unreadable is skipped, so no list is no members.
    fun parse(json: String?): List<TribeMember> {
        val text = json?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val listed = mutableListOf<TribeMember>()
        if (text.startsWith("[")) {
            readArray(runCatching { JSONArray(text) }.getOrNull(), pending = false, into = listed)
        } else {
            val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
            readArray(root.optJSONArray("members"), pending = false, into = listed)
            readArray(root.optJSONArray("pending"), pending = true, into = listed)
        }
        return listed.distinctBy { it.pubkey to it.pending }
    }

    private fun readArray(array: JSONArray?, pending: Boolean, into: MutableList<TribeMember>) {
        if (array == null) return
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val key = item.optString("pubkey").trim().takeIf { it.isNotEmpty() } ?: continue
            into.add(
                TribeMember(
                    pubkey = key,
                    alias = (item.optString("alias").ifEmpty { item.optString("nickname") }).trim().takeIf { it.isNotEmpty() },
                    photoUrl = (item.optString("photo_url").ifEmpty { item.optString("img") }).trim().takeIf { it.isNotEmpty() },
                    pending = pending || item.optBoolean("pending", false),
                ),
            )
        }
    }

    /// Join requests in a tribe's stored messages that nothing has answered yet: a type-19 message
    /// from someone else, with no later approve (20) or reject (21) naming it by `replyUuid`, oldest first.
    /// The asker is read from the request's own body (`pubkey`), else its sender's alias and photo stand in.
    fun pendingRequests(rows: List<MessageRow>): List<TribeMember> {
        val answered = rows.filter { it.type == MEMBER_APPROVE_MESSAGE_TYPE || it.type == MEMBER_REJECT_MESSAGE_TYPE }
            .mapNotNull { it.replyUuid }.toSet()
        return rows.filter { it.type == MEMBER_REQUEST_MESSAGE_TYPE && !it.fromMe && it.uuid != null && it.uuid !in answered }
            .sortedBy { it.timestamp ?: 0L }
            .map { row ->
                val body = row.body?.let { runCatching { JSONObject(it) }.getOrNull() }
                val sender = row.sender?.let { runCatching { JSONObject(it) }.getOrNull() }
                TribeMember(
                    pubkey = body?.optString("pubkey")?.takeIf { it.isNotEmpty() } ?: sender?.optString("pubkey").orEmpty(),
                    alias = sender?.optString("alias")?.takeIf { it.isNotEmpty() },
                    photoUrl = sender?.optString("photo_url")?.takeIf { it.isNotEmpty() },
                    pending = true,
                    requestUuid = row.uuid,
                )
            }
    }

    /// The listed members, then anyone with a pending request who is not already listed.
    fun merge(listed: List<TribeMember>, requests: List<TribeMember>): List<TribeMember> {
        val known = listed.map { it.pubkey }.toSet()
        val withRequests = listed.map { member ->
            if (member.pending) requests.firstOrNull { it.pubkey == member.pubkey }?.let { member.copy(requestUuid = it.requestUuid) } ?: member else member
        }
        return withRequests + requests.filter { it.pubkey !in known }
    }

    /// The body that approves (type 20) or rejects (type 21) a request: names the request by `replyUuid`.
    fun answerBody(member: TribeMember): String = JSONObject().apply {
        put("content", "")
        member.requestUuid?.let { put("replyUuid", it) }
        put("pubkey", member.pubkey)
    }.toString()

    /// The body that removes a member (type 16).
    fun kickBody(memberPubkey: String): String = JSONObject().put("content", "").put("pubkey", memberPubkey).toString()

    /// The body of a leave (type 15).
    fun leaveBody(): String = JSONObject().put("content", "").toString()
}
