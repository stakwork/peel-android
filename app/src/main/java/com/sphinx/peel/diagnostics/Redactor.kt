package com.sphinx.peel.diagnostics

/// Scrubs a crash record (iOS `Redactor`): structure stays, every free-text field is run through
/// `redactText`. Best effort by design (docs/diagnostics.md on iOS): the rules catch what Peel knows to
/// look for.
///
/// - `allow`: strings that must survive verbatim (the tribe's name and public key). Swapped for
///   placeholders before the rules run and put back after.
/// - `knownNames`: contact names and aliases, removed case-insensitively. Built from the contact list
///   when redacting, never stored.
class Redactor(allow: List<String> = emptyList(), knownNames: List<String> = emptyList()) {
    val allow: List<String> = allow.filter { it.isNotEmpty() }
    val knownNames: List<String> = knownNames.map { it.trim() }.filter { it.codePointCount(0, it.length) >= 2 }

    fun redact(record: CrashRecord): RedactedRecord = RedactedRecord(
        id = record.id,
        appIdentifier = identifier(record.appIdentifier),
        appVersion = identifier(record.appVersion),
        osVersion = identifier(record.osVersion),
        osBuild = identifier(record.osBuild),
        machineModel = identifier(record.machineModel),
        timestamp = record.timestamp,
        signal = identifier(record.signal),
        signalCode = identifier(record.signalCode),
        faultAddress = record.faultAddress,
        machException = identifier(record.machException),
        exceptionName = record.exceptionName?.let(::redactText),
        exceptionReason = record.exceptionReason?.let(::redactText),
        processName = record.processName?.let(::redactPath),
        processPath = record.processPath?.let(::redactPath),
        threads = record.threads.map { thread ->
            CrashThread(
                thread.number, thread.crashed,
                thread.frames.map { CrashFrame(it.pc, it.imageName?.let(::redactPath), it.symbol?.let(::redactSymbol), it.offset) },
                thread.registers.map { CrashRegister(identifier(it.name) ?: "?", it.value) },
            )
        },
        images = record.images.map { CrashImage(redactPath(it.name), identifier(it.uuid), it.base, it.size, identifier(it.arch)) },
        events = record.events,
        strutStderrTail = record.strutStderrTail?.let(::redactText),
    )

    /// A free-text field: paths, money, keys, device names, contact names, quoted text and labelled
    /// text all go; the allowed strings stay.
    fun redactText(input: String): String {
        val (swapped, swaps) = swapAllowed(input)
        var text = swapped
        fun apply(rules: List<Rule>) { for (rule in rules) text = rule.apply(text, swaps) }
        apply(quoted + labelled)
        text = redactPath(text)
        apply(money + device + keys)
        text = removeNames(text)
        for ((token, original) in swaps) text = text.replace(token, original)
        return text
    }

    /// A code symbol: only a home path in it goes, so class and method names read as they are.
    fun redactSymbol(symbol: String): String = redactPath(symbol).take(512)

    /// A path: the home folder and the app container's id become `~`.
    fun redactPath(input: String): String {
        var text = input
        for (rule in paths) text = rule.apply(text, emptyList())
        return text
    }

    /// Fields that are identifiers by nature (versions, model, signal name, uuid, register name): kept
    /// only in that alphabet and length.
    private fun identifier(value: String?): String? {
        if (value == null) return null
        val ok = value.codePointCount(0, value.length) <= 64 && value.all { it.isLetterOrDigit() || it in ".,_-+" }
        return if (ok) value else "[removed]"
    }

    private fun swapAllowed(input: String): Pair<String, List<Pair<String, String>>> {
        var text = input
        val swaps = mutableListOf<Pair<String, String>>()
        for ((index, item) in allow.sortedByDescending { it.length }.withIndex()) {
            val token = "\u27E6P$index\u27E7"
            val expression = Regex(Regex.escape(item), RegexOption.IGNORE_CASE)
            if (!expression.containsMatchIn(text)) continue
            // Put back as written in the allowed list, whatever the text's case.
            text = expression.replace(text, Regex.escapeReplacement(token))
            swaps += token to item
        }
        return text to swaps
    }

