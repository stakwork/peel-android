package com.sphinx.peel.engine

import com.sphinx.peel.storage.MessageRow
import org.json.JSONObject
import java.util.Calendar

// The agent's DM and the device channel's rows as the phone draws them (iOS `ChatRow.rows` control handling, `AgentRequest`, `AgentReads`,
// `AgentToolTitle`; Apps/iOS/Peel/MessageRows.swift `AgentRequestLine`, `AgentToolLines`). Pure; JVM-tested. The phone never answers a request
// (docs/agent-on-strut.md, decision 9), so a request here is a quiet line saying how the Mac ended it, never a card.

/// A request from the agent to a device, as its DM's row says it: what was asked, and how the Mac answered (null while it works on it), with
/// what it answered. [asker] is who asked, outside the agent chat: any contact can send a request.
data class AgentRequest(
    val kind: String,
    /// What it was asked, as the agent wrote it: a JSON object, or null.
    val args: JSONObject? = null,
    val status: ControlEnvelope.Status?,
    /// Whether it waits on your answer on the Mac's card: a send in conversation, an approval, a change to your calendar. An automation's
    /// send goes on its grant.
    val asks: Boolean = false,
    /// The answer's text (`result.text`), as the Mac sent it.
    val text: String? = null,
    /// Why it was not done, as the Mac said it.
    val reason: String? = null,
    val asker: String? = null,
    /// After this second it is never done (`not_after`): unanswered by then, too late.
    val notAfter: Long? = null,
) {
    /// Unanswered past its expiry, as the Mac judges it: nothing will be done, whatever comes.
    fun isExpired(nowSeconds: Long): Boolean = status == null && notAfter?.let { ControlEnvelope.isPast(it, nowSeconds) } == true

    /// When (unix seconds) the row says something else without a new message: the second its expiry is past, while unanswered in the agent chat.
    val changesAt: Long? get() = if (status == null && asker == null) notAfter?.plus(1) else null

    /// What the row says: "Read your unread messages", after who asked outside the agent chat ("Kate: Read your unread messages").
    val label: String get() = asker?.let { "$it: $words" } ?: words

    /// "Read your unread messages", by the request's kind.
    val words: String
        get() = when {
            kind == "chats.list" -> "Read your chats"
            kind == "chats.read" -> "Read a chat"
            kind == "chats.threads" -> "Read threads"
            kind == "chats.unseen" -> "Read your unread messages"
            kind == "chats.search" -> "Search your messages"
            kind == "send" -> "Send a message"
            kind == "approve" -> "Asked for your approval"
            kind == "calendar.events" -> "Read your calendar"
            kind.startsWith("calendar.") -> "Change your calendar"
            else -> "Asked your Mac"
        }

    /// How it ended at [nowSeconds], after the words: null when it was done, as asked; unanswered past its expiry, too late, as the Mac would
    /// answer. Outside the agent chat, one never answered was not this Mac's.
    fun outcome(nowSeconds: Long): String? {
        val effective = if (isExpired(nowSeconds) && asker == null) ControlEnvelope.Status.EXPIRED else status
        return when (effective) {
            null -> if (asker != null) "Not answered" else "on your Mac…"
            ControlEnvelope.Status.DONE -> null
            ControlEnvelope.Status.DENIED -> "Declined"
            ControlEnvelope.Status.EXPIRED -> "Too late, nothing done"
            ControlEnvelope.Status.FAILED -> "Could not do it"
            ControlEnvelope.Status.UNKNOWN -> "Not sure it went"
        }
    }

    /// How it ended at [nowSeconds] on a device that never answers it (the phone, decision 9): its card waits on the Mac rather than working there.
    fun outcomeElsewhere(nowSeconds: Long): String? =
        if (status == null && asks && !isExpired(nowSeconds)) "Waiting for your approval on your Mac" else outcome(nowSeconds)

    companion object {
        /// [request], answered by [answer] (the Mac's result), if it was.
        fun of(request: ControlEnvelope, answer: ControlEnvelope?): AgentRequest {
            val kind = request.kind ?: ""
            val args = request.args as? JSONObject
            val automation = args?.let { it.has("automation") && !it.isNull("automation") } ?: false
            val said = (answer?.result as? JSONObject)?.opt("text") as? String
            return AgentRequest(
                kind = kind, args = request.args as? JSONObject, status = answer?.status,
                asks = kind == "approve" || (kind == "send" && !automation) || (kind.startsWith("calendar.") && kind != "calendar.events"),
                text = said, reason = answer?.error, notAfter = request.notAfter,
            )
        }
    }
}

