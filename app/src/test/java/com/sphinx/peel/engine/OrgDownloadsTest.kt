package com.sphinx.peel.engine

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/// What an org page's downloads do (iOS `OrgPageController`'s folders and `downloadDidFinish`/`didFailWithError`), against a
/// real local server: saved in a folder of its own under the name the server suggests, cut safe; redirects only inside what the
/// page serves; a failure leaves nothing; closing removes every folder.
class OrgDownloadsTest {
    private lateinit var server: HttpServer
    private lateinit var root: File
    private lateinit var downloads: OrgDownloads
    private val seen = java.util.Collections.synchronizedList(mutableListOf<Map<String, String>>())
    private val port get() = server.address.port

    @Before fun start() {
        root = java.nio.file.Files.createTempDirectory("org-downloads").toFile().resolve("org-downloads")
        downloads = OrgDownloads(root)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            seen += ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") }
            when (ex.requestURI.path) {
                "/lab/files/a.zip" -> {
                    ex.responseHeaders.add("Content-Disposition", "attachment; filename=\"../../evil.zip\"")
                    ex.responseHeaders.add("Content-Type", "application/zip; charset=binary")
                    ex.sendResponseHeaders(200, 5); ex.responseBody.use { it.write(byteArrayOf(1, 2, 3, 4, 5)) }
                }
                "/lab/go" -> { ex.responseHeaders.add("Location", "/lab/files/a.zip"); ex.sendResponseHeaders(302, -1) }
                "/lab/away" -> { ex.responseHeaders.add("Location", "http://localhost:$port/lab/files/a.zip"); ex.sendResponseHeaders(302, -1) }
                else -> { ex.sendResponseHeaders(404, -1) }
            }
            ex.close()
        }
        server.start()
    }

    @After fun stop() { server.stop(0); root.parentFile!!.deleteRecursively() }

    private fun served(url: String) = url.startsWith("http://127.0.0.1:$port/lab/")
    private fun save(path: String, headers: Map<String, String> = emptyMap()) =
        runBlocking { downloads.save("http://127.0.0.1:$port$path", headers, null, ::served) }

    @Test fun `a download is saved in a folder of its own under a safe name`() {
        val saved = save("/lab/files/a.zip") as OrgDownloads.Result.Saved
        assertEquals("evil.zip", saved.file.name)
        assertEquals(saved.folder, saved.file.parentFile)
        assertEquals(root, saved.folder.parentFile)
        assertEquals(listOf<Byte>(1, 2, 3, 4, 5), saved.file.readBytes().toList())
        assertEquals("application/zip", saved.mimeType)
        // Another is in another folder.
        val second = save("/lab/files/a.zip") as OrgDownloads.Result.Saved
        assertFalse(saved.folder == second.folder)
    }

    @Test fun `only the given headers are sent and never a bearer`() {
        save("/lab/files/a.zip", mapOf("User-Agent" to "UA/1", "Cookie" to "a=b"))
        val sent = seen.last()
        assertEquals("UA/1", sent["user-agent"])
        assertEquals("a=b", sent["cookie"])
        assertFalse(sent.containsKey("authorization"))
    }

    @Test fun `a redirect inside the page is followed and one outside is refused`() {
        assertTrue(save("/lab/go") is OrgDownloads.Result.Saved)
        assertEquals(OrgDownloads.Result.Failed, save("/lab/away"))
        // Never fetched from outside what the page serves, whatever was asked for.
        assertEquals(OrgDownloads.Result.Failed, runBlocking { downloads.save("http://127.0.0.1:$port/other/x", emptyMap(), null, ::served) })
    }

    @Test fun `a failure leaves nothing behind`() {
        assertEquals(OrgDownloads.Result.Failed, save("/lab/files/missing.zip"))
        assertEquals(0, root.listFiles()?.size ?: 0)
    }

    @Test fun `a folder that cannot be made says there was no room`() {
        // The root is a file: no folder can be made in it.
        root.parentFile!!.mkdirs(); root.writeText("x")
        assertEquals(OrgDownloads.Result.NoRoom, save("/lab/files/a.zip"))
    }

    @Test fun `closing removes every folder`() {
        val saved = save("/lab/files/a.zip") as OrgDownloads.Result.Saved
        downloads.cleanUp()
        assertFalse(saved.file.exists())
        assertFalse(root.exists())
    }

    @Test fun `removing one download's folder leaves the others`() {
        val a = save("/lab/files/a.zip") as OrgDownloads.Result.Saved
        val b = save("/lab/files/a.zip") as OrgDownloads.Result.Saved
        downloads.remove(a.folder)
        assertFalse(a.folder.exists())
        assertTrue(b.file.exists())
    }
}
