package com.sphinx.peel.engine

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/// The memes server: sign in with a signed challenge, upload or
/// download an attachment's encrypted bytes. Ported from PeelCore's
/// `MemesServer.swift`, trimmed to what sending an image (and
/// receiving anything) needs — no preferences file, no payment
/// templates.
class MemesServer(private val host: String) {
    data class Token(val value: String, val expiresAtMs: Long) {
        val isFresh: Boolean get() = expiresAtMs - System.currentTimeMillis() > 60_000
    }

    class RefusedException(val status: Int, what: String) : Exception("the memes server refused $what (HTTP $status)") {
        val isUnauthorized: Boolean get() = status == 401 || status == 403
    }
    class UnreadableException(what: String) : Exception("the memes server's reply to $what was unreadable")
    class TooLargeException(val size: Int) : Exception("the attachment is larger than expected ($size bytes)")

    /// `GET /ask`: an id and a challenge to sign.
    suspend fun challenge(): Pair<String, String> = withContext(Dispatchers.IO) {
        val connection = (URL("https://$host/ask").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        val body = readText(connection, "sign-in")
        val json = runCatching { JSONObject(body) }.getOrNull() ?: throw UnreadableException("sign-in")
        val id = json.optString("id").takeIf { it.isNotEmpty() }
        val text = json.optString("challenge").takeIf { it.isNotEmpty() }
        if (id == null || text == null) throw UnreadableException("sign-in")
        id to text
    }

    /// `POST /verify`: the signed challenge for a bearer token. Built by
    /// hand, same as PeelCore: the query carries the signature as is,
    /// not re-encoded through a form body.
    suspend fun verify(id: String, signature: String, pubkey: String): Token = withContext(Dispatchers.IO) {
        val query = "id=${URLEncoder.encode(id, "UTF-8")}&sig=${URLEncoder.encode(signature, "UTF-8")}" +
            "&pubkey=${URLEncoder.encode(pubkey, "UTF-8")}"
        val connection = (URL("https://$host/verify?$query").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "multipart/form-data")
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        val body = readText(connection, "the signed challenge")
        val json = runCatching { JSONObject(body) }.getOrNull() ?: throw UnreadableException("the signed challenge")
        val token = json.optString("token").takeIf { it.isNotEmpty() } ?: throw UnreadableException("the signed challenge")
        Token(token, Jwt.expiryMs(token))
    }

    /// `GET /file/<media token>`: the encrypted bytes and the file name
    /// the server gives, refusing anything over `limit` bytes (the
    /// whole point of the limit: never buffer more than that, not just
    /// reject after the fact).
    suspend fun download(mediaToken: String, token: Token, limit: Int): Pair<ByteArray, String?> =
        withContext(Dispatchers.IO) {
            val connection = (URL("https://$host/file/$mediaToken").openConnection() as HttpURLConnection).apply {
                setRequestProperty("Authorization", "Bearer ${token.value}")
                connectTimeout = 10_000
                readTimeout = 60_000
            }
            val status = connection.responseCode
            if (status != 200) {
                connection.disconnect()
                throw RefusedException(status, "the download")
            }
            val fileName = connection.getHeaderField("Content-Disposition")?.let(::fileNameFromDisposition)
            val data = try {
                readUpTo(connection.inputStream, limit)
            } finally {
                connection.disconnect()
            }
            data to fileName
        }

    /// `POST /file`: the encrypted bytes, as a multipart body (one text
    /// field, then the file) — PeelCore's `upload`/`multipart`. Returns
    /// the muid.
    suspend fun upload(sealed: ByteArray, fieldName: String, fileName: String, mediaType: String, token: Token): String =
        withContext(Dispatchers.IO) {
            val boundary = "peel-${java.util.UUID.randomUUID()}"
            val body = java.io.ByteArrayOutputStream()
            fun part(headers: String, content: ByteArray) {
                body.write("--$boundary\r\n$headers\r\n\r\n".toByteArray(Charsets.UTF_8))
                body.write(content)
                body.write("\r\n".toByteArray(Charsets.UTF_8))
            }
            part("Content-Disposition: form-data; name=\"name\"", fieldName.toByteArray(Charsets.UTF_8))
            val safeFileName = fileName.filter { it.code >= 0x20 }.replace("\"", "'")
            part(
                "Content-Disposition: form-data; name=\"file\"; filename=\"$safeFileName\"\r\nContent-Type: $mediaType",
                sealed,
            )
            body.write("--$boundary--\r\n".toByteArray(Charsets.UTF_8))
            val bodyBytes = body.toByteArray()

            val connection = (URL("https://$host/file").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                setRequestProperty("Authorization", "Bearer ${token.value}")
                connectTimeout = 10_000
                readTimeout = 60_000
                setFixedLengthStreamingMode(bodyBytes.size)
            }
            connection.outputStream.use { it.write(bodyBytes) }
            val replyText = readText(connection, "the upload")
            val json = runCatching { JSONObject(replyText) }.getOrNull() ?: throw UnreadableException("the upload")
            val muid = json.optString("muid").takeIf { it.isNotEmpty() && MediaToken.isSafeMuid(it) }
                ?: throw UnreadableException("the upload")
            muid
        }

    /// `GET /preferences`: the account's encrypted preferences file, as
    /// base64 text — PeelCore's own `preferences`, including its exact
    /// "not found" shape: a JSON `{"success": false, "error":
    /// "Preferences not found"}` (status usually 404, sometimes 200 —
    /// read by content here, not status) means no file yet, returned as
    /// null; anything else that parses as JSON is a refusal; anything
    /// else (plain text, status 200) is the file itself.
    suspend fun preferences(token: Token): String? = withContext(Dispatchers.IO) {
        val connection = (URL("https://$host/preferences").openConnection() as HttpURLConnection).apply {
            setRequestProperty("Authorization", "Bearer ${token.value}")
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        val status = connection.responseCode
        val body = try {
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            stream?.bufferedReader()?.use { it.readText() } ?: ""
        } finally {
            connection.disconnect()
        }
        val json = runCatching { JSONObject(body) }.getOrNull()
        if (json != null) {
            if (jsonSuccess(json) == false && json.optString("error") == "Preferences not found") return@withContext null
            throw RefusedException(status, "the preferences file")
        }
        if (status != 200 || body.isEmpty()) throw RefusedException(status, "the preferences file")
        body
    }

    /// `POST /preferences`: the whole encrypted file (already base64
    /// text), with the account's pubkey as base64url — PeelCore's own
    /// `uploadPreferences`, the same multipart shape `upload` already
    /// uses, just with a `"pubkey"` field instead of `"name"`.
    suspend fun uploadPreferences(sealedBase64: String, pubkeyField: String, token: Token) = withContext(Dispatchers.IO) {
        val boundary = "peel-${java.util.UUID.randomUUID()}"
        val body = java.io.ByteArrayOutputStream()
        fun part(headers: String, content: ByteArray) {
            body.write("--$boundary\r\n$headers\r\n\r\n".toByteArray(Charsets.UTF_8))
            body.write(content)
            body.write("\r\n".toByteArray(Charsets.UTF_8))
        }
        part("Content-Disposition: form-data; name=\"pubkey\"", pubkeyField.toByteArray(Charsets.UTF_8))
        part(
            "Content-Disposition: form-data; name=\"file\"; filename=\"preferences\"\r\nContent-Type: application/octet-stream",
            sealedBase64.toByteArray(Charsets.UTF_8),
        )
        body.write("--$boundary--\r\n".toByteArray(Charsets.UTF_8))
        val bodyBytes = body.toByteArray()

        val connection = (URL("https://$host/preferences").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Authorization", "Bearer ${token.value}")
            connectTimeout = 10_000
            readTimeout = 30_000
            setFixedLengthStreamingMode(bodyBytes.size)
        }
        connection.outputStream.use { it.write(bodyBytes) }
        val replyText = readText(connection, "the preferences file")
        val json = runCatching { JSONObject(replyText) }.getOrNull() ?: throw UnreadableException("the preferences file")
        if (jsonSuccess(json) != true) throw UnreadableException("the preferences file")
    }

    /// `success` as the official apps' reply reads it: a bool, or a
    /// number (not 0 is true); null when it's neither.
    private fun jsonSuccess(json: JSONObject): Boolean? = when (val value = json.opt("success")) {
        is Boolean -> value
        is Int -> value != 0
        is Long -> value != 0L
        null -> null
        else -> null
    }

    private fun readText(connection: HttpURLConnection, what: String): String {
        val status = connection.responseCode
        try {
            if (status != 200) throw RefusedException(status, what)
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /// Reads at most `limit` bytes, throwing the moment a `limit + 1`th
    /// one shows up — never buffers more than that trying to find out.
    private fun readUpTo(stream: java.io.InputStream, limit: Int): ByteArray {
        val buffer = java.io.ByteArrayOutputStream(minOf(limit, 1 shl 16))
        val chunk = ByteArray(1 shl 16)
        var total = 0
        while (true) {
            val read = stream.read(chunk)
            if (read < 0) break
            total += read
            if (total > limit) throw TooLargeException(total)
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    /// The `filename=` of a Content-Disposition header, made safe to
    /// show: the last path component, no control characters, at most
    /// 200 characters. Simplified from PeelCore's own (which also
    /// strips leading dots/colons) — this is for display only, never a
    /// path on disk (attachments cache by `muid`, never by this name).
    private fun fileNameFromDisposition(header: String): String? {
        val marker = "filename="
        val index = header.indexOf(marker)
        if (index < 0) return null
        val raw = header.substring(index + marker.length).split(";").first().trim().trim('"')
        val last = raw.substringAfterLast('/').substringAfterLast('\\')
        val clean = last.filter { it.code >= 0x20 }.take(200)
        return clean.ifEmpty { null }
    }
}

/// Just enough of a JWT to read its `exp` claim — the memes server's
/// bearer token, a standard `header.payload.signature` JWT.
private object Jwt {
    fun expiryMs(token: String): Long {
        val parts = token.split(".")
        if (parts.size < 2) return 0
        return try {
            val payload = JSONObject(String(base64UrlDecode(parts[1]), Charsets.UTF_8))
            if (payload.has("exp")) (payload.getDouble("exp") * 1000).toLong() else 0
        } catch (e: Exception) {
            0
        }
    }

    private fun base64UrlDecode(text: String): ByteArray {
        val padded = text.replace('-', '+').replace('_', '/').let { it + "=".repeat((4 - it.length % 4) % 4) }
        return java.util.Base64.getDecoder().decode(padded)
    }
}