/// One read folded into a row of the agent's DM: its message and how it went.
data class AgentRead(val message: MessageRow, val request: AgentRequest)

/// Reads the agent asked the Mac for back to back, done or on their way, as one line: "Checked unread messages, 4 chats and threads".
class AgentReads(reads: List<AgentRead>) {
    /// The kinds of read that fold; a send, an approval or a change to your calendar always has its own row.
    enum class Kind {
        UNSEEN, CHATS, CHAT, THREADS, SEARCH, CALENDAR;

        companion object {
            fun of(kind: String): Kind? = when (kind) {
                "chats.unseen" -> UNSEEN
                "chats.list" -> CHATS
                "chats.read" -> CHAT
                "chats.threads" -> THREADS
                "chats.search" -> SEARCH
                "calendar.events" -> CALENDAR
                else -> null
            }
        }
    }

    /// The reads of one kind: "Read 4 chats", and the chats or searches it named, as the agent asked for them, each once.
    class Step(val kind: Kind, val requests: MutableList<AgentRequest>, val subjects: MutableList<String>) {
        /// What it read, after "Read": "4 chats", "threads in 2 chats"; null for a search.
        private val objectText: String?
            get() {
                val count = maxOf(subjects.size, 1)
                return when (kind) {
                    Kind.UNSEEN -> "your unread messages"
                    Kind.CHATS -> "your chats"
                    Kind.CHAT -> if (count == 1) "a chat" else "$count chats"
                    Kind.THREADS -> if (count == 1) "threads in a chat" else "threads in $count chats"
                    Kind.CALENDAR -> "your calendar"
                    Kind.SEARCH -> null
                }
            }

        /// Every read of it answered.
        val done: Boolean get() = requests.all { it.status == ControlEnvelope.Status.DONE }

        val title: String
            get() = objectText?.let { "Read $it" }
                ?: if (requests.size == 1) "Searched your messages" else "Searched your messages ${requests.size} times"
    }

    val steps: List<Step>

    /// From the first read asked to the last, in seconds.
    val span: Double

    init {
        val built = mutableListOf<Step>()
        for (read in reads) {
            val kind = Kind.of(read.request.kind) ?: continue
            val subject = subject(read.request, kind)
            val step = built.firstOrNull { it.kind == kind }
            if (step != null) {
                step.requests += read.request
                if (subject != null && subject !in step.subjects) step.subjects += subject
            } else {
                built += Step(kind, mutableListOf(read.request), if (subject != null) mutableListOf(subject) else mutableListOf())
            }
        }
        steps = built
        val last = reads.lastOrNull()?.message?.timestamp
        val first = reads.firstOrNull()?.message?.timestamp
        span = if (last != null && first != null) (last - first) / 1000.0 else 0.0
    }

    /// The row's line, short enough for one: "Checked unread messages, 2 chats, threads and a search". Reading the list of chats, which every
    /// look starts with, is left to the steps unless it is all there was.
    val summary: String
        get() {
            val items = steps.mapNotNull { step ->
                val count = step.requests.size
                val chats = maxOf(step.subjects.size, 1)
                when (step.kind) {
                    Kind.UNSEEN -> "unread messages"
                    Kind.CHATS -> if (steps.size == 1) "your chats" else null
                    Kind.CHAT -> if (chats == 1) "a chat" else "$chats chats"
                    Kind.THREADS -> "threads"
                    Kind.SEARCH -> if (count == 1) "a search" else "$count searches"
                    Kind.CALENDAR -> "your calendar"
                }
            }
            val list = if (items.size < 2) items.joinToString("") else items.dropLast(1).joinToString(", ") + " and " + items.last()
            return "Checked $list"
        }

