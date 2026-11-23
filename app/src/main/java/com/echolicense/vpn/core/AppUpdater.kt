package com.echolicense.vpn.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.concurrent.TimeUnit

data class AppUpdateInfo(
    val latestTag: String,
    val downloadUrl: String,
    val fileName: String,
    val sizeMb: Double,
    val updatedAt: String,
    val hasUpdate: Boolean
)

object AppUpdater {
    private const val SITE_BASE = "https://app.echolicense.ai"
    private const val META_URL = "$SITE_BASE/downloads/latest.json"
    private const val GH_API_URL = "https://api.github.com/repos/Rockst4r4/V2Echo/releases/latest"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private fun parseVersionTuple(raw: String): List<Int> {
        val clean = raw.trim().lowercase().removePrefix("v")
        return clean.split(".").map { part ->
            part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        }
    }

    private fun isNewerVersion(remoteTag: String, localVersion: String): Int {
        val r = parseVersionTuple(remoteTag)
        val l = parseVersionTuple(localVersion)
        val len = maxOf(r.size, l.size)
        for (i in 0 until len) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv.compareTo(lv)
        }
        return 0
    }

    private fun parseIsoEpoch(iso: String): Long {
        if (iso.isBlank()) return 0L
        return runCatching { Instant.parse(iso.trim()).epochSecond }.getOrDefault(0L)
    }

    fun checkForUpdate(currentVersion: String, buildEpoch: Long): AppUpdateInfo? {
        val fromSite = runCatching { fetchFromSite(currentVersion, buildEpoch) }.getOrNull()
        if (fromSite != null) return fromSite
        return runCatching { fetchFromGithub(currentVersion, buildEpoch) }.getOrNull()
    }

    private fun fetchFromSite(currentVersion: String, buildEpoch: Long): AppUpdateInfo? {
        val req = Request.Builder()
            .url("$META_URL?t=${System.currentTimeMillis() / 60000L}")
            .header("User-Agent", "Echolicense-Android/$currentVersion")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string().orEmpty()
            val root = JSONObject(body)
            val tag = root.optString("tag", "").ifBlank { return null }
            val androidObj = root.optJSONObject("android") ?: return null
            val relUrl = androidObj.optString("url", "").ifBlank { return null }
            val fullUrl = if (relUrl.startsWith("http")) relUrl else "$SITE_BASE$relUrl"
            val name = androidObj.optString("name", "Echolicense-Android.apk")
            val sizeMb = androidObj.optDouble("size_mb", 0.0)
            val updatedAt = androidObj.optString("updated_at", "")
            val cmp = isNewerVersion(tag, currentVersion)
            val remoteEpoch = parseIsoEpoch(updatedAt)
            val hasUpdate = (cmp > 0) || (cmp == 0 && buildEpoch > 0L && remoteEpoch > buildEpoch + 600L)
            return AppUpdateInfo(tag, fullUrl, name, sizeMb, updatedAt, hasUpdate)
        }
    }

    private fun fetchFromGithub(currentVersion: String, buildEpoch: Long): AppUpdateInfo? {
        val req = Request.Builder()
            .url(GH_API_URL)
            .header("User-Agent", "Echolicense-Android/$currentVersion")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val root = JSONObject(resp.body?.string().orEmpty())
            val tag = root.optString("tag_name", "").ifBlank { return null }
            val assets = root.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name", "")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    val url = a.optString("browser_download_url", "")
                    val sizeMb = (a.optLong("size", 0L) / 1048576.0)
                    val updatedAt = a.optString("updated_at", "")
                    val cmp = isNewerVersion(tag, currentVersion)
                    val remoteEpoch = parseIsoEpoch(updatedAt)
                    val hasUpdate = (cmp > 0) || (cmp == 0 && buildEpoch > 0L && remoteEpoch > buildEpoch + 600L)
                    return AppUpdateInfo(tag, url, name, sizeMb, updatedAt, hasUpdate)
                }
            }
            return null
        }
    }

    fun downloadApk(
        context: Context,
        url: String,
        fileName: String,
        onProgress: (Int) -> Unit
    ): File {
        val dir = File(context.cacheDir, "updates")
        if (!dir.exists()) dir.mkdirs()
        dir.listFiles()?.forEach { runCatching { it.delete() } }

        val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "Echolicense-Update.apk" }
        val outFile = File(dir, safeName)

        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Echolicense-Android-Updater")
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body ?: error("Empty response")
            val total = body.contentLength()
            var copied = 0L
            var lastPct = -1

            body.byteStream().use { input ->
                FileOutputStream(outFile).use { output ->
                    val buf = ByteArray(16384)
                    while (true) {
                        val read = input.read(buf)
                        if (read == -1) break
                        output.write(buf, 0, read)
                        copied += read
                        if (total > 0L) {
                            val pct = ((copied * 100L) / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                    output.flush()
                }
            }
        }
        if (!outFile.exists() || outFile.length() < 1_000_000L) {
            runCatching { outFile.delete() }
            error("Downloaded APK is invalid")
        }
        onProgress(100)
        return outFile
    }

    fun canInstallPackages(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()
    }

    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    fun launchApkInstaller(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
