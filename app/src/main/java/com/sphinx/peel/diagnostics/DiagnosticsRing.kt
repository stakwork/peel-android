package com.sphinx.peel.diagnostics

import java.io.File
import java.io.RandomAccessFile

/// The event ring (iOS `DiagnosticsRing`): a bounded file of `DiagnosticEvent`s, one JSON object per
/// line, oldest first. Appends go to the end of the file; identical consecutive events are counted
/// instead of repeated; over the cap the file is rewritten atomically with the newest half. A line that
/// does not decode (a write cut short, damage) is skipped, never fatal.
class DiagnosticsRing(
    private val file: File,
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
    private val maxEvents: Int = DEFAULT_MAX_EVENTS,
) {
    private val lock = Any()
    private var events: MutableList<DiagnosticEvent>
    private var bytes: Int

    init {
        val data = runCatching { file.readBytes() }.getOrDefault(ByteArray(0))
        events = decode(data).toMutableList()
        bytes = data.size
    }

    /// Every event in the file, oldest first.
    fun read(): List<DiagnosticEvent> = synchronized(lock) { events.toList() }

    fun append(event: DiagnosticEvent) = synchronized(lock) {
        val last = events.lastOrNull()
        if (last != null && last.repeats(event)) {
            events[events.size - 1] = last.copy(count = last.count + event.count, timestamp = event.timestamp)
            rewrite()
            return
        }
        events.add(event)
        val line = line(event)
        if (bytes + line.size > maxBytes || events.size > maxEvents) {
            events = events.subList(events.size / 2, events.size).toMutableList()
            rewrite()
        } else if (!write(line)) {
            rewrite()
        }
    }

    /// Empties the ring.
    fun clear() = synchronized(lock) {
        events = mutableListOf()
        rewrite()
    }

    private fun write(line: ByteArray): Boolean = try {
        if (!file.exists()) {
            false
        } else {
            RandomAccessFile(file, "rw").use { handle ->
                val end = handle.length()
                // A damaged tail may end without a newline: start a fresh line.
                if (end > 0) {
                    handle.seek(end - 1)
                    if (handle.read() != '\n'.code) {
                        handle.seek(end)
                        handle.write('\n'.code)
                        bytes += 1
                    }
                }
                handle.seek(handle.length())
                handle.write(line)
            }
            bytes += line.size
            true
        }
    } catch (e: Exception) {
        false
    }

    private fun rewrite() {
        val out = java.io.ByteArrayOutputStream()
        for (event in events) out.write(line(event))
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeBytes(out.toByteArray())
            if (!temp.renameTo(file)) { file.writeBytes(out.toByteArray()); temp.delete() }
        }
        bytes = out.size()
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 256 * 1024
        const val DEFAULT_MAX_EVENTS = 2000

        fun line(event: DiagnosticEvent): ByteArray = (event.toJson() + "\n").toByteArray(Charsets.UTF_8)

        fun decode(data: ByteArray): List<DiagnosticEvent> =
            String(data, Charsets.UTF_8).split('\n').filter { it.isNotEmpty() }.mapNotNull { DiagnosticEvent.fromJson(it) }
    }
}
