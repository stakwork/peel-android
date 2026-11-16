package com.sphinx.peel.diagnostics

import java.io.File
import java.nio.file.Files

fun tempDirectory(): File = Files.createTempDirectory("peel-diag").toFile().also { it.deleteOnExit() }

/// The same cast as iOS's `Fixtures` (RedactorTests.swift), with Android paths added.
object Fixtures {
    const val tribeName = "Bitcoin Builders"
    val tribeKey = "02" + "ab12cd34".repeat(8) // 66 hex
    const val seed = "abandon ability able about above absent absorb abstract absurd abuse access accident"
    val hexKey = "9f".repeat(32)
    const val vendorID = "3F2504E0-4F89-11D3-9A0C-0305E82C3301"
    const val invoice = "lnbc2500u1pjq8k2hpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4jsxqzpu"
    const val uuid = "A1B2C3D4-0000-4000-8000-0123456789AB"

    val reason = """
        Could not join $tribeName ($tribeKey) for Alice Nakamoto: paid 12,500 sats, invoice $invoice. message: hey are you coming to dinner tonight
        seed $seed key $hexKey
        file /Users/alice/Library/Application Support/Peel/db.sqlite and /var/mobile/Containers/Data/Application/$uuid/Library/x.db and /data/user/0/com.sphinx.peel/files/x.db
        vendor $vendorID host alices-macbook-pro.local
    """.trimIndent()

    val record = CrashRecord(
        id = "rec-1", appIdentifier = "com.sphinx.peel", appVersion = "1.2.3", osVersion = "26.0", osBuild = "25A1",
        machineModel = "iPhone17,1", timestamp = 1_700_000_000_000, signal = "SIGABRT", signalCode = "0",
        faultAddress = 0xdeadbeefL, machException = "EXC_CRASH", exceptionName = "NSInvalidArgumentException", exceptionReason = reason,
        processName = "Peel", processPath = "/Users/alice/Library/Developer/Xcode/DerivedData/Peel-abc/Build/Peel.app/Contents/MacOS/Peel",
        threads = listOf(
            CrashThread(
                0, true,
                listOf(
                    CrashFrame(0x1_0000_1234, "/Users/alice/Build/Peel.app/Contents/MacOS/Peel", "\$s4Peel8AppModelC8joinTribeyyYaF", 52),
                    CrashFrame(0x1_8000_0010, "/usr/lib/system/libsystem_kernel.dylib", "Peel.AppModel.send", 8),
                    CrashFrame(0x1_8000_0020, null, null, null),
                ),
                listOf(CrashRegister("x0", 0x1234), CrashRegister("pc", 0x1_0000_1234)),
            ),
        ),
        images = listOf(CrashImage("/Users/alice/Build/Peel.app/Contents/MacOS/Peel", "C0FFEE00-1111-2222-3333-444455556666", 0x1_0000_0000, 0x40000, "arm64")),
        events = listOf(DiagnosticEvent(DiagnosticEvent.Kind.error, timestamp = 1_699_999_000_000, errorKind = DiagnosticEvent.ErrorKind.network, lastAction = DiagnosticEvent.Action.tribeJoin)),
        strutStderrTail = "strut failed for Alice Nakamoto in /Users/alice/strut",
    )
}