    /// The first read not yet answered, whose outcome the line shows in place of its tick.
    val waiting: AgentRequest? get() = steps.asSequence().flatMap { it.requests.asSequence() }.firstOrNull { it.status == null }

    companion object {
        /// The most time between two reads of one row: one look around the chats asks every few seconds, a scheduled run's next look is hours on.
        const val GAP_SECONDS = 2 * 60

        /// What a read named: the chat it read, a search's words in quotes; null when it named none.
        fun subject(request: AgentRequest, kind: Kind): String? {
            val args = request.args ?: return null
            return when (kind) {
                Kind.CHAT, Kind.THREADS, Kind.UNSEEN -> (args.opt("chat") as? String)?.takeIf { it.isNotEmpty() }
                Kind.SEARCH -> (args.opt("q") as? String)?.takeIf { it.isNotEmpty() }?.let { "“$it”" }
                Kind.CHATS, Kind.CALENDAR -> null
            }
        }
    }
}

/// How the agent's tools read in its chat: "Read the time · 0.1 s" (iOS `AgentToolTitle`).
object AgentToolTitle {
    private val titles = mapOf(
        "current_time" to "Read the time",
        "get_contacts_and_tribes" to "Read contacts and tribes",
        "read_recent_messages" to "Read recent messages",
        "read_unseen_messages" to "Read unseen messages",
        "read_threads" to "Read threads",
        "search_messages" to "Searched messages",
        "send_sphinx_message" to "Send a message",
        "remember" to "Remembered",
        "recall" to "Searched memory",
        "read_memory" to "Read memory",
        "memory_ontology" to "Read memory's vocabulary",
        "forget_memory" to "Forgot",
        "merge_memory" to "Merged",
        "list_automations" to "Read automations",
        "preview_schedule" to "Checked a schedule",
        "create_automation" to "Create an automation",
        "update_automation" to "Change an automation",
        "delete_automation" to "Delete an automation",
        "run_automation_now" to "Run an automation",
        // strut's own job steps, on the orchestrator.
        "job_start" to "Started a job",
        "job_continue" to "Sent a job your answer",
        "job_list" to "Looked for a job",
        "job_get" to "Checked on a job",
        "job_read" to "Read a job's file",
        // Hive workspaces, paired as peers.
        "strut_list-peers" to "Looked at your Stadeum workspaces",
        "strut_run-workflow" to "Asked a Stadeum workspace",
    )

    /// strut's orchestrator's tools, as the bridge names them (`sphinx/unseen` as `sphinx_unseen`), by the tool of Peel's earlier loop that did the
    /// same, so both read alike.
    private val strutNames = mapOf(
        "sphinx_chats" to "get_contacts_and_tribes", "sphinx_read" to "read_recent_messages",
        "sphinx_unseen" to "read_unseen_messages", "sphinx_threads" to "read_threads", "sphinx_search" to "search_messages",
        "sphinx_send" to "send_sphinx_message", "memory_remember" to "remember", "memory_recall" to "recall",
        "memory_get" to "read_memory", "memory_forget" to "forget_memory", "memory_merge" to "merge_memory",
        "automation_list" to "list_automations", "automation_preview" to "preview_schedule",
        "automation_create" to "create_automation", "automation_update" to "update_automation",
        "automation_delete" to "delete_automation", "automation_run-now" to "run_automation_now",
    )

    fun of(rawName: String): String {
        val name = rawName.replace("/", "_")
        titles[strutNames[name] ?: name]?.let { return it }
        val words = name.replace("_", " ")
        return words.take(1).uppercase() + words.drop(1)
    }