    private fun removeNames(input: String): String {
        var text = input
        for (name in knownNames.sortedByDescending { it.length }) {
            val expression = Regex("(?<![\\p{L}\\p{N}])${Regex.escape(name)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
            text = expression.replace(text, "[contact]")
        }
        return text
    }

    internal class Rule(pattern: String, private val replacement: String, ignoreCase: Boolean = false, private val keepsAllowed: Boolean = false) {
        private val expression = if (ignoreCase) Regex(pattern, RegexOption.IGNORE_CASE) else Regex(pattern)

        fun apply(text: String, swaps: List<Pair<String, String>>): String {
            val out = StringBuilder()
            var last = 0
            for (match in expression.findAll(text)) {
                out.append(text, last, match.range.first)
                val matched = match.value
                if (keepsAllowed && swaps.any { matched.contains(it.first) } && onlyAllowed(matched, swaps)) {
                    out.append(matched)
                } else {
                    out.append(expand(match))
                }
                last = match.range.last + 1
            }
            out.append(text, last, text.length)
            return out.toString()
        }

        private fun expand(match: MatchResult): String {
            val out = StringBuilder()
            var i = 0
            while (i < replacement.length) {
                val c = replacement[i]
                if (c == '$' && i + 1 < replacement.length && replacement[i + 1].isDigit()) {
                    out.append(match.groupValues.getOrElse(replacement[i + 1] - '0') { "" })
                    i += 2
                } else {
                    out.append(c)
                    i++
                }
            }
            return out.toString()
        }

        /// Whether `text` is just placeholders, quotes, a label and spacing.
        private fun onlyAllowed(text: String, swaps: List<Pair<String, String>>): Boolean {
            var rest = text
            for ((token, _) in swaps) rest = rest.replace(token, "")
            // A label before a colon is not text.
            val colon = rest.indexOf(':')
            if (colon >= 0) rest = rest.substring(colon + 1)
            return rest.none { it.isLetterOrDigit() }
        }
    }

    private val quoted = listOf(
        Rule("\"[^\"\\n]{0,400}\"", "[text]", keepsAllowed = true),
        Rule("\u201C[^\u201D\\n]{0,400}\u201D", "[text]", keepsAllowed = true),
        Rule("(?<![\\w])'[^'\\n]{1,200}'(?![\\w])", "[text]", keepsAllowed = true),
    )

    private val labelled = listOf(
        Rule("(?i)\\b(message|text|name|alias|content|body)\\s*[:=][^\\n]*", "$1: [text]", keepsAllowed = true),
    )

    // The two container forms are iOS's; the rest are Android's own homes (app data, shared storage).
    private val paths = listOf(
        Rule("(?:/private)?/var/mobile/Containers/[^/\\s]+/[^/\\s]+/[0-9A-Fa-f]{8}-(?:[0-9A-Fa-f]{4}-){3}[0-9A-Fa-f]{12}", "~"),
        Rule("(?:/private)?/var/containers/[^/\\s]+/[^/\\s]+/[0-9A-Fa-f]{8}-(?:[0-9A-Fa-f]{4}-){3}[0-9A-Fa-f]{12}", "~"),
        Rule("(?:/private)?/var/mobile(?=/|\\s|$)", "~"),
        Rule("/Users/[^/\\s\"']+", "~"),
        Rule("/home/[^/\\s\"']+", "~"),
        Rule("/data/(?:user|user_de)/\\d+/[^/\\s\"']+", "~"),
        Rule("/data/data/[^/\\s\"']+", "~"),
        Rule("/storage/emulated/\\d+", "~"),
        Rule("/sdcard(?=/|\\s|$)", "~"),
    )

    private val money = listOf(
        Rule("\\bln(?:bc|tbs|tb|bcrt|sb)[0-9a-z]{10,}", "[amount]", ignoreCase = true),
        Rule("\\b\\d[\\d,._]*\\s?(?:msats?|sats?|satoshis?|btc|usd|eur|gbp)\\b", "[amount]", ignoreCase = true),
        Rule("[$\u20AC\u00A3\u20BF]\\s?\\d[\\d,._]*", "[amount]"),
    )

    private val keys = listOf(
        // 12 or more lowercase words in a row: a recovery phrase, or prose.
        Rule("\\b(?:[a-z]{3,8}\\s+){11,}[a-z]{3,8}\\b", "[key]"),
        Rule("\\b[xyz]prv[1-9A-HJ-NP-Za-km-z]{20,}", "[key]"),
        Rule("\\bsk[-_][A-Za-z0-9_-]{16,}", "[key]"),
        Rule("\\b(?:bc1|tb1|bcrt1|lnurl1|npub1|nsec1)[a-z0-9]{20,}", "[key]", ignoreCase = true),
        Rule("(?<![\\w$])[0-9a-fA-F]{32,}(?![\\w])", "[key]"),
        // Base64 and its URL form, with a digit in it: not a word, not a path.
        Rule("(?<![\\w$+-])(?=[A-Za-z0-9+_-]*\\d)[A-Za-z0-9+_-]{32,}={0,2}", "[key]"),
    )

    private val device = listOf(
        Rule("\\bhttps?://\\S+", "[url]", ignoreCase = true),
        Rule("\\b[0-9A-Fa-f]{8}-(?:[0-9A-Fa-f]{4}-){3}[0-9A-Fa-f]{12}\\b", "[device]"),
        // iOS also lists `.internal`; here it is dropped, since Java/Kotlin names (`kotlinx.coroutines.internal`) have it.
        Rule("\\b[\\w-]+\\.(?:local|lan|home)\\b", "[device]", ignoreCase = true),
        Rule("\\b[\\w]+-(?:MacBook|iPhone|iPad|iMac|Mac-mini|Mac-Studio|Mac-Pro|Pixel|Galaxy|SM)[\\w-]*", "[device]", ignoreCase = true),
        Rule("\\b(serial(?:\\s*number)?|udid|imei|android_id)\\b\\s*[:=]?\\s*[A-Za-z0-9-]{8,}", "$1 [device]", ignoreCase = true),
    )
}

/// `Diagnostics.redact`: makes a failure's text safe to keep in the ring. Emails, URL paths and
/// queries, invoices, hex runs of 16+ (keys, hashes, tags), base64/base58/bech32-like tokens of 24+
/// characters are replaced by `<redacted>`, whitespace is collapsed and the text capped at
/// `DETAIL_LIMIT` characters.
object DetailRedaction {
    const val DETAIL_LIMIT = 200

    private val emailRule = Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")
    private val urlRule = Regex("([A-Za-z][A-Za-z0-9+.\\-]*://[^\\s/?#]+)[/?#]\\S*")
    private val invoiceRule = Regex("\\b(?:lnbcrt|lntbs|lnbc|lntb)[0-9a-z]{10,}", RegexOption.IGNORE_CASE)
    private val hexRule = Regex("\\b[0-9a-fA-F]{16,}\\b")
    private val tokenRule = Regex("[A-Za-z0-9+/=_\\-]{24,}")

    fun redact(text: String): String {
        var out = text
        out = emailRule.replace(out, "<redacted>")
        out = urlRule.replace(out) { it.groupValues[1] + "<redacted>" }
        out = invoiceRule.replace(out, "<redacted>")
        out = hexRule.replace(out, "<redacted>")
        out = tokenRule.replace(out, "<redacted>")
        out = out.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        return out.take(DETAIL_LIMIT)
    }
}
