package com.echolicense.vpn.core

import android.util.Base64
import com.echolicense.vpn.data.DnsProvider
import com.echolicense.vpn.data.MuxConfig
import com.echolicense.vpn.data.ServerProfile
import com.echolicense.vpn.data.VpnProtocol
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

object XrayConfigBuilder {

    private val IPV4_REGEX = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    fun buildConfigJson(
        profile: ServerProfile,
        primaryDns: DnsProvider,
        enableIranSplit: Boolean,
        mtu: Int,
        muxConfig: MuxConfig,
        hasGeoFiles: Boolean
    ): String {
        val root = JSONObject()
        root.put("log", JSONObject().put("loglevel", "warning"))

        val dnsServers = JSONArray()
        if (profile.host.isNotBlank() && !IPV4_REGEX.matches(profile.host) && !profile.host.contains(":")) {
            dnsServers.put(
                JSONObject()
                    .put("address", "localhost")
                    .put("domains", JSONArray().put("full:${profile.host}"))
            )
        }
        if (enableIranSplit) {
            val irDomains = JSONArray().put("domain:ir")
            if (hasGeoFiles) irDomains.put("geosite:ir")
            dnsServers.put(
                JSONObject()
                    .put("address", SmartDnsManager.IRAN_DIRECT_DNS)
                    .put("domains", irDomains)
            )
        }

        val addedDns = linkedSetOf<String>()
        if (primaryDns.dohUrl.isNotBlank()) addedDns.add(primaryDns.dohUrl)
        if (primaryDns.ipv4.isNotBlank()) addedDns.add(primaryDns.ipv4)
        addedDns.add("8.8.8.8")
        addedDns.add("localhost")
        addedDns.forEach { dnsServers.put(it) }

        root.put(
            "dns",
            JSONObject()
                .put("servers", dnsServers)
                .put("queryStrategy", "UseIPv4")
        )

        val safeMtu = mtu.coerceIn(1200, 1500)
        val tunInbound = JSONObject()
            .put("tag", "tun")
            .put("port", 0)
            .put("protocol", "tun")
            .put(
                "settings",
                JSONObject()
                    .put("name", "xray0")
                    .put("mtu", safeMtu)
                    .put("userLevel", 8)
            )
            .put(
                "sniffing",
                JSONObject()
                    .put("enabled", true)
                    .put("destOverride", JSONArray().put("http").put("tls").put("quic"))
                    .put("routeOnly", false)
            )
        root.put("inbounds", JSONArray().put(tunInbound))

        val outbounds = JSONArray()
        val proxyOutbound = when (profile.protocol) {
            VpnProtocol.WIREGUARD -> buildWireGuardOutbound(profile, safeMtu)
            VpnProtocol.VMESS -> buildVmessOutbound(profile)
            VpnProtocol.VLESS, VpnProtocol.TROJAN -> buildUriOutbound(profile, muxConfig.enabled)
        }

        // اعمال سازوکار Mux Mode روی VLESS / VMess / Trojan (WireGuard از Mux پشتیبانی نمی‌کند)
        if (muxConfig.enabled && profile.protocol != VpnProtocol.WIREGUARD) {
            proxyOutbound.put(
                "mux",
                JSONObject()
                    .put("enabled", true)
                    .put("concurrency", muxConfig.concurrency.coerceIn(1, 64))
                    .put("xudpConcurrency", muxConfig.xudpConcurrency.coerceIn(1, 64))
                    .put("xudpProxyUDP443", muxConfig.xudpProxyUDP443)
            )
        }

        outbounds.put(proxyOutbound)
        outbounds.put(
            JSONObject()
                .put("tag", "direct")
                .put("protocol", "freedom")
                .put("settings", JSONObject().put("domainStrategy", "UseIPv4"))
        )
        outbounds.put(JSONObject().put("tag", "dns-out").put("protocol", "dns"))
        outbounds.put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
        root.put("outbounds", outbounds)

        val rules = JSONArray()
        rules.put(
            JSONObject()
                .put("type", "field")
                .put("inboundTag", JSONArray().put("tun"))
                .put("port", 53)
                .put("outboundTag", "dns-out")
        )

        val privateIps = if (hasGeoFiles) {
            JSONArray().put("geoip:private")
        } else {
            JSONArray()
                .put("10.0.0.0/8")
                .put("127.0.0.0/8")
                .put("172.16.0.0/12")
                .put("192.168.0.0/16")
        }
        rules.put(
            JSONObject()
                .put("type", "field")
                .put("ip", privateIps)
                .put("outboundTag", "direct")
        )

        if (enableIranSplit) {
            val irDomains = JSONArray().put("domain:ir")
            if (hasGeoFiles) irDomains.put("geosite:ir")
            rules.put(
                JSONObject()
                    .put("type", "field")
                    .put("domain", irDomains)
                    .put("outboundTag", "direct")
            )
            if (hasGeoFiles) {
                rules.put(
                    JSONObject()
                        .put("type", "field")
                        .put("ip", JSONArray().put("geoip:ir"))
                        .put("outboundTag", "direct")
                )
            }
        }

        root.put(
            "routing",
            JSONObject()
                .put("domainStrategy", "IPIfNonMatch")
                .put("rules", rules)
        )

        return root.toString(2)
    }