    private val chatId = Regex(""" \(chat=[^)]*\)""")
    private val titleField = Regex(""""title":"([^"\\]{1,200})"""")

    /// A result's first line as a row shows it: without chat ids, which the model needs and a person does not, or the colon before the data.
    fun summary(line: String): String = chatId.replace(line, "").trim { it == ':' || it == ' ' }

    /// What a tool in the agent's DM was asked, from the bridge's one line of its input: an object's values ("pr6-peer, hello"), else the line.
    fun asked(line: String): String {
        val fields = runCatching { JSONObject(line) }.getOrNull()
        if (fields == null) {
            // The bridge cuts a long input short: its title if one shows, never half an object.
            if (!line.startsWith("{")) return summary(line)
            return titleField.find(line)?.groupValues?.get(1) ?: ""
        }
        // A job by its title, not the whole prompt it was given.
        (fields.opt("title") as? String)?.let { return it }
        val keys = mutableListOf<String>().also { list -> fields.keys().forEach { list += it } }.sorted()
        return keys.mapNotNull { key ->
            when (val value = fields.opt(key)) {
                is String -> value
                is Boolean -> if (value) "1" else "0"
                is Int, is Long -> value.toString()
                is Double -> if (value == Math.floor(value) && value.isFinite()) value.toLong().toString() else value.toString()
                else -> null
            }
        }.joinToString(", ")
    }
}

/// What a message of the agent's DM or a direct message is drawn as, when it is a control message (iOS `ChatRow.Kind.control`).
sealed interface ControlRow {
    /// A quiet line: who sent it and its text, as the official apps show it ("Kate: …").
    data class Line(val text: String) : ControlRow

    /// A request to this device as one row, how it ended folded in.
    data class Request(val request: AgentRequest) : ControlRow

