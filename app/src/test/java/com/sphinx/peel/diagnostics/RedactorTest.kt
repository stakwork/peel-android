package com.sphinx.peel.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/// iOS RedactorTests.
class RedactorTest {
    private val redactor = Redactor(allow = listOf(Fixtures.tribeName, Fixtures.tribeKey), knownNames = listOf("Alice Nakamoto"))

    private fun everything(r: RedactedRecord) = r.rendered() + r.toJson()

    @Test fun tribeNameAndKeySurviveVerbatim() {
        val redacted = redactor.redact(Fixtures.record)
        val reason = redacted.exceptionReason!!
        assertTrue(reason.contains(Fixtures.tribeName))
        assertTrue(reason.contains(Fixtures.tribeKey))
        assertTrue(everything(redacted).contains(Fixtures.tribeKey))
    }

    @Test fun nothingPrivateAppearsAnywhere() {
        val text = everything(redactor.redact(Fixtures.record))
        for (leak in listOf(
            "Alice", "Nakamoto", "alice", "12,500", "lnbc2500", "dinner", "coming to", "abandon ability", "absent absorb",
            Fixtures.hexKey, Fixtures.vendorID, Fixtures.uuid, "alices-macbook", ".local", "/Users/", "/var/mobile/Containers", "/data/user/",
        )) assertFalse("leaked $leak", text.contains(leak, ignoreCase = true))
        for (mark in listOf("[amount]", "[key]", "[device]", "[text]", "[contact]")) assertTrue(mark, text.contains(mark))
    }

    @Test fun pathsKeepTheirTail() {
        assertEquals("~/Library/Application Support/Peel/db.sqlite", redactor.redactText("/Users/alice/Library/Application Support/Peel/db.sqlite"))
        assertEquals("~/Library/x.db", redactor.redactText("/var/mobile/Containers/Data/Application/${Fixtures.uuid}/Library/x.db"))
        assertEquals("~/files/x.db", redactor.redactText("/data/user/0/com.sphinx.peel/files/x.db"))
        assertEquals("~/Download/a.txt", redactor.redactText("/storage/emulated/0/Download/a.txt"))
    }

    @Test fun stacksImagesAndAddressesSurviveExactly() {
        val redacted = redactor.redact(Fixtures.record)
        val original = Fixtures.record
        val thread = redacted.threads[0]
        assertEquals(original.threads[0].frames.map { it.pc }, thread.frames.map { it.pc })
        assertEquals(original.threads[0].frames.map { it.offset }, thread.frames.map { it.offset })
        assertEquals("\$s4Peel8AppModelC8joinTribeyyYaF", thread.frames[0].symbol)
        assertEquals("Peel.AppModel.send", thread.frames[1].symbol)
        assertEquals("~/Build/Peel.app/Contents/MacOS/Peel", thread.frames[0].imageName)
        assertEquals("/usr/lib/system/libsystem_kernel.dylib", thread.frames[1].imageName)
        assertEquals(original.threads[0].registers, thread.registers)
        assertEquals("C0FFEE00-1111-2222-3333-444455556666", redacted.images[0].uuid)
        assertEquals(0x1_0000_0000, redacted.images[0].base)
        assertEquals(0x40000L, redacted.images[0].size)
        assertEquals(0xdeadbeefL, redacted.faultAddress)
        assertEquals(original.events, redacted.events)
        assertTrue(redacted.rendered().contains("0x100001234"))
        assertTrue(redacted.rendered().contains("C0FFEE00-1111-2222-3333-444455556666"))
        assertEquals("1.2.3", redacted.appVersion)
        assertEquals("iPhone17,1", redacted.machineModel)
    }

    @Test fun aContactsKeyIsRedactedWhenNotAllowed() {
        val other = "03" + "cd56ef78".repeat(8)
        val out = redactor.redactText("member $other and ${Fixtures.tribeKey}")
        assertFalse(out.contains(other))
        assertTrue(out.contains(Fixtures.tribeKey))
    }

