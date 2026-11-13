package com.sphinx.peel.engine

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/// A Talk session saved after a call ends (iOS `TalkSavedSession`, `TalkHistory.swift`).
data class TalkSavedSession(
    val id: String,
    /// `TalkHome.id` (workspace slug or `@development`).
    val homeId: String,
    val homeName: String,
    /// When the call ended.
    val date: Instant,
    val lines: List<TalkLine>,
) {
    /// The first line's text as a preview; null when the transcript is empty.
    val preview: String? get() = lines.firstOrNull()?.text

    fun toJson(): String = JSONObject()
        .put("id", id).put("homeId", homeId).put("homeName", homeName).put("date", date.toString())
        .put("lines", JSONArray(lines.map {
            JSONObject().put("id", it.id).put("speaker", if (it.speaker == TalkLine.Speaker.YOU) "you" else "agent")
                .put("text", it.text).put("isFinal", it.isFinal)
        }))
        .toString()

    companion object {
        /// Null for anything that is not a saved session.
        fun parse(json: String): TalkSavedSession? = try {
            val o = JSONObject(json)
            val lines = o.getJSONArray("lines")
            TalkSavedSession(
                o.getString("id"), o.getString("homeId"), o.getString("homeName"), Instant.parse(o.getString("date")),
                (0 until lines.length()).map {
                    val l = lines.getJSONObject(it)
                    TalkLine(
                        l.getString("id"),
                        when (l.getString("speaker")) { "you" -> TalkLine.Speaker.YOU; "agent" -> TalkLine.Speaker.AGENT; else -> return null },
                        l.getString("text"), l.optBoolean("isFinal", true),
                    )
                },
            )
        } catch (e: Exception) {
            null
        }
    }
}

/// Reads and writes saved Talk sessions as `<dir>/<homeId>_<ISO8601>.json` (iOS `TalkHistoryStore`, in the app's
/// files dir rather than Application Support). Never throws: a failed write or an unreadable file is skipped.
class TalkHistoryStore(private val dir: File) {
    /// Saves [lines] for the home; nothing for an empty transcript. Returns the saved session.
    fun save(lines: List<TalkLine>, homeId: String, homeName: String, now: Instant = Instant.now()): TalkSavedSession? {
        if (lines.isEmpty()) return null
        val session = TalkSavedSession(UUID.randomUUID().toString(), homeId, homeName, now, lines)
        return try {
            dir.mkdirs()
            val name = "${safe(homeId)}_${now.toString().replace(":", "-")}.json"
            val target = File(dir, name)
            val temp = File(dir, "$name.tmp")
            temp.writeText(session.toJson())
            if (!temp.renameTo(target)) { temp.delete(); return null }
            session
        } catch (e: Exception) {
            null
        }
    }

    /// All saved sessions of [homeId], newest first.
    fun sessions(homeId: String): List<TalkSavedSession> {
        val prefix = safe(homeId) + "_"
        val files = dir.listFiles() ?: return emptyList()
        return files.filter { it.name.startsWith(prefix) && it.extension == "json" }
            .mapNotNull { runCatching { TalkSavedSession.parse(it.readText()) }.getOrNull() }
            // iOS takes the file name's prefix alone; a session of another home that shares it is left out here.
            .filter { it.homeId == homeId }
            .sortedByDescending { it.date }
    }

    private fun safe(id: String) = id.replace("/", "_").replace(":", "_")
}

/// How the history shows a date (iOS `Date.FormatStyle`: abbreviated month, day, 2-digit year, hour, minute).
object TalkHistoryFormat {
    fun row(date: Instant, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofPattern("MMM d, yy, h:mm a", locale).withZone(zone).format(date)

    fun title(date: Instant, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofPattern("MMM d, h:mm a", locale).withZone(zone).format(date)

    fun speaker(line: TalkLine): String = if (line.speaker == TalkLine.Speaker.YOU) "You" else "Jamie"
}
