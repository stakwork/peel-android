package com.sphinx.peel.diagnostics

/// A crash in plain values (iOS `CrashRecord`/`RedactedRecord`). On Android the source is the uncaught
/// exception handler (`CrashRecorder`) or an `ApplicationExitInfo` for a native crash, not PLCrashReporter:
/// a frame is `imageName` = the class's file or class, `symbol` = the method, `offset` = the source line,
/// `pc` = 0 (the JVM has no addresses). Raw records hold paths and free text, so they live only in the
/// private raw folder.
data class CrashFrame(val pc: Long, val imageName: String?, val symbol: String?, val offset: Long?)
data class CrashRegister(val name: String, val value: Long)
data class CrashThread(val number: Int, val crashed: Boolean, val frames: List<CrashFrame>, val registers: List<CrashRegister>)
data class CrashImage(val name: String, val uuid: String?, val base: Long, val size: Long, val arch: String?)

/// The fields a raw and a redacted record share.
interface CrashFields {
    val id: String
    val appIdentifier: String?
    val appVersion: String?
    val osVersion: String?
    val osBuild: String?
    val machineModel: String?
    /// Milliseconds since 1970.
    val timestamp: Long?
    val signal: String?
    val signalCode: String?
    val faultAddress: Long?
    val machException: String?
    val exceptionName: String?
    val exceptionReason: String?
    val processName: String?
    val processPath: String?
    val threads: List<CrashThread>
    val images: List<CrashImage>
    val events: List<DiagnosticEvent>
    val strutStderrTail: String?
}

data class CrashRecord(
    override val id: String,
    override val appIdentifier: String? = null,
    override val appVersion: String? = null,
    override val osVersion: String? = null,
    override val osBuild: String? = null,
    override val machineModel: String? = null,
    override val timestamp: Long? = null,
    override val signal: String? = null,
    override val signalCode: String? = null,
    override val faultAddress: Long? = null,
    override val machException: String? = null,
    override val exceptionName: String? = null,
    override val exceptionReason: String? = null,
    override val processName: String? = null,
    override val processPath: String? = null,
    override val threads: List<CrashThread> = emptyList(),
    override val images: List<CrashImage> = emptyList(),
    /// The ring as it stood when the report was found (the crashed run's end).
    override val events: List<DiagnosticEvent> = emptyList(),
    override val strutStderrTail: String? = null,
) : CrashFields {
    fun toJson(): String = CrashCodec.encode(this)

    companion object {
        fun fromJson(text: String): CrashRecord? = CrashCodec.decode(text)?.let {
            CrashRecord(
                it.id, it.appIdentifier, it.appVersion, it.osVersion, it.osBuild, it.machineModel, it.timestamp, it.signal,
                it.signalCode, it.faultAddress, it.machException, it.exceptionName, it.exceptionReason, it.processName,
                it.processPath, it.threads, it.images, it.events, it.strutStderrTail,
            )
        }
    }
}

/// A crash record after `Redactor`: what may be shown, copied and shared.
data class RedactedRecord(
    override val id: String,
    override val appIdentifier: String? = null,
    override val appVersion: String? = null,
    override val osVersion: String? = null,
    override val osBuild: String? = null,
    override val machineModel: String? = null,
    override val timestamp: Long? = null,
    override val signal: String? = null,
    override val signalCode: String? = null,
    override val faultAddress: Long? = null,
    override val machException: String? = null,
    override val exceptionName: String? = null,
    override val exceptionReason: String? = null,
    override val processName: String? = null,
    override val processPath: String? = null,
    override val threads: List<CrashThread> = emptyList(),
    override val images: List<CrashImage> = emptyList(),
    override val events: List<DiagnosticEvent> = emptyList(),
    override val strutStderrTail: String? = null,
) : CrashFields {
    fun toJson(): String = CrashCodec.encode(this)

    /// The record cut to what a device answer holds: the exception's words shortened, the crashed
    /// thread's top frames without registers, only the images those frames are in, the last events.
    fun compact(): RedactedRecord {
        val crashed = threads.firstOrNull { it.crashed } ?: threads.firstOrNull()
        val kept = crashed?.let { listOf(CrashThread(it.number, it.crashed, it.frames.take(25), emptyList())) } ?: emptyList()
        val used = kept.flatMap { it.frames }.mapNotNull { it.imageName }.toSet()
        return copy(
            threads = kept, images = images.filter { it.name in used }, events = events.takeLast(15),
            exceptionName = exceptionName?.take(200), exceptionReason = exceptionReason?.take(1_000),
        )
    }

    /// Apple's crash log, roughly (as iOS renders it): header, exception, threads with their frames, the
    /// crashed thread's registers, binary images, then the events leading up to it.
    fun rendered(): String {
        val out = mutableListOf<String>()
        fun hex(value: Long) = "0x" + java.lang.Long.toHexString(value)
        fun line(key: String, value: String?) { if (!value.isNullOrEmpty()) out += "$key: $value" }
        line("Process", listOfNotNull(processName, processPath?.let { "[$it]" }).joinToString(" "))
        line("Identifier", appIdentifier)
        line("Version", appVersion)
        line("Hardware Model", machineModel)
        line("OS Version", listOfNotNull(osVersion, osBuild?.let { "($it)" }).joinToString(" "))
        line("Date/Time", timestamp?.let { DiagnosticEvent.isoTime(it) })
        line("Report ID", id)
        out += ""
        line("Exception Type", machException)
        line("Signal", listOfNotNull(signal, signalCode).joinToString(" "))
        line("Fault Address", faultAddress?.let(::hex))
        line("Exception Name", exceptionName)
        line("Exception Reason", exceptionReason)
        for (thread in threads) {
            out += ""
            out += "Thread ${thread.number}${if (thread.crashed) " Crashed" else ""}:"
            thread.frames.forEachIndexed { index, frame ->
                var text = "$index  ${frame.imageName ?: "???"}  ${hex(frame.pc)}"
                frame.symbol?.let { text += " $it" + (frame.offset?.let { off -> " + $off" } ?: "") }
                out += text
            }
            if (thread.crashed && thread.registers.isNotEmpty()) {
                out += ""
                out += "Thread ${thread.number} crashed with registers:"
                out += thread.registers.joinToString("  ") { "${it.name}: ${hex(it.value)}" }
            }
        }
        if (images.isNotEmpty()) {
            out += ""
            out += "Binary Images:"
            for (image in images) {
                out += "${hex(image.base)} - ${hex(image.base + maxOf(image.size, 1) - 1)} ${image.name} " +
                    "${image.arch ?: ""} <${image.uuid ?: "no uuid"}>"
            }
        }
        if (events.isNotEmpty()) {
            out += ""
            out += "Recent events (newest last):"
            for (event in events.takeLast(40)) out += event.line
        }
        strutStderrTail?.takeIf { it.isNotEmpty() }?.let {
            out += ""
            out += "Strut's last stderr (redacted):"
            out += it
        }
        return out.joinToString("\n") + "\n"
    }

    companion object {
        fun fromJson(text: String): RedactedRecord? = CrashCodec.decode(text)?.let {
            RedactedRecord(
                it.id, it.appIdentifier, it.appVersion, it.osVersion, it.osBuild, it.machineModel, it.timestamp, it.signal,
                it.signalCode, it.faultAddress, it.machException, it.exceptionName, it.exceptionReason, it.processName,
                it.processPath, it.threads, it.images, it.events, it.strutStderrTail,
            )
        }
    }
}