    private fun buildWireGuardOutbound(profile: ServerProfile, mtu: Int): JSONObject {
        var section = ""
        var secretKey = ""
        val addresses = mutableListOf<String>()
        var publicKey = ""
        var preSharedKey = ""
        var endpoint = ""
        var keepAlive = 25
        var reservedRaw = ""

        for (rawLine in profile.rawConfig.lines()) {
            val line = rawLine.substringBefore("#").substringBefore(";").trim()
            if (line.isEmpty()) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.lowercase()
                continue
            }
            val parts = line.split("=", limit = 2)
            if (parts.size != 2) continue
            val key = parts[0].trim().lowercase()
            val value = parts[1].trim()

            when (section) {
                "[interface]" -> when (key) {
                    "privatekey" -> secretKey = value
                    "address" -> value.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach(addresses::add)
                    "reserved" -> reservedRaw = value
                }
                "[peer]" -> when (key) {
                    "publickey" -> publicKey = value
                    "presharedkey" -> preSharedKey = value
                    "endpoint" -> endpoint = value
                    "persistentkeepalive" -> keepAlive = value.toIntOrNull() ?: 25
                    "reserved" -> if (reservedRaw.isBlank()) reservedRaw = value
                }
            }
        }

        if (secretKey.isBlank() || publicKey.isBlank()) {
            throw IllegalArgumentException("کلید PrivateKey یا PublicKey در کانفیگ WireGuard ناقص است.")
        }
        if (endpoint.isBlank()) {
            endpoint = if (profile.host.contains(":")) "[${profile.host}]:${profile.port}" else "${profile.host}:${profile.port}"
        }
        if (addresses.isEmpty()) addresses.add("10.0.0.2/32")

        val peer = JSONObject()
            .put("publicKey", publicKey)
            .put("endpoint", endpoint)
            .put("keepAlive", keepAlive.coerceAtLeast(10))
            .put("allowedIPs", JSONArray().put("0.0.0.0/0").put("::/0"))
        if (preSharedKey.isNotBlank()) peer.put("preSharedKey", preSharedKey)

        val settings = JSONObject()
            .put("secretKey", secretKey)
            .put("address", JSONArray(addresses))
            .put("peers", JSONArray().put(peer))
            .put("mtu", mtu)
            .put("kernelMode", false)
            .put("domainStrategy", "ForceIPv4")

        parseReservedBytes(reservedRaw)?.let { bytes ->
            settings.put("reserved", JSONArray(bytes))
        }