    @Test fun allowedNameSurvivesInsideQuotesAndLabels() {
        assertEquals("joined \"${Fixtures.tribeName}\"", redactor.redactText("joined \"${Fixtures.tribeName}\""))
        assertEquals("name: ${Fixtures.tribeName}", redactor.redactText("name: ${Fixtures.tribeName}"))
        assertEquals("name: [text]", redactor.redactText("name: Someone Else"))
        assertEquals("said [text]", redactor.redactText("said \"hey there\""))
    }

    @Test fun moneyForms() {
        for (sample in listOf("paid 12,500 sats", "3 msat", "0.5 BTC", "20 USD", "costs \$4.99", "1 sat")) {
            val out = redactor.redactText(sample)
            assertTrue(sample, out.contains("[amount]"))
            assertTrue("$sample -> $out", out.none { it.isDigit() })
        }
    }

    @Test fun keyForms() {
        val samples = listOf(
            "xprv9s21ZrQH143K3QTDL4LXw2F7HEK3wJUD2nW2nRk4stbPy6cq3jPPqjiChkVvvNKmPGJxWUtg6LnF5kejMRNNU3TGtRBeJgk33yuGBxrMPHi",
            "sk-ant-api03-abcdefghijklmnopqrstuvwxyz0123456789", "A1b2".repeat(12),
            "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq", "npub1" + "q".repeat(58),
        )
        for (sample in samples) assertEquals(sample, "k [key] end", redactor.redactText("k $sample end"))
    }

    @Test fun codeLikeTextIsNotMistakenForKeys() {
        val text = "Peel.AppModel.joinTribe in \$s4Peel8AppModelC8joinTribe10agreedJoinMsat9confirmedMsatAA14PaymentOutcomeOSSs6UInt64VAHSgtYaF"
        assertEquals(text, redactor.redactText(text))
        val path = "/Applications/Peel.app/Contents/Frameworks/LiveKitWebRTC.framework/LiveKitWebRTC"
        assertEquals(path, redactor.redactText(path))
    }

    @Test fun javaFramesAreNotMistakenForKeys() {
        val text = "com.sphinx.peel.engine.Engine.handleInbound(Engine.kt:571) kotlinx.coroutines.internal.DispatchedContinuation.resumeWith"
        assertEquals(text, redactor.redactText(text))
    }

    @Test fun namesAreMatchedWholeAndWithoutCase() {
        val names = Redactor(knownNames = listOf("Al", "bob"))
        assertEquals("[contact] said hi, Alan too", names.redactText("BOB said hi, Alan too"))
    }

    @Test fun untrustedFieldsAreKeptOnlyAsIdentifiers() {
        val record = Fixtures.record.copy(osVersion = "26.0 for Alice Nakamoto at home")
        assertEquals("[removed]", Redactor().redact(record).osVersion)
    }

    /// A real crash log is far over a device answer's 32 KB; the compact record keeps the crashed thread.
    @Test fun theCompactRecordFitsADeviceAnswer() {
        var record = redactor.redact(Fixtures.record)
        val crashed = record.threads[0]
        val frame = crashed.frames[0]
        fun thread(n: Int, crashedFlag: Boolean, regs: List<CrashRegister> = emptyList()) = CrashThread(n, crashedFlag, List(200) { frame }, regs)
        record = record.copy(
            threads = listOf(thread(0, false), thread(1, true, crashed.registers)) + (2 until 60).map { thread(it, false) },
            images = record.images + (0 until 800).map { CrashImage("/usr/lib/lib$it.dylib", Fixtures.uuid, 0, 1, "arm64") },
            events = List(2000) { record.events[0] },
            exceptionReason = "x".repeat(40_000),
        )
        assertTrue(record.rendered().toByteArray().size > 32 * 1024)
        val text = record.compact().rendered()
        assertTrue(text.toByteArray().size < 16 * 1024)
        assertTrue(text.contains("Thread 1 Crashed:") && !text.contains("Thread 0:"))
        assertFalse("only the images the frames are in", text.contains("<${Fixtures.uuid}>"))
        assertTrue(text.contains("Exception Type: EXC_CRASH"))
    }

    @Test fun recordsRoundTripThroughJson() {
        val redacted = redactor.redact(Fixtures.record)
        assertEquals(redacted, RedactedRecord.fromJson(redacted.toJson()))
        assertEquals(Fixtures.record, CrashRecord.fromJson(Fixtures.record.toJson()))
    }
}
