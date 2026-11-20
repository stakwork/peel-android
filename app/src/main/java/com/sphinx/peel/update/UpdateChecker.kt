package com.sphinx.peel.update

import java.io.IOException

/// One HTTP answer, as much as the checker reads. [headers] keys are lower-case.
data class HttpReply(val code: Int, val headers: Map<String, String>, val body: String)

/// The seam over OkHttp, so the checker runs on the JVM. Throws [IOException] when there is no answer at all.
fun interface UpdateHttp {
    fun get(url: String, headers: Map<String, String>): HttpReply
}

/// What a check found.
sealed class CheckResult {
    data class UpToDate(val installedName: String) : CheckResult()
    data class Available(val release: ReleaseInfo) : CheckResult()
    data class Failed(val failure: UpdateFailure) : CheckResult()
    /// Automatic checks only: too soon after the last one, or the person said Later to this very version.
    object Skipped : CheckResult()
}

/// Where the throttle and "Later" are kept. App-wide SharedPreferences, not `Storage`: that is one database per
/// account, and the check runs for the app, before and whichever account is open.
interface UpdateStore {
    var lastCheckMillis: Long
    var dismissedCode: Int
}

/// The two automatic-check rules: not more than every [INTERVAL_MILLIS], and not again for a version put off.
object UpdateSchedule {
    const val INTERVAL_MILLIS = 12L * 60 * 60 * 1000

    fun due(lastCheckMillis: Long, now: Long): Boolean =
        lastCheckMillis <= 0 || now < lastCheckMillis /* clock went back */ || now - lastCheckMillis >= INTERVAL_MILLIS

    fun dismissed(releaseCode: Int, dismissedCode: Int): Boolean = dismissedCode == releaseCode
}

/// The decision itself: newer code (the asset's existence is already settled by [ReleaseParser]).
object UpdateDecision {
    fun isNewer(release: ReleaseVersion, installedCode: Int): Boolean = release.code > installedCode
}

/// Asks GitHub for the latest release and decides. No Android here.
class UpdateChecker(
    private val http: UpdateHttp,
    private val store: UpdateStore,
    private val installedCode: Int,
    private val installedName: String,
    private val repo: String = DEFAULT_REPO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /// [manual]: a tap on "Check for updates" ignores the throttle and "Later"; the launch check honours both.
    fun check(manual: Boolean): CheckResult {
        val now = clock()
        if (!manual && !UpdateSchedule.due(store.lastCheckMillis, now)) return CheckResult.Skipped
        val reply = try {
            http.get("https://api.github.com/repos/$repo/releases/latest", HEADERS)
        } catch (_: IOException) {
            return CheckResult.Failed(UpdateFailure.Offline)
        }
        // Any answer counts as a try, so a rate-limited or broken API isn't hit again at every launch.
        store.lastCheckMillis = now
        if (rateLimited(reply)) return CheckResult.Failed(UpdateFailure.RateLimited(resetAt(reply, now)))
        if (reply.code !in 200..299) return CheckResult.Failed(UpdateFailure.Http(reply.code))
        return when (val parsed = ReleaseParser.parse(reply.body)) {
            // The workflow publishes the release minutes before its APK: no asset yet is "no update yet", not an error.
            is ReleaseParser.Parsed.Failed ->
                if (parsed.failure is UpdateFailure.NoAsset) CheckResult.UpToDate(installedName) else CheckResult.Failed(parsed.failure)
            is ReleaseParser.Parsed.Ok -> {
                val release = parsed.release
                when {
                    !UpdateDecision.isNewer(release.version, installedCode) -> CheckResult.UpToDate(installedName)
                    !manual && UpdateSchedule.dismissed(release.version.code, store.dismissedCode) -> CheckResult.Skipped
                    else -> CheckResult.Available(release)
                }
            }
        }
    }

    /// "Later" for this version: the launch check stays quiet about it until a newer one is published.
    fun later(release: ReleaseInfo) { store.dismissedCode = release.version.code }

    /// 429, or 403 with no requests left (a plain 403 is something else: not a limit).
    private fun rateLimited(r: HttpReply) =
        r.code == 429 || (r.code == 403 && (r.headers["x-ratelimit-remaining"]?.trim() == "0" || r.headers.containsKey("retry-after")))

    /// Retry-After (seconds) wins; else X-RateLimit-Reset (epoch seconds). Null when GitHub said neither.
    private fun resetAt(r: HttpReply, now: Long): Long? {
        r.headers["retry-after"]?.trim()?.toLongOrNull()?.let { return now + it * 1000 }
        r.headers["x-ratelimit-reset"]?.trim()?.toLongOrNull()?.let { return it * 1000 }
        return null
    }

    companion object {
        const val DEFAULT_REPO = "stakwork/peel-android"
        val HEADERS = mapOf(
            "Accept" to "application/vnd.github+json",
            "X-GitHub-Api-Version" to "2022-11-28",
            "User-Agent" to "peel-android-updater",
        )
    }
}
