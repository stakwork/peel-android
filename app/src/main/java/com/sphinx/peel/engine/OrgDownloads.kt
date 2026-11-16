package com.sphinx.peel.engine

import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/// The downloads of an org page's web view (iOS `OrgPageController` folders and `OrgWebView.Coordinator` downloads): each is
/// saved in a folder of its own under [root] (the app's cache, which only this app can read), handed to the share sheet, and
/// every folder is removed when the page closes ([cleanUp]). Redirects are followed by hand, and only inside what the page
/// serves (`allowed`): a download is never fetched from anywhere else.
class OrgDownloads(private val root: File, client: OkHttpClient = OkHttpClient()) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val folders = mutableListOf<File>()

    sealed interface Result {
        data class Saved(val file: File, val folder: File, val mimeType: String?) : Result
        /// No folder to keep it in: [OrgPageRules.NO_ROOM].
        data object NoRoom : Result
        /// It did not finish: [OrgPageRules.DOWNLOAD_FAILED].
        data object Failed : Result
    }

    /// A folder for one download.
    fun newFolder(): File? {
        val folder = File(root, UUID.randomUUID().toString())
        return if (folder.mkdirs()) folder.also { synchronized(folders) { folders += it } } else null
    }

    fun remove(folder: File) {
        synchronized(folders) { folders.remove(folder) }
        folder.deleteRecursively()
    }

    /// The page is gone: nothing is kept.
    fun cleanUp() {
        synchronized(folders) { folders.toList().also { folders.clear() } }.forEach { it.deleteRecursively() }
        root.deleteRecursively()
    }

    /// Fetches `url` with `headers` (never the page's bearer: iOS's download carries only what the navigation did) and saves
    /// it under the name the server suggests, cut to a safe one ([OrgPageRules.filename]).
    suspend fun save(url: String, headers: Map<String, String>, contentDisposition: String?, allowed: (String) -> Boolean): Result =
        withContext(Dispatchers.IO) {
            if (!allowed(url)) return@withContext Result.Failed
            val folder = newFolder() ?: return@withContext Result.NoRoom
            try {
                var at = url
                repeat(MAX_REDIRECTS + 1) {
                    val request = Request.Builder().url(at).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
                    client.newCall(request).execute().use { response ->
                        if (response.isRedirect) {
                            val next = response.header("Location")?.let { response.request.url.resolve(it)?.toString() }
                            if (next == null || !allowed(next)) throw IOException("redirect")
                            at = next
                            return@use
                        }
                        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                        val name = OrgPageRules.filename(OrgPageRules.suggestedName(response.header("Content-Disposition") ?: contentDisposition, at))
                        val file = File(folder, name)
                        (response.body ?: throw IOException("empty")).byteStream().use { input -> file.outputStream().use { input.copyTo(it) } }
                        return@withContext Result.Saved(file, folder, response.header("Content-Type")?.substringBefore(';')?.trim())
                    }
                }
                throw IOException("too many redirects")
            } catch (e: IOException) {
                remove(folder)
                Result.Failed
            }
        }

    private companion object {
        const val MAX_REDIRECTS = 5
    }
}
