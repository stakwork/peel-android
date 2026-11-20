package com.sphinx.peel.update

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpdateCheckerTest {
    private class Store(override var lastCheckMillis: Long = 0, override var dismissedCode: Int = 0) : UpdateStore

    private val body = """{"tag_name":"v0.1.18","name":"n","body":"b","assets":[{"name":"peel-0.1.18.apk","browser_download_url":"https://x/y.apk","size":10}]}"""
    private var reply: () -> HttpReply = { HttpReply(200, emptyMap(), body) }
    private val requests = mutableListOf<Pair<String, Map<String, String>>>()
    private val now = 10_000_000_000L

    private fun checker(store: Store, installed: Int = 117) =
        UpdateChecker({ url, h -> requests += url to h; reply() }, store, installed, "0.1.17", clock = { now })

    @Test fun offersANewerRelease() {
        val store = Store()
        assertTrue(checker(store).check(manual = false) is CheckResult.Available)
        assertEquals(now, store.lastCheckMillis)
        assertEquals("https://api.github.com/repos/stakwork/peel-android/releases/latest", requests.single().first)
        assertEquals("application/vnd.github+json", requests.single().second["Accept"])
        assertTrue(requests.single().second["User-Agent"]!!.isNotBlank())
    }

    @Test fun upToDateOrOlder() {
        assertEquals(CheckResult.UpToDate("0.1.17"), checker(Store(), installed = 118).check(true))
        assertEquals(CheckResult.UpToDate("0.1.17"), checker(Store(), installed = 200).check(true))
    }

    @Test fun automaticChecksAreThrottledManualOnesAreNot() {
        val store = Store(lastCheckMillis = now - 60_000)
        assertEquals(CheckResult.Skipped, checker(store).check(false))
        assertTrue(requests.isEmpty())
        assertTrue(checker(store).check(true) is CheckResult.Available)
        store.lastCheckMillis = now - UpdateSchedule.INTERVAL_MILLIS
        assertTrue(checker(store).check(false) is CheckResult.Available)
    }

    @Test fun laterSilencesThatVersionOnlyForAutomaticChecks() {
        val store = Store()
        val c = checker(store)
        c.later((c.check(true) as CheckResult.Available).release)
        store.lastCheckMillis = 0
        assertEquals(CheckResult.Skipped, c.check(false))
        assertTrue(c.check(true) is CheckResult.Available)
        reply = { HttpReply(200, emptyMap(), body.replace("0.1.18", "0.1.19")) }
        store.lastCheckMillis = 0
        assertTrue(c.check(false) is CheckResult.Available) // a newer release is offered again
    }

    @Test fun clockGoingBackDoesNotBlockChecks() {
        assertTrue(UpdateSchedule.due(now + 1_000_000, now))
        assertTrue(UpdateSchedule.due(0, now))
        assertFalse(UpdateSchedule.due(now - 1000, now))
    }

    @Test fun offline() {
        val c = UpdateChecker({ _, _ -> throw IOException("down") }, Store(), 1, "1", clock = { now })
        assertEquals(CheckResult.Failed(UpdateFailure.Offline), c.check(true))
    }

    @Test fun rateLimitMapping() {
        reply = { HttpReply(429, mapOf("retry-after" to "60"), "") }
        assertEquals(CheckResult.Failed(UpdateFailure.RateLimited(now + 60_000)), checker(Store()).check(true))
        reply = { HttpReply(403, mapOf("x-ratelimit-reset" to "1700000000", "x-ratelimit-remaining" to "0"), "") }
        assertEquals(CheckResult.Failed(UpdateFailure.RateLimited(1_700_000_000_000)), checker(Store()).check(true))
        reply = { HttpReply(403, mapOf("x-ratelimit-remaining" to "0"), "") }
        assertEquals(CheckResult.Failed(UpdateFailure.RateLimited(null)), checker(Store()).check(true))
        // a 403 with requests left is not a rate limit
        reply = { HttpReply(403, mapOf("x-ratelimit-remaining" to "7"), "") }
        assertEquals(CheckResult.Failed(UpdateFailure.Http(403)), checker(Store()).check(true))
    }

    @Test fun httpErrorsBadBodiesAndNoAssetYet() {
        reply = { HttpReply(404, emptyMap(), "{}") }
        assertEquals(CheckResult.Failed(UpdateFailure.Http(404)), checker(Store()).check(true))
        reply = { HttpReply(502, emptyMap(), "") }
        assertEquals(CheckResult.Failed(UpdateFailure.Http(502)), checker(Store()).check(true))
        reply = { HttpReply(200, emptyMap(), "nope") }
        assertEquals(CheckResult.Failed(UpdateFailure.BadResponse), checker(Store()).check(true))
        reply = { HttpReply(200, emptyMap(), body.replace("peel-0.1.18.apk", "x.zip")) }
        assertTrue(checker(Store()).check(true) is CheckResult.UpToDate)
    }

    @Test fun rowDetailSaysWhenToRetry() {
        val s = UpdateState.Failed(UpdateFailure.RateLimited(now + 1000))
        assertTrue(UpdateText.rowDetail(s) { now }!!.contains("Try again after"))
        assertEquals(UpdateFailure.Offline.message, UpdateText.rowDetail(UpdateState.Failed(UpdateFailure.Offline)))
        assertNull(UpdateText.rowDetail(UpdateState.Idle))
    }
}