        return JSONObject()
            .put("tag", "proxy")
            .put("protocol", "wireguard")
            .put("settings", settings)
    }

    private fun parseReservedBytes(raw: String): List<Int>? {
        val cleaned = raw.trim().removePrefix("[").removeSuffix("]").trim()
        if (cleaned.isEmpty()) return null
        if (cleaned.contains(",")) {
            val nums = cleaned.split(",").mapNotNull { it.trim().toIntOrNull() }
            if (nums.size == 3 && nums.all { it in 0..255 }) return nums
        }
        return try {
            val bytes = Base64.decode(cleaned, Base64.DEFAULT)
            if (bytes.size == 3) bytes.map { it.toInt() and 0xFF } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun buildVmessOutbound(profile: ServerProfile): JSONObject {
        val b64 = profile.rawConfig.removePrefix("vmess://").trim()
        val vmess = JSONObject(String(Base64.decode(b64, Base64.DEFAULT)))
        val network = vmess.optString("net", "tcp")
        val tls = vmess.optString("tls", "none")
        val hostHeader = vmess.optString("host", profile.host)
        val path = vmess.optString("path", "/")
        val sni = vmess.optString("sni", hostHeader).ifBlank { profile.host }

        val streamSettings = JSONObject()
            .put("network", network)
            .put("security", if (tls == "tls") "tls" else "none")

        if (tls == "tls") {
            streamSettings.put(
                "tlsSettings",
                JSONObject()
                    .put("serverName", sni)
                    .put("fingerprint", vmess.optString("fp", "chrome").ifBlank { "chrome" })
                    .put("allowInsecure", false)
            )
        }
        when (network) {
            "ws" -> streamSettings.put(
                "wsSettings",
                JSONObject().put("path", path).put("headers", JSONObject().put("Host", hostHeader))
            )
            "grpc" -> streamSettings.put("grpcSettings", JSONObject().put("serviceName", path))
            "httpupgrade" -> streamSettings.put(
                "httpupgradeSettings",
                JSONObject().put("path", path).put("host", hostHeader)
            )
            "xhttp", "splithttp" -> streamSettings.put(
                "xhttpSettings",
                JSONObject().put("path", path).put("host", hostHeader)
            )
        }

        val user = JSONObject()
            .put("id", vmess.optString("id"))
            .put("alterId", vmess.optInt("aid", 0))
            .put("security", vmess.optString("scy", "auto"))

        return JSONObject()
            .put("tag", "proxy")
            .put("protocol", "vmess")
            .put("streamSettings", streamSettings)
            .put(
                "settings",
                JSONObject().put(
                    "vnext",
                    JSONArray().put(
                        JSONObject()
                            .put("address", profile.host)
                            .put("port", profile.port)
                            .put("users", JSONArray().put(user))
                    )
                )
            )
    }

    private fun buildUriOutbound(profile: ServerProfile, muxEnabled: Boolean): JSONObject {
        val baseUri = profile.rawConfig.substringBefore("#").replace(" ", "%20")
        val uri = URI(baseUri)
        val params = (uri.rawQuery ?: "").split("&").mapNotNull { kv ->
            val p = kv.split("=", limit = 2)
            if (p.size == 2) {
                val v = runCatching { URLDecoder.decode(p[1], "UTF-8") }.getOrDefault(p[1])
                p[0] to v
            } else null
        }.toMap()

        val network = params["type"] ?: "tcp"
        val security = params["security"] ?: "none"
        val userSecret = runCatching { URLDecoder.decode(uri.userInfo ?: "", "UTF-8") }.getOrDefault(uri.userInfo ?: "")

        val streamSettings = JSONObject()
            .put("network", network)
            .put("security", security)

        if (security == "reality") {
            streamSettings.put(
                "realitySettings",
                JSONObject()
                    .put("serverName", params["sni"] ?: "")
                    .put("fingerprint", params["fp"] ?: "chrome")
                    .put("publicKey", params["pbk"] ?: "")
                    .put("shortId", params["sid"] ?: "")
                    .put("spiderX", params["spx"] ?: "/")
            )
        } else if (security == "tls") {
            val tlsObj = JSONObject()
                .put("serverName", params["sni"] ?: profile.host)
                .put("fingerprint", params["fp"] ?: "chrome")
                .put("allowInsecure", params["allowInsecure"] == "1" || params["insecure"] == "1")
            params["alpn"]?.takeIf { it.isNotBlank() }?.let { rawAlpn ->
                val arr = JSONArray()
                rawAlpn.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach(arr::put)
                if (arr.length() > 0) tlsObj.put("alpn", arr)
            }
            streamSettings.put("tlsSettings", tlsObj)
        }

        when (network) {
            "ws" -> streamSettings.put(
                "wsSettings",
                JSONObject()
                    .put("path", params["path"] ?: "/")
                    .put("headers", JSONObject().put("Host", params["host"] ?: profile.host))
            )
            "grpc" -> streamSettings.put(
                "grpcSettings",
                JSONObject()
                    .put("serviceName", params["serviceName"] ?: "")
                    .put("multiMode", params["mode"] == "multi")
            )
            "httpupgrade" -> streamSettings.put(
                "httpupgradeSettings",
                JSONObject()
                    .put("path", params["path"] ?: "/")
                    .put("host", params["host"] ?: profile.host)
            )
            "xhttp", "splithttp" -> streamSettings.put(
                "xhttpSettings",
                JSONObject()
                    .put("path", params["path"] ?: "/")
                    .put("host", params["host"] ?: profile.host)
                    .put("mode", params["mode"] ?: "auto")
            )
        }

        val outbound = JSONObject()
            .put("tag", "proxy")
            .put("streamSettings", streamSettings)

        if (profile.protocol == VpnProtocol.TROJAN) {
            outbound.put("protocol", "trojan").put(
                "settings",
                JSONObject().put(
                    "servers",
                    JSONArray().put(
                        JSONObject()
                            .put("address", profile.host)
                            .put("port", profile.port)
                            .put("password", userSecret)
                    )
                )
            )
        } else {
            val user = JSONObject()
                .put("id", userSecret)
                .put("encryption", params["encryption"] ?: "none")
            val rawFlow = params["flow"]?.trim().orEmpty()
            // در Xray-core ترکیب xtls-rprx-vision با mux مجاز نیست؛ هنگام فعال بودن Mux فیلد flow حذف می‌شود
            if (rawFlow.isNotBlank() && !(muxEnabled && rawFlow.contains("vision", ignoreCase = true))) {
                user.put("flow", rawFlow)
            }

            outbound.put("protocol", "vless").put(
                "settings",
                JSONObject().put(
                    "vnext",
                    JSONArray().put(
                        JSONObject()
                            .put("address", profile.host)
                            .put("port", profile.port)
                            .put("users", JSONArray().put(user))
                    )
                )
            )
        }
        return outbound
    }
}
