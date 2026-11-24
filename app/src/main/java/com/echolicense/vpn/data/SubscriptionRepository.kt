package com.echolicense.vpn.data

import android.content.Context
import android.util.Base64
import com.echolicense.vpn.core.VpnStateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

class SubscriptionRepository {

    private val ipv4SystemDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val all = Dns.SYSTEM.lookup(hostname)
            val v4 = all.filterIsInstance<Inet4Address>()
            return if (v4.isNotEmpty()) v4 + all.filterNot { it is Inet4Address } else all
        }
    }

    // دور زدن DNS مسموم یا کندِ مودم‌های مخابرات از طریق DoH مستقیم روی IP
    private val dohBootstrapClient = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    private val dohIpv4Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val resolved = resolveViaDoh(hostname)
            if (resolved.isNotEmpty()) return resolved
            return ipv4SystemDns.lookup(hostname)
        }
    }

    private fun resolveViaDoh(hostname: String): List<InetAddress> {
        val enc = runCatching { URLEncoder.encode(hostname, "UTF-8") }.getOrNull() ?: return emptyList()
        val endpoints = listOf(
            "https://1.1.1.1/dns-query?name=$enc&type=A",
            "https://1.0.0.1/dns-query?name=$enc&type=A",
            "https://8.8.8.8/resolve?name=$enc&type=A"
        )
        for (url in endpoints) {
            val ips = runCatching {
                val req = Request.Builder()
                    .url(url)
                    .header("Accept", "application/dns-json")
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
                dohBootstrapClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use emptyList()
                    val json = JSONObject(resp.body?.string().orEmpty())
                    val ans = json.optJSONArray("Answer") ?: return@use emptyList()
                    val list = mutableListOf<InetAddress>()
                    for (i in 0 until ans.length()) {
                        val rec = ans.getJSONObject(i)
                        if (rec.optInt("type") == 1) {
                            val ipStr = rec.optString("data").trim()
                            if (ipStr.isNotEmpty()) {
                                list.add(InetAddress.getByName(ipStr))
                            }
                        }
                    }
                    list
                }
            }.getOrDefault(emptyList())
            if (ips.isNotEmpty()) return ips
        }
        return emptyList()
    }

    private fun buildSubClient(dns: Dns?, proxyPort: Int = 0, useHttp11: Boolean = true): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(16, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
        if (useHttp11) {
            b.protocols(listOf(Protocol.HTTP_1_1))
        }
        if (dns != null) {
            b.dns(dns)
        }
        if (proxyPort > 0) {
            b.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort)))
        }
        return b.build()
    }

    private fun executeSubCall(
        client: OkHttpClient,
        subUrl: String,
        context: Context?
    ): Pair<SubscriptionInfo, List<ServerProfile>> {
        val reqBuilder = Request.Builder()
            .url(subUrl.trim())
            .header("User-Agent", "v2rayNG/1.9.15")
            .header("Accept", "*/*")
            .header("Cache-Control", "no-cache")

        if (context != null) {
            DeviceInfoProvider.getDeviceHeaders(context).forEach { (k, v) ->
                reqBuilder.header(k, v)
            }
        }

        client.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("کد خطای سرور: ${resp.code}")
            }

            val userInfoHeader = extractUserInfoFromResponseChain(resp)
            val bodyRaw = resp.body?.string()?.trim().orEmpty()
            val decodedText = tryDecodeBase64(bodyRaw)

            val subInfo = if (userInfoHeader.isNotBlank()) {
                parseUserInfoHeader(subUrl, userInfoHeader)
            } else {
                extractUserInfoFromBody(subUrl, decodedText)
            }

            val profiles = parseServerLines(decodedText)
            if (profiles.isEmpty()) {
                throw IllegalStateException("پاسخ دریافت شد اما کانفیگی در آن نبود")
            }
            return subInfo to profiles
        }
    }

    suspend fun fetchSubscription(
        subUrl: String,
        context: Context? = null
    ): Pair<SubscriptionInfo, List<ServerProfile>> = withContext(Dispatchers.IO) {
        val probePort = VpnStateManager.state.value.activeProbePort
        val attempts = buildList {
            // 1. مستقیم با اولویت IPv4 و HTTP/1.1
            add(buildSubClient(dns = ipv4SystemDns, proxyPort = 0, useHttp11 = true))
            // 2. مستقیم با DoH مستقل (دور زدن DNS مخابرات / فیبر نوری)
            add(buildSubClient(dns = dohIpv4Dns, proxyPort = 0, useHttp11 = true))
            // 3. در صورت روشن بودن VPN، عبور از داخل تونل VPN
            if (probePort > 0) {
                add(buildSubClient(dns = null, proxyPort = probePort, useHttp11 = true))
            }
            // 4. کلاینت پیش‌فرض با ALPN کامل (H2 + HTTP/1.1)
            add(buildSubClient(dns = dohIpv4Dns, proxyPort = 0, useHttp11 = false))
        }

        var lastError: Throwable? = null
        for (client in attempts) {
            try {
                return@withContext executeSubCall(client, subUrl, context)
            } catch (t: Throwable) {
                lastError = t
            }
        }

        val reason = lastError?.message?.takeIf { it.isNotBlank() }
            ?: lastError?.javaClass?.simpleName
            ?: "Timeout"
        throw IllegalStateException("خطا در دریافت اشتراک ($reason)")
    }

    private fun extractUserInfoFromResponseChain(response: Response): String {
        var current: Response? = response
        while (current != null) {
            val h = current.headers
            for (i in 0 until h.size) {
                val name = h.name(i).lowercase()
                if (name == "subscription-userinfo" || name == "x-subscription-userinfo") {
                    val v = h.value(i).trim()
                    if (v.isNotBlank()) return v
                }
            }
            current = current.priorResponse
        }
        return ""
    }

    private fun extractUserInfoFromBody(subUrl: String, decodedText: String): SubscriptionInfo {
        val match = Regex("""upload\s*=\s*\d+.*total\s*=\s*\d+""", RegexOption.IGNORE_CASE).find(decodedText)
        if (match != null) {
            return parseUserInfoHeader(subUrl, match.value)
        }
        return SubscriptionInfo(subUrl = subUrl, hasUserInfo = false)
    }

    fun parseUserInfoHeader(subUrl: String, header: String): SubscriptionInfo {
        var upload = 0L
        var download = 0L
        var total = 0L
        var expire = 0L
        var foundAny = false

        header.split(";").forEach { part ->
            val kv = part.trim().split("=", limit = 2)
            if (kv.size == 2) {
                val rawVal = kv[1].trim()
                val value = rawVal.toLongOrNull() ?: rawVal.toDoubleOrNull()?.toLong() ?: 0L
                when (kv[0].trim().lowercase()) {
                    "upload" -> { upload = value; foundAny = true }
                    "download" -> { download = value; foundAny = true }
                    "total" -> { total = value; foundAny = true }
                    "expire" -> { expire = value; foundAny = true }
                }
            }
        }
        return SubscriptionInfo(
            subUrl = subUrl,
            uploadBytes = upload,
            downloadBytes = download,
            totalBytes = total,
            expireTimestampSec = expire,
            hasUserInfo = foundAny
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
                    val timeout = if (srv.protocol == VpnProtocol.WIREGUARD) 900 else 1800
                    srv.copy(pingMs = measureTcpPing(srv.host, srv.port, timeout))
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
