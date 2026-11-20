package com.sphinx.peel.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.sphinx.peel.BuildConfig
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import okhttp3.Request

/// The Android parts of self-update, kept thin: everything decided lives in the pure classes beside this file.

class PrefsUpdateStore(context: Context) : UpdateStore {
    private val prefs = context.getSharedPreferences("peel_update", Context.MODE_PRIVATE)
    override var lastCheckMillis: Long
        get() = prefs.getLong("lastCheck", 0)
        set(v) { prefs.edit().putLong("lastCheck", v).apply() }
    override var dismissedCode: Int
        get() = prefs.getInt("dismissedCode", 0)
        set(v) { prefs.edit().putInt("dismissedCode", v).apply() }
}

/// No redirect from https to http (OkHttp would follow it by default); GitHub's own redirect to its CDN stays https.
private val client: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
}

private fun requireHttps(url: String) { if (!url.startsWith("https://")) throw IOException("not https") }

class OkHttpUpdateHttp : UpdateHttp {
    override fun get(url: String, headers: Map<String, String>): HttpReply {
        requireHttps(url)
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        client.newCall(request).execute().use { r ->
            // An API answer is small; cap what is read so a wrong server can't fill memory.
            val body = r.body?.source()?.let { it.request(2_000_000); it.buffer.snapshot().utf8() } ?: ""
            val h = r.headers.names().associate { it.lowercase() to (r.header(it) ?: "") }
            return HttpReply(r.code, h, body)
        }
    }
}

class OkHttpApkFetch : ApkFetch {
    override fun open(url: String): ApkStream {
        requireHttps(url)
        val response = client.newCall(Request.Builder().url(url).build()).execute()
        if (!response.isSuccessful) { response.close(); throw IOException("HTTP ${response.code}") }
        val body = response.body ?: run { response.close(); throw IOException("empty") }
        return object : ApkStream {
            override val length: Long = body.contentLength()
            override val input: InputStream = body.byteStream()
            override fun close() { response.close() }
        }
    }
}

class AndroidApkInstaller(private val context: Context) : ApkInstaller {
    private val pm get() = context.packageManager

    override fun canInstall(): Boolean = pm.canRequestPackageInstalls()

    override fun openPermissionSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { context.startActivity(intent) } catch (_: ActivityNotFoundException) {}
    }

    override fun installedFacts(): PackageFacts? = runCatching { facts(packageInfo(null)) }.getOrNull()

    override fun archiveFacts(file: File): PackageFacts? = runCatching { facts(packageInfo(file.path)) }.getOrNull()

    @Suppress("DEPRECATION")
    private fun packageInfo(archivePath: String?): PackageInfo? {
        // API 28+ has signing certificates proper; before that the (deprecated) signatures are all there is.
        val flag = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        return if (archivePath == null) pm.getPackageInfo(context.packageName, flag) else pm.getPackageArchiveInfo(archivePath, flag)
    }

    @Suppress("DEPRECATION")
    private fun facts(info: PackageInfo?): PackageFacts? {
        info ?: return null
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        return PackageFacts(info.packageName, code, signatures.orEmpty().map { sha256(it.toByteArray()) }.toSet())
    }

    override fun install(file: File): Boolean = try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) { false } catch (_: RuntimeException) { false }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/// The one controller of the process, so the You row and the prompt share it and a download outlives either screen.
object Updates {
    @Volatile private var controller: UpdateController? = null

    fun get(context: Context): UpdateController = controller ?: synchronized(this) {
        controller ?: build(context.applicationContext).also { controller = it }
    }

    private fun build(app: Context): UpdateController {
        val checker = UpdateChecker(OkHttpUpdateHttp(), PrefsUpdateStore(app), BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME, BuildConfig.UPDATE_REPO)
        return UpdateController(
            checker, ApkDownloader(OkHttpApkFetch()), AndroidApkInstaller(app),
            File(app.cacheDir, UpdateFiles.DIR), BuildConfig.VERSION_CODE, BuildConfig.UPDATES_ENABLED,
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
    }
}
