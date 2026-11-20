package com.sphinx.peel.update

import org.json.JSONArray
import org.json.JSONObject

/// A release's version as release.yml reads it from the tag: `vMAJOR.MINOR.PATCH`, minor and patch at most 99, and
/// `versionCode = major * 10000 + minor * 100 + patch`. The formula must stay the same as the workflow's, or an
/// update would be judged older than the installed build (`UpdateReleaseTest` pins both).
data class ReleaseVersion(val major: Int, val minor: Int, val patch: Int) {
    val name: String get() = "$major.$minor.$patch"
    val code: Int get() = major * 10_000 + minor * 100 + patch

    companion object {
        private val TAG = Regex("""^v(\d{1,4})\.(\d{1,2})\.(\d{1,2})$""")

        /// Null for anything release.yml would not have built (`v1.2`, `nightly`, `v1.100.0`).
        fun parseTag(tag: String?): ReleaseVersion? {
            val m = TAG.matchEntire(tag?.trim() ?: return null) ?: return null
            val (major, minor, patch) = m.destructured
            return ReleaseVersion(major.toInt(), minor.toInt(), patch.toInt())
        }
    }
}

data class ReleaseAsset(val name: String, val url: String, val size: Long)

/// What the prompt needs from a GitHub release: the version, its notes, and the APK to fetch.
data class ReleaseInfo(
    val version: ReleaseVersion,
    val title: String,
    val notes: String,
    val pageUrl: String?,
    val apk: ReleaseAsset,
)

/// Why a check or an update did not go through, each with the words shown to the person.
sealed class UpdateFailure(val message: String) {
    object Offline : UpdateFailure("No connection to GitHub. Check your internet and try again.")
    /// [resetAtMillis]: when GitHub says the limit lifts (X-RateLimit-Reset / Retry-After), if it did.
    data class RateLimited(val resetAtMillis: Long?) : UpdateFailure("GitHub is limiting update checks right now. Try again later.")
    object BadResponse : UpdateFailure("GitHub's answer couldn't be read. Try again later.")
    data class Http(val code: Int) : UpdateFailure("GitHub answered with an error ($code). Try again later.")
    /// The release workflow publishes the release first and uploads the APK minutes later.
    data class NoAsset(val tag: String) : UpdateFailure("Release $tag has no APK yet. Try again in a few minutes.")
    object Disabled : UpdateFailure("Updates are only available in release builds of Peel.")
    object Insecure : UpdateFailure("The update's address isn't secure (HTTPS), so it was not downloaded.")
    object SizeMismatch : UpdateFailure("The download was incomplete or damaged. Try again.")
    object WrongSignature : UpdateFailure("This update isn't signed with the same key as the installed app, so Android would refuse to install it over Peel.")
    object NotNewer : UpdateFailure("The downloaded app isn't newer than the installed one, so it was not installed.")
    object UnreadableApk : UpdateFailure("The downloaded file isn't a valid Peel package.")
    data class Storage(val detail: String?) : UpdateFailure("Couldn't save the update on this device.")
    object InstallBlocked : UpdateFailure("Android didn't let Peel start the installer.")
}

/// Parses GitHub's `GET /repos/{owner}/{repo}/releases/latest`. Pure: takes the body, returns a release or why not.
object ReleaseParser {
    sealed class Parsed {
        data class Ok(val release: ReleaseInfo) : Parsed()
        data class Failed(val failure: UpdateFailure) : Parsed()
    }

    fun parse(body: String): Parsed {
        val json = try { JSONObject(body) } catch (_: Exception) { return Parsed.Failed(UpdateFailure.BadResponse) }
        val tag = json.optString("tag_name", "")
        val version = ReleaseVersion.parseTag(tag) ?: return Parsed.Failed(UpdateFailure.BadResponse)
        val apk = pickApk(version, assetsOf(json.optJSONArray("assets"))) ?: return Parsed.Failed(UpdateFailure.NoAsset(tag))
        val title = json.optString("name", "").ifBlank { tag }
        val page = json.optString("html_url", "").ifBlank { null }
        return Parsed.Ok(ReleaseInfo(version, title, json.optString("body", ""), page, apk))
    }

    private fun assetsOf(array: JSONArray?): List<ReleaseAsset> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val a = array.optJSONObject(i) ?: return@mapNotNull null
            val name = a.optString("name", "")
            val url = a.optString("browser_download_url", "")
            if (name.isEmpty() || url.isEmpty()) null else ReleaseAsset(name, url, a.optLong("size", -1))
        }
    }

    /// release.yml uploads exactly `peel-<version>.apk`; anything else is not ours to install.
    fun pickApk(version: ReleaseVersion, assets: List<ReleaseAsset>): ReleaseAsset? =
        assets.firstOrNull { it.name == "peel-${version.name}.apk" }
}

/// "Release notes" as the dialog shows them: plain text, markdown left alone but trimmed and capped.
object ReleaseNotes {
    const val LIMIT = 4_000

    fun plain(body: String, limit: Int = LIMIT): String {
        val text = body.replace("\r\n", "\n").replace('\r', '\n').trim().replace(Regex("\n{3,}"), "\n\n")
        if (text.length <= limit) return text
        return text.take(limit).trimEnd() + "…"
    }
}
