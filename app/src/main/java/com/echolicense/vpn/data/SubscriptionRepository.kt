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
                .header("User-Agent", "EcholicenseVPN/1.0 (v2rayNG compatible)")
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
            val kv = part.trim().split("=")
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
            val endpointMatch = Regex("""Endpoint\s*=\s*([^\s:]+):(\d+)""").find(trimmed)
            val host = endpointMatch?.groupValues?.getOrNull(1) ?: "wireguard"
            val port = endpointMatch?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 51820
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
        if (raw.contains("://")) return raw
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

    private fun parseUriProfile(line: String, protocol: VpnProtocol): ServerProfile? {
        return try {
            val uri = URI(line)
            val name = uri.fragment?.let { URLDecoder.decode(it, "UTF-8") } ?: "${protocol.name} Server"
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
            val b64 = line.removePrefix("vmess://")
            val json = JSONObject(String(Base64.decode(b64, Base64.DEFAULT)))
            ServerProfile(
                id = UUID.randomUUID().toString(),
                name = json.optString("ps", "VMess Server"),
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
            val uri = URI(line)
            val privateKey = URLDecoder.decode(uri.userInfo ?: return null, "UTF-8")
            val host = uri.host ?: return null
            val port = if (uri.port > 0) uri.port else 51820
            val name = uri.fragment?.let { URLDecoder.decode(it, "UTF-8") } ?: "WireGuard ($host)"

            val params = (uri.rawQuery ?: "").split("&").mapNotNull { kv ->
                val parts = kv.split("=", limit = 2)
                if (parts.size == 2) parts[0].lowercase() to URLDecoder.decode(parts[1], "UTF-8") else null
            }.toMap()

            val address = params["address"] ?: "10.0.0.2/32"
            val publicKey = params["publickey"] ?: return null
            val mtu = params["mtu"] ?: "1280"
            val psk = params["presharedkey"]

            val wgConf = buildString {
                appendLine("[Interface]")
                appendLine("PrivateKey = $privateKey")
                appendLine("Address = $address")
                appendLine("MTU = $mtu")
                appendLine()
                appendLine("[Peer]")
                appendLine("PublicKey = $publicKey")
                if (!psk.isNullOrBlank()) appendLine("PresharedKey = $psk")
                appendLine("Endpoint = $host:$port")
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

    suspend fun pingAllServers(servers: List<ServerProfile>): List<ServerProfile> =
        withContext(Dispatchers.IO) {
            servers.map { srv ->
                async {
                    val ms = measureTcpPing(srv.host, srv.port)
                    srv.copy(pingMs = ms)
                }
            }.awaitAll().sortedBy { if (it.pingMs < 0) Int.MAX_VALUE else it.pingMs }
        }

    private fun measureTcpPing(host: String, port: Int, timeoutMs: Int = 1800): Int {
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
