package com.echolicense.vpn.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.TimeUnit

class SubscriptionRepository {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun fetchSubscription(subUrl: String): Pair<SubscriptionInfo, List<ServerProfile>> =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(subUrl.trim())
                .header("User-Agent", "EcholicenseVPN/1.1 (v2rayNG compatible)")
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("خطای دریافت اشتراک (کد ${resp.code})")
                }

                val userInfoHeader = resp.header("Subscription-Userinfo")
                    ?: resp.header("subscription-userinfo")
                    ?: ""
                val subInfo = parseUserInfoHeader(subUrl, userInfoHeader)

                val bodyRaw = resp.body?.string()?.trim().orEmpty()
                val decodedText = tryDecodeBase64(bodyRaw)
                val profiles = parseServerLines(decodedText)
                subInfo to profiles
            }
        }

    fun parseUserInfoHeader(subUrl: String, header: String): SubscriptionInfo {
        var upload = 0L
        var download = 0L
        var total = 0L
        var expire = 0L

        header.split(";").forEach { part ->
            val kv = part.trim().split("=", limit = 2)
            if (kv.size == 2) {
                val value = kv[1].trim().toLongOrNull() ?: 0L
                when (kv[0].trim().lowercase()) {
                    "upload" -> upload = value
                    "download" -> download = value
                    "total" -> total = value
                    "expire" -> expire = value
                }
            }
        }
        return SubscriptionInfo(
            subUrl = subUrl,
            uploadBytes = upload,
            downloadBytes = download,
            totalBytes = total,
            expireTimestampSec = expire
        )
    }

    fun parseSingleInput(input: String): List<ServerProfile> {
        val trimmed = input.trim()
        if (trimmed.contains("[Interface]", ignoreCase = true) &&
            trimmed.contains("[Peer]", ignoreCase = true)
        ) {
            val endpointLine = trimmed.lines()
                .map { it.substringBefore("#").substringBefore(";").trim() }
                .firstOrNull { it.startsWith("Endpoint", ignoreCase = true) }
            val endpointVal = endpointLine?.split("=", limit = 2)?.getOrNull(1)?.trim().orEmpty()
            val (host, port) = parseEndpointHostPort(endpointVal)
            return listOf(
                ServerProfile(
                    id = UUID.randomUUID().toString(),
                    name = "WireGuard ($host)",
                    protocol = VpnProtocol.WIREGUARD,
                    rawConfig = trimmed,
                    host = host,
                    port = port
                )
            )
        }
        return parseServerLines(tryDecodeBase64(trimmed))
    }

    private fun tryDecodeBase64(raw: String): String {
        if (raw.contains("://") || raw.contains("[Interface]", ignoreCase = true)) return raw
        return try {
            val normalized = raw.replace("\n", "").replace("\r", "")
            String(Base64.decode(normalized, Base64.DEFAULT), Charsets.UTF_8)
        } catch (_: Exception) {
            raw
        }
    }

    private fun parseServerLines(text: String): List<ServerProfile> {
        val result = mutableListOf<ServerProfile>()
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            when {
                line.startsWith("vless://", true) -> parseUriProfile(line, VpnProtocol.VLESS)?.let(result::add)
                line.startsWith("trojan://", true) -> parseUriProfile(line, VpnProtocol.TROJAN)?.let(result::add)
                line.startsWith("vmess://", true) -> parseVmessProfile(line)?.let(result::add)
                line.startsWith("wireguard://", true) -> parseWireGuardUri(line)?.let(result::add)
            }
        }
        return result
    }

    private fun splitFragment(line: String): Pair<String, String?> {
        val idx = line.indexOf('#')
        if (idx < 0) return line to null
        return line.substring(0, idx) to line.substring(idx + 1)
    }

    private fun safeDecode(text: String?): String? {
        if (text.isNullOrBlank()) return null
        return runCatching { URLDecoder.decode(text, "UTF-8") }.getOrDefault(text)
    }

    private fun parseUriProfile(line: String, protocol: VpnProtocol): ServerProfile? {
        return try {
            val (base, frag) = splitFragment(line)
            val uri = URI(base.replace(" ", "%20"))
            val name = safeDecode(frag)?.ifBlank { null } ?: "${protocol.name} Server"
            ServerProfile(
                id = UUID.randomUUID().toString(),
                name = name,
                protocol = protocol,
                rawConfig = line,
                host = uri.host ?: return null,
                port = if (uri.port > 0) uri.port else 443
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseVmessProfile(line: String): ServerProfile? {
        return try {
            val b64 = line.removePrefix("vmess://").trim()
            val json = JSONObject(String(Base64.decode(b64, Base64.DEFAULT)))
            ServerProfile(
                id = UUID.randomUUID().toString(),
                name = json.optString("ps", "VMess Server").ifBlank { "VMess Server" },
                protocol = VpnProtocol.VMESS,
                rawConfig = line,
                host = json.optString("add"),
                port = json.optInt("port", 443)
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseWireGuardUri(line: String): ServerProfile? {
        return try {
            val (base, frag) = splitFragment(line)
            val withoutScheme = base.removePrefix("wireguard://").removePrefix("WIREGUARD://")
            val atIdx = withoutScheme.indexOf('@')
            if (atIdx <= 0) return null

            val privateKey = safeDecode(withoutScheme.substring(0, atIdx))?.trim() ?: return null
            val rest = withoutScheme.substring(atIdx + 1)
            val qIdx = rest.indexOf('?')
            val hostPortRaw = (if (qIdx >= 0) rest.substring(0, qIdx) else rest).trimEnd('/')
            val rawQuery = if (qIdx >= 0) rest.substring(qIdx + 1) else ""

            val (host, port) = parseEndpointHostPort(hostPortRaw)
            if (host.isBlank()) return null

            val name = safeDecode(frag)?.ifBlank { null } ?: "WireGuard ($host)"
            val params = rawQuery.split("&").mapNotNull { kv ->
                val parts = kv.split("=", limit = 2)
                if (parts.size == 2) parts[0].lowercase() to (safeDecode(parts[1]) ?: parts[1]) else null
            }.toMap()

            val address = params["address"]?.ifBlank { null } ?: "10.0.0.2/32"
            val publicKey = params["publickey"]?.trim() ?: return null
            val mtu = params["mtu"]?.ifBlank { null } ?: "1280"
            val psk = params["presharedkey"]?.trim()
            val reserved = params["reserved"]?.trim()

            val endpointFormatted = if (host.contains(":")) "[$host]:$port" else "$host:$port"
            val wgConf = buildString {
                appendLine("[Interface]")
                appendLine("PrivateKey = $privateKey")
                appendLine("Address = $address")
                appendLine("MTU = $mtu")
                if (!reserved.isNullOrBlank()) appendLine("Reserved = $reserved")
                appendLine()
                appendLine("[Peer]")
                appendLine("PublicKey = $publicKey")
                if (!psk.isNullOrBlank()) appendLine("PresharedKey = $psk")
                appendLine("Endpoint = $endpointFormatted")
                appendLine("AllowedIPs = 0.0.0.0/0, ::/0")
                appendLine("PersistentKeepalive = 25")
            }

            ServerProfile(
                id = UUID.randomUUID().toString(),
                name = name,
                protocol = VpnProtocol.WIREGUARD,
                rawConfig = wgConf,
                host = host,
                port = port
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseEndpointHostPort(endpoint: String): Pair<String, Int> {
        val clean = endpoint.trim()
        if (clean.startsWith("[")) {
            val closeIdx = clean.indexOf(']')
            if (closeIdx > 1) {
                val h = clean.substring(1, closeIdx)
                val p = clean.substring(closeIdx + 1).removePrefix(":").toIntOrNull() ?: 51820
                return h to p
            }
        }
        val lastColon = clean.lastIndexOf(':')
        if (lastColon > 0) {
            val h = clean.substring(0, lastColon).trim()
            val p = clean.substring(lastColon + 1).trim().toIntOrNull() ?: 51820
            return h to p
        }
        return clean.ifBlank { "wireguard" } to 51820
    }

    suspend fun pingAllServers(servers: List<ServerProfile>): List<ServerProfile> =
        withContext(Dispatchers.IO) {
            servers.map { srv ->
                async {
                    val ms = if (srv.protocol == VpnProtocol.WIREGUARD) {
                        // پورت UDP وایرگارد به TCP پاسخ نمی‌دهد؛ برای جلوگیری از خطا پینگ 1ms نمایش داده نمی‌شود
                        measureTcpPing(srv.host, srv.port, 900)
                    } else {
                        measureTcpPing(srv.host, srv.port, 1800)
                    }
                    srv.copy(pingMs = ms)
                }
            }.awaitAll().sortedBy { if (it.pingMs < 0) Int.MAX_VALUE else it.pingMs }
        }

    private fun measureTcpPing(host: String, port: Int, timeoutMs: Int): Int {
        return try {
            val start = System.currentTimeMillis()
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            }
            (System.currentTimeMillis() - start).toInt()
        } catch (_: Exception) {
            -1
        }
    }
}
