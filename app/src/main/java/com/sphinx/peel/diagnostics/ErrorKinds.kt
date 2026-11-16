package com.sphinx.peel.diagnostics

import com.sphinx.peel.diagnostics.DiagnosticEvent.ErrorKind

/// The kind of an error, for the diagnostics ring (iOS `PeelError.diagnosticKind`): the same split as
/// the notices make, and never their words. Matched on class names up the cause chain so it needs no
/// Android classes (and runs on a plain JVM). Android's exceptions differ from iOS's, so the table is
/// its own; the kinds are the same.
object ErrorKinds {
    fun of(error: Throwable): ErrorKind {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 6) {
            kindOf(current)?.let { return it }
            current = current.cause?.takeIf { it !== current }
            depth++
        }
        return ErrorKind.other
    }

    private fun kindOf(e: Throwable): ErrorKind? {
        val name = e.javaClass.name
        val simple = e.javaClass.simpleName
        return when {
            name.contains("SQLite") || name.startsWith("java.sql.") -> ErrorKind.database
            e is java.util.concurrent.CancellationException || simple == "CancellationException" -> ErrorKind.cancelled
            name.startsWith("javax.net.ssl.") || name.startsWith("java.security.cert.") || name.contains("SSLException") -> ErrorKind.secureConnection
            e is java.net.UnknownHostException || e is java.net.SocketException || e is java.net.SocketTimeoutException ||
                e is java.net.ConnectException || e is java.net.NoRouteToHostException -> ErrorKind.network
            name.startsWith("com.hivemq.") || simple == "NotConnectedException" -> ErrorKind.connection
            name.startsWith("uniffi.sphinxrs.") -> ErrorKind.core
            name.startsWith("org.json.") || name.startsWith("kotlinx.serialization.") || e is NumberFormatException -> ErrorKind.decoding
            e is java.io.FileNotFoundException || e is java.nio.file.NoSuchFileException || e is java.nio.file.AccessDeniedException ||
                (e is java.io.IOException && (e.message ?: "").let { it.contains("ENOSPC") || it.contains("No space left") || it.contains("EACCES") || it.contains("ENOENT") }) -> ErrorKind.file
            name.startsWith("com.sphinx.peel.engine.CallServer") || name.startsWith("com.sphinx.peel.call.") -> ErrorKind.call
            name.startsWith("com.sphinx.peel.engine.MemesServer") || name.startsWith("com.sphinx.peel.engine.Hive") -> ErrorKind.server
            name.startsWith("com.sphinx.peel.engine.") -> ErrorKind.engine
            e is IllegalStateException && (e.message ?: "").startsWith("Not connected") -> ErrorKind.connection
            e is java.io.IOException -> ErrorKind.network
            else -> null
        }
    }
}