internal object CrashCodec {
    fun encode(r: CrashFields): String = MiniJson.write(
        mapOf(
            "id" to r.id, "appIdentifier" to r.appIdentifier, "appVersion" to r.appVersion, "osVersion" to r.osVersion,
            "osBuild" to r.osBuild, "machineModel" to r.machineModel, "timestamp" to r.timestamp?.let { it / 1000.0 },
            "signal" to r.signal, "signalCode" to r.signalCode, "faultAddress" to r.faultAddress,
            "machException" to r.machException, "exceptionName" to r.exceptionName, "exceptionReason" to r.exceptionReason,
            "processName" to r.processName, "processPath" to r.processPath,
            "threads" to r.threads.map { t ->
                mapOf(
                    "number" to t.number, "crashed" to t.crashed,
                    "frames" to t.frames.map { mapOf("pc" to it.pc, "imageName" to it.imageName, "symbol" to it.symbol, "offset" to it.offset) },
                    "registers" to t.registers.map { mapOf("name" to it.name, "value" to it.value) },
                )
            },
            "images" to r.images.map { mapOf("name" to it.name, "uuid" to it.uuid, "base" to it.base, "size" to it.size, "arch" to it.arch) },
            "events" to r.events.map { it.toMap() },
            "strutStderrTail" to r.strutStderrTail,
        ),
    )

    @Suppress("UNCHECKED_CAST")
    private fun maps(value: Any?): List<Map<String, Any?>> = (value as? List<*>)?.mapNotNull { it as? Map<String, Any?> } ?: emptyList()

    fun decode(text: String): CrashRecord? {
        val m = MiniJson.parseObject(text) ?: return null
        val id = m.str("id") ?: return null
        return CrashRecord(
            id = id, appIdentifier = m.str("appIdentifier"), appVersion = m.str("appVersion"), osVersion = m.str("osVersion"),
            osBuild = m.str("osBuild"), machineModel = m.str("machineModel"),
            timestamp = m.dbl("timestamp")?.let { Math.round(it * 1000) },
            signal = m.str("signal"), signalCode = m.str("signalCode"), faultAddress = m.long("faultAddress"),
            machException = m.str("machException"), exceptionName = m.str("exceptionName"),
            exceptionReason = m.str("exceptionReason"), processName = m.str("processName"), processPath = m.str("processPath"),
            threads = maps(m["threads"]).map { t ->
                CrashThread(
                    t.int("number") ?: 0, t.bool("crashed") ?: false,
                    maps(t["frames"]).map { CrashFrame(it.long("pc") ?: 0, it.str("imageName"), it.str("symbol"), it.long("offset")) },
                    maps(t["registers"]).map { CrashRegister(it.str("name") ?: "?", it.long("value") ?: 0) },
                )
            },
            images = maps(m["images"]).map { CrashImage(it.str("name") ?: "", it.str("uuid"), it.long("base") ?: 0, it.long("size") ?: 0, it.str("arch")) },
            events = (m["events"] as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.let { e -> DiagnosticEvent.fromJson(MiniJson.write(e)) } } ?: emptyList(),
            strutStderrTail = m.str("strutStderrTail"),
        )
    }
}

/// What `Diagnostics.snapshot()` returns: no free text, only enums and numbers.
data class DiagnosticsReport(
    val previousRun: DiagnosticEvent.PreviousRunKind,
    val hasReport: Boolean,
    val appVersion: String?,
    val footprintBytes: Long?,
    val events: List<DiagnosticEvent>,
) {
    /// What the ring holds, as text: for a run that was killed and left no crash report.
    fun rendered(limit: Int = 60): String {
        val out = mutableListOf("Previous run: ${previousRun.name}")
        appVersion?.let { out += "Version: $it" }
        out += ""
        out += "Recent events (newest last):"
        out += events.takeLast(maxOf(limit, 0)).map { it.line }
        return out.joinToString("\n") + "\n"
    }
}
