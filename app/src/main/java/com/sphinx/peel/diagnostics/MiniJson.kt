package com.sphinx.peel.diagnostics

/// A small JSON reader/writer for the diagnostics files (ring lines, run marker, crash records).
/// Not `org.json`: that is a stub on a plain JVM, and these files must be testable there. Values are
/// `Map<String, Any?>` (sorted on write, like the iOS encoder's `.sortedKeys`), `List<Any?>`, `String`,
/// `Boolean`, `Long`, `Double`, null.
internal object MiniJson {
    fun write(value: Any?): String = StringBuilder().also { write(value, it) }.toString()

    private fun write(value: Any?, out: StringBuilder) {
        when (value) {
            null -> out.append("null")
            is Boolean -> out.append(value)
            is Int, is Long -> out.append(value.toString())
            is Double -> out.append(if (value == Math.rint(value) && Math.abs(value) < 1e15) value.toLong().toString() else value.toString())
            is String -> quote(value, out)
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for (key in value.keys.map { it as String }.sorted()) {
                    if (value[key] == null) continue
                    if (!first) out.append(',')
                    first = false
                    quote(key, out)
                    out.append(':')
                    write(value[key], out)
                }
                out.append('}')
            }
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { i, item -> if (i > 0) out.append(','); write(item, out) }
                out.append(']')
            }
            else -> quote(value.toString(), out)
        }
    }

    private fun quote(text: String, out: StringBuilder) {
        out.append('"')
        for (c in text) when {
            c == '"' -> out.append("\\\"")
            c == '\\' -> out.append("\\\\")
            c == '\n' -> out.append("\\n")
            c == '\r' -> out.append("\\r")
            c == '\t' -> out.append("\\t")
            c < ' ' -> out.append("\\u%04x".format(c.code))
            else -> out.append(c)
        }
        out.append('"')
    }

    /// Null for anything that is not one well-formed JSON value.
    fun parse(text: String): Any? = try {
        val reader = Reader(text)
        val value = reader.value()
        reader.skip()
        if (reader.atEnd()) value else null
    } catch (e: Exception) {
        null
    }

    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): Map<String, Any?>? = parse(text) as? Map<String, Any?>

    private class Reader(val s: String) {
        var i = 0
        fun atEnd() = i >= s.length
        fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            skip()
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> list()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw IllegalArgumentException()
            }
        }
        fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw IllegalArgumentException()
            i += word.length
            return v
        }
        fun num(): Any {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            val t = s.substring(start, i)
            return t.toLongOrNull() ?: t.toDouble()
        }
        fun str(): String {
            i++
            val b = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> when (val e = s[i++]) {
                        'n' -> b.append('\n'); 'r' -> b.append('\r'); 't' -> b.append('\t')
                        'b' -> b.append('\b'); 'f' -> b.append('\u000c')
                        'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> b.append(e)
                    }
                    else -> b.append(c)
                }
            }
        }
        fun obj(): Map<String, Any?> {
            i++
            val m = LinkedHashMap<String, Any?>()
            skip()
            if (s[i] == '}') { i++; return m }
            while (true) {
                skip()
                val k = str()
                skip()
                if (s[i++] != ':') throw IllegalArgumentException()
                m[k] = value()
                skip()
                when (s[i++]) { ',' -> continue; '}' -> return m; else -> throw IllegalArgumentException() }
            }
        }
        fun list(): List<Any?> {
            i++
            val l = ArrayList<Any?>()
            skip()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l.add(value())
                skip()
                when (s[i++]) { ',' -> continue; ']' -> return l; else -> throw IllegalArgumentException() }
            }
        }
    }
}

internal fun Map<String, Any?>.str(key: String): String? = this[key] as? String
internal fun Map<String, Any?>.long(key: String): Long? = (this[key] as? Number)?.toLong()
internal fun Map<String, Any?>.int(key: String): Int? = (this[key] as? Number)?.toInt()
internal fun Map<String, Any?>.dbl(key: String): Double? = (this[key] as? Number)?.toDouble()
internal fun Map<String, Any?>.bool(key: String): Boolean? = this[key] as? Boolean
internal inline fun <reified E : Enum<E>> Map<String, Any?>.enum(key: String): E? =
    (this[key] as? String)?.let { name -> enumValues<E>().firstOrNull { it.name == name } }
