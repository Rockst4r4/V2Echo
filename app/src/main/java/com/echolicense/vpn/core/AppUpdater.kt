package com.echolicense.vpn.core

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
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
    private const val GH_WEB_LATEST = "https://github.com/Rockst4r4/V2Echo/releases/latest"
    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private val ipv4FirstDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val all = Dns.SYSTEM.lookup(hostname)
            val v4 = all.filterIsInstance<Inet4Address>()
            val others = all.filterNot { it is Inet4Address }
            return if (v4.isNotEmpty()) v4 + others else all
        }
    }

    private fun buildClient(proxyPort: Int = 0, followRedirects: Boolean = true): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .dns(ipv4FirstDns)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(18, TimeUnit.SECONDS)
            .followRedirects(followRedirects)
            .followSslRedirects(followRedirects)
        if (proxyPort > 0) {
            builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort)))
        }
        return builder.build()
    }

    private fun parseVersionTuple(raw: String): List<Int> {
        val clean = raw.trim().lowercase().removePrefix("v")
        val parts = clean.split(".").map { part ->
            part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        }.toMutableList()
        while (parts.size < 3) parts.add(0)
        return parts
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

    fun checkForUpdate(currentVersion: String, buildEpoch: Long, proxyPort: Int = 0): AppUpdateInfo? {
        return checkForUpdateDetailed(currentVersion, buildEpoch, proxyPort).first
    }

    fun checkForUpdateDetailed(
        currentVersion: String,
        buildEpoch: Long,
        proxyPort: Int = 0
    ): Pair<AppUpdateInfo?, String> {
        val errs = mutableListOf<String>()
        val portsToTry = if (proxyPort > 0) listOf(0, proxyPort) else listOf(0)

        for (port in portsToTry) {
            val routeTag = if (port > 0) "vpn" else "dir"
            try {
                val res = fetchFromSite(currentVersion, buildEpoch, port)
                if (res != null) return res to ""
                errs.add("site-$routeTag:empty")
            } catch (e: Throwable) {
                errs.add("site-$routeTag:${e.javaClass.simpleName}")
            }
        }

        for (port in portsToTry) {
            val routeTag = if (port > 0) "vpn" else "dir"
            try {
                val res = fetchFromGithubApi(currentVersion, buildEpoch, port)
                if (res != null) return res to ""
            } catch (e: Throwable) {
                errs.add("gh-$routeTag:${e.javaClass.simpleName}")
            }
        }

        for (port in portsToTry) {
            val routeTag = if (port > 0) "vpn" else "dir"
            try {
                val res = fetchFromGithubRedirect(currentVersion, port)
                if (res != null) return res to ""
            } catch (e: Throwable) {
                errs.add("web-$routeTag:${e.javaClass.simpleName}")
            }
        }

        return null to errs.take(3).joinToString(" | ")
    }

    private fun fetchFromSite(currentVersion: String, buildEpoch: Long, proxyPort: Int): AppUpdateInfo? {
        val client = buildClient(proxyPort)
        val req = Request.Builder()
            .url("$META_URL?t=${System.currentTimeMillis() / 60000L}")
            .header("User-Agent", BROWSER_UA)
            .header("Accept", "application/json, */*")
            .header("Cache-Control", "no-cache")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body?.string().orEmpty()
            val root = JSONObject(body)
            val tag = root.optString("tag", "").ifBlank { return null }
            val androidObj = root.optJSONObject("android") ?: return null
            val relUrl = androidObj.optString("url", "").ifBlank { return null }
            val fullUrl = if (relUrl.startsWith("http")) relUrl else "$SITE_BASE$relUrl"
            val name = androidObj.optString("name", "Echolicense-Android-$tag.apk")
            val sizeMb = androidObj.optDouble("size_mb", 0.0)
            val updatedAt = androidObj.optString("updated_at", "")
            val cmp = isNewerVersion(tag, currentVersion)
            val remoteEpoch = parseIsoEpoch(updatedAt)
            val hasUpdate = (cmp > 0) || (cmp == 0 && buildEpoch > 0L && remoteEpoch > buildEpoch + 600L)
            return AppUpdateInfo(tag, fullUrl, name, sizeMb, updatedAt, hasUpdate)
        }
    }

    private fun fetchFromGithubApi(currentVersion: String, buildEpoch: Long, proxyPort: Int): AppUpdateInfo? {
        val client = buildClient(proxyPort)
        val req = Request.Builder()
            .url(GH_API_URL)
            .header("User-Agent", BROWSER_UA)
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val root = JSONObject(resp.body?.string().orEmpty())
            val tag = root.optString("tag_name", "").ifBlank { return null }
            val assets = root.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name", "")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    val url = a.optString("browser_download_url", "")
                    val sizeMb = a.optLong("size", 0L) / 1048576.0
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

    private fun fetchFromGithubRedirect(currentVersion: String, proxyPort: Int): AppUpdateInfo? {
        val client = buildClient(proxyPort, followRedirects = false)
        val req = Request.Builder()
            .url(GH_WEB_LATEST)
            .header("User-Agent", BROWSER_UA)
            .build()
        client.newCall(req).execute().use { resp ->
            val loc = resp.header("Location").orEmpty()
            val idx = loc.lastIndexOf("/tag/")
            if (idx == -1) return null
            val tag = loc.substring(idx + 5).substringBefore("?").trim()
            if (tag.isBlank()) return null
            val apkName = "Echolicense-Android-$tag.apk"
            val dlUrl = "https://github.com/Rockst4r4/V2Echo/releases/download/$tag/$apkName"
            val hasUpdate = isNewerVersion(tag, currentVersion) > 0
            return AppUpdateInfo(tag, dlUrl, apkName, 0.0, "", hasUpdate)
        }
    }
}
