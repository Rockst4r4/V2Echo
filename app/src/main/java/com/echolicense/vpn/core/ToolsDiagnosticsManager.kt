package com.echolicense.vpn.core

import android.content.Context
import android.content.Intent
import android.os.Build
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

data class AiServiceCheck(
    val name: String,
    val status: String, // "OK", "WARN", "FAIL"
    val detail: String
)

data class IpHealthReport(
    val ip: String,
    val location: String,
    val isp: String,
    val viaVpn: Boolean,
    val services: List<AiServiceCheck>,
    val error: String = ""
)

object ToolsDiagnosticsManager {

    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private fun buildClient(proxyPort: Int): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .followRedirects(true)
        if (proxyPort > 0) {
            b.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort)))
        }
        return b.build()
    }

    fun runHealthCheck(proxyPort: Int): IpHealthReport {
        val client = buildClient(proxyPort)
        var ip = "--"
        var loc = "--"
        var isp = "--"
        var err = ""

        // 1. Public IP & Geo
        var resolved = false
        runCatching {
            val req = Request.Builder().url("https://ipwho.is/").header("User-Agent", BROWSER_UA).build()
            client.newCall(req).execute().use { r ->
                if (r.isSuccessful) {
                    val j = JSONObject(r.body?.string().orEmpty())
                    if (j.optBoolean("success", true)) {
                        ip = j.optString("ip", "--")
                        loc = listOf(j.optString("city"), j.optString("country")).filter { it.isNotBlank() }.joinToString(", ").ifBlank { "--" }
                        val conn = j.optJSONObject("connection")
                        isp = conn?.optString("isp")?.ifBlank { conn.optString("org") } ?: "--"
                        resolved = true
                    }
                }
            }
        }
        if (!resolved) {
            runCatching {
                val req = Request.Builder().url("https://get.geojs.io/v1/ip/geo.json").header("User-Agent", BROWSER_UA).build()
                client.newCall(req).execute().use { r ->
                    if (r.isSuccessful) {
                        val j = JSONObject(r.body?.string().orEmpty())
                        ip = j.optString("ip", "--")
                        loc = listOf(j.optString("city"), j.optString("country")).filter { it.isNotBlank() }.joinToString(", ").ifBlank { "--" }
                        isp = j.optString("organization_name", "--").ifBlank { "--" }
                        resolved = true
                    }
                }
            }.onFailure { err = it.javaClass.simpleName }
        }

        // 2. AI Services reachability
        val targets = listOf(
            "ChatGPT" to "https://chatgpt.com/robots.txt",
            "Gemini" to "https://gemini.google.com/",
            "Claude" to "https://claude.ai/robots.txt"
        )
        val checks = targets.map { (name, url) ->
            val t0 = System.currentTimeMillis()
            try {
                val req = Request.Builder().url(url).header("User-Agent", BROWSER_UA).build()
                client.newCall(req).execute().use { r ->
                    val ms = (System.currentTimeMillis() - t0).toInt()
                    val code = r.code
                    when {
                        code in 200..399 -> AiServiceCheck(name, "OK", "${ms}ms")
                        code == 403 || code == 429 -> AiServiceCheck(name, "WARN", "HTTP $code (${ms}ms)")
                        else -> AiServiceCheck(name, "FAIL", "HTTP $code")
                    }
                }
            } catch (t: Throwable) {
                AiServiceCheck(name, "FAIL", t.javaClass.simpleName.take(14))
            }
        }

        return IpHealthReport(
            ip = ip,
            location = loc,
            isp = isp,
            viaVpn = proxyPort > 0,
            services = checks,
            error = if (!resolved && err.isBlank()) "IP check timeout" else err
        )
    }

    fun buildDiagnosticReport(state: VpnUiState, versionName: String): String {
        val vpnLoss = if (state.vpnAttempts.isNotEmpty()) {
            (state.vpnAttempts.count { !it } * 100) / state.vpnAttempts.size
        } else 0
        val srv = state.activeServer
        return buildString {
            appendLine("=== Echolicense Diagnostic Report ===")
            appendLine("App: Android v$versionName (SDK ${Build.VERSION.SDK_INT}, ${Build.MANUFACTURER} ${Build.MODEL})")
            appendLine("State: ${state.connectionState.name} | Status: ${state.statusMessage}")
            appendLine("Server: ${srv?.name ?: "None"} (${srv?.protocol?.name ?: "-"})")
            appendLine("DNS: ${state.activeDns?.name ?: "Auto"} | Manual: ${state.manualDns.ifBlank { "-" }} | MTU: ${state.mtu} (Auto=${state.autoMtuEnabled})")
            appendLine("Direct Ping: ${state.directPingMs}ms (J=${state.directJitterMs}ms) | VPN Ping: ${state.vpnPingMs}ms (J=${state.vpnJitterMs}ms, Loss=${vpnLoss}%)")
            appendLine("Flags: IranSplit=${state.iranSplitEnabled}, LAN=${state.lanMirrorBypassEnabled}, AdBlock=${state.adBlockerEnabled}, Failover=${state.smartFailoverEnabled}, AiRoute=${state.smartAiRoutingEnabled}")
            appendLine("Mode: AIPro=${state.aiProOnly}, Gaming=${state.gamingOnly}, GPS=${state.gpsOverrideActive}, AppSplit=${state.appSplitMode}(${state.customSplitPackages.size})")
            appendLine("Servers Count: ${state.servers.size} | Subs Count: ${state.subscriptions.size}")
        }
    }

    fun loadInstalledUserApps(context: Context): List<Pair<String, String>> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val myPkg = context.packageName
        return runCatching {
            pm.queryIntentActivities(intent, 0)
                .mapNotNull { ri ->
                    val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                    if (pkg == myPkg) return@mapNotNull null
                    val label = ri.loadLabel(pm)?.toString()?.trim().orEmpty().ifBlank { pkg }
                    pkg to label
                }
                .distinctBy { it.first }
                .sortedBy { it.second.lowercase() }
        }.getOrDefault(emptyList())
    }
}