    /// Reads asked back to back and done, as one row (the first's), [reads] oldest first, this row's own first.
    data class Reads(val reads: AgentReads) : ControlRow
}

/// The rows of a direct message's control messages (iOS `ChatRow.rows` and `foldingReads`): which messages are a quiet line, which are a
/// request's one row, which have no row at all.
class ControlPlan(
    /// By [MessageRow.id]: the row a control message is drawn as in place of a bubble.
    val rows: Map<Long, ControlRow>,
    /// By id: control messages with no row of their own (an ack, a result, a cancel, an older copy of a request, a line with no words, a read folded into
    /// an earlier row).
    val hidden: Set<Long>,
) {
    /// Whether [row] is drawn as a quiet line or request row rather than a bubble.
    fun isControl(row: MessageRow): Boolean = row.id in rows

    companion object {
        val NONE = ControlPlan(emptyMap(), emptySet())

        /// [messages] oldest first as the chat shows them. [inTribe]: in a tribe the device channel never runs. [agentDM]: the agent's chat, where the
        /// agent's cancels and answers are the bridge's own and requests carry no asker. [sender] names who sent a message ("You", the contact's name).
        fun of(messages: List<MessageRow>, inTribe: Boolean, agentDM: Boolean, sender: (MessageRow) -> String): ControlPlan {
            if (inTribe || messages.isEmpty()) return NONE
            val control = HashMap<Long, ControlEnvelope?>()
            val isLine = HashMap<Long, Boolean>()
            for (m in messages) {
                val c = ControlMessages.control(m.type, m.deleted, m.body)
                isLine[m.id] = c != null && !(m.fromMe && m.status == "FAILED")
                control[m.id] = (c as? ControlMessage.Envelope)?.envelope
            }
            // A device request is one row, on its newest copy, with how this Mac answered it from its result that went; a status line is its
            // line; an ack, a result or a cancel has no row of its own.
            val requestRows = HashMap<String, Long>()
            val answered = HashMap<String, ControlEnvelope>()
            for (m in messages) {
                val e = control[m.id] ?: continue
                if (e.op == ControlEnvelope.Op.REQUEST && e.id != null) requestRows[e.id] = m.id
                if (e.op == ControlEnvelope.Op.RESULT && m.fromMe && m.status != "FAILED" && e.re != null && e.status != null) answered[e.re] = e
            }
            val rows = LinkedHashMap<Long, ControlRow>()
            val hidden = HashSet<Long>()
            // The visible sequence, for folding reads: a control row or a message that is not one.
            val sequence = mutableListOf<Pair<MessageRow, ControlRow?>>()
            for (m in messages) {
                if (isLine[m.id] != true) { sequence += m to null; continue }
                val line = ControlMessages.line(m.body, sender(m))
                if (line.isEmpty()) { hidden += m.id; continue }
                val e = control[m.id]
                var request: AgentRequest? = null
                if (e != null && e.op != ControlEnvelope.Op.STATUS && (e.op == ControlEnvelope.Op.REQUEST || m.fromMe || agentDM)) {
                    // Your Mac's ack, result or cancel, and a request's older copies, have no row; anyone else's own line stays (in the agent
                    // chat, the agent's cancels are the bridge's too).
                    val id = e.id
                    if (e.op != ControlEnvelope.Op.REQUEST || id == null || requestRows[id] != m.id) { hidden += m.id; continue }
                    request = AgentRequest.of(e, answered[id])
                    // Outside the agent chat any contact could send one: named, as its line was.
                    if (!agentDM) request = request.copy(asker = sender(m))
                }
                val row = if (request != null) ControlRow.Request(request) else ControlRow.Line(line)
                sequence += m to row
            }
            if (agentDM) foldReads(sequence, rows, hidden) else sequence.forEach { (m, r) -> if (r != null) rows[m.id] = r }
            return ControlPlan(rows, hidden)
        }

        /// This row's request when it is a read done or not yet answered: one that may fold.
        private fun quietRead(row: ControlRow?): AgentRequest? {
            val request = (row as? ControlRow.Request)?.request ?: return null
            if (request.asker != null || !(request.status == ControlEnvelope.Status.DONE || request.status == null)) return null
            return request.takeIf { AgentReads.Kind.of(it.kind) != null }
        }

        private fun sameDay(a: Long?, b: Long?): Boolean {
            if (a == null || b == null) return false
            val ca = Calendar.getInstance().apply { timeInMillis = a }
            val cb = Calendar.getInstance().apply { timeInMillis = b }
            return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
        }

        /// Reads asked back to back, no more than [AgentReads.GAP_SECONDS] apart on one day, as their first row: an agent's look around the chats
        /// is one line, not one per chat. One still on this Mac folds too; a read that failed or was refused stays a row of its own between them.
        private fun foldReads(sequence: List<Pair<MessageRow, ControlRow?>>, rows: MutableMap<Long, ControlRow>, hidden: MutableSet<Long>) {
            // Per folded row: its head message, and its reads so far.
            var head: MessageRow? = null
            var headRequest: AgentRequest? = null
            val reads = mutableListOf<AgentRead>()
            fun flush() {
                val h = head ?: return
                rows[h.id] = if (reads.size > 1) ControlRow.Reads(AgentReads(reads.toList())) else ControlRow.Request(headRequest!!)
                head = null
                reads.clear()
            }
            for ((m, row) in sequence) {
                val read = quietRead(row)
                val h = head
                if (read != null && h != null) {
                    val previous = reads.last().message.timestamp
                    val now = m.timestamp
                    if (previous != null && now != null && (now - previous) <= AgentReads.GAP_SECONDS * 1000L && sameDay(now, previous)) {
                        reads += AgentRead(m, read)
                        hidden += m.id
                        continue
                    }
                }
                flush()
                if (read != null) {
                    head = m
                    headRequest = read
                    reads += AgentRead(m, read)
                } else if (row != null) {
                    rows[m.id] = row
                }
            }
            flush()
        }
    }
}
