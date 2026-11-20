package com.echolicense.vpn.core

import android.util.Base64
import com.echolicense.vpn.data.DnsProvider
import com.echolicense.vpn.data.ServerProfile
import com.echolicense.vpn.data.VpnProtocol
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

object XrayConfigBuilder {

    fun buildConfigJson(
        profile: ServerProfile,
        primaryDns: DnsProvider,
        enableIranSplit: Boolean,
        enableFragment: Boolean,
        socksPort: Int = 10808
    ): String {
        val root = JSONObject()
        root.put("log", JSONObject().put("loglevel", "warning"))

        // DNS هوشمند با Failover و تفکیک دامنه‌های ایرانی
        val dnsServers = JSONArray()
        dnsServers.put(primaryDns.dohUrl)
        dnsServers.put(primaryDns.ipv4)
        dnsServers.put("https://dns.quad9.net/dns-query")
        if (enableIranSplit) {
            dnsServers.put(
                JSONObject()
                    .put("address", SmartDnsManager.IRAN_DIRECT_DNS)
                    .put("domains", JSONArray().put("geosite:ir").put("domain:ir"))
                    .put("expectIPs", JSONArray().put("geoip:ir"))
            )
        }
        root.put(
            "dns",
            JSONObject()
                .put("servers", dnsServers)
                .put("queryStrategy", "UseIPv4")
        )

        val inbound = JSONObject()
            .put("tag", "socks")
            .put("port", socksPort)
            .put("listen", "127.0.0.1")
            .put("protocol", "socks")
            .put("settings", JSONObject().put("auth", "noauth").put("udp", true))
            .put(
                "sniffing",
                JSONObject()
                    .put("enabled", true)
                    .put("destOverride", JSONArray().put("http").put("tls").put("quic"))
                    .put("routeOnly", true)
            )
        root.put("inbounds", JSONArray().put(inbound))

        val outbounds = JSONArray()
        val proxyOutbound = if (profile.protocol == VpnProtocol.VMESS) {
            buildVmessOutbound(profile, enableFragment)
        } else {
            buildUriOutbound(profile, enableFragment)
        }
        outbounds.put(proxyOutbound)

        if (enableFragment) {
            outbounds.put(
                JSONObject()
                    .put("tag", "fragment")
                    .put("protocol", "freedom")
                    .put(
                        "settings",
                        JSONObject().put(
                            "fragment",
                            JSONObject()
                                .put("packets", "tlshello")
                                .put("length", "10-50")
                                .put("interval", "10-20")
                        )
                    )
            )
        }

        outbounds.put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
        outbounds.put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
        root.put("outbounds", outbounds)

        val rules = JSONArray()
        rules.put(
            JSONObject()
                .put("type", "field")
                .put("ip", JSONArray().put("geoip:private"))
                .put("outboundTag", "direct")
        )
        if (enableIranSplit) {
            rules.put(
                JSONObject()
                    .put("type", "field")
                    .put("domain", JSONArray().put("geosite:ir").put("regexp:.*\\.ir$"))
                    .put("outboundTag", "direct")
            )
            rules.put(
                JSONObject()
                    .put("type", "field")
                    .put("ip", JSONArray().put("geoip:ir"))
                    .put("outboundTag", "direct")
            )
        }
        rules.put(
            JSONObject()
                .put("type", "field")
                .put("domain", JSONArray().put("geosite:category-ads-all"))
                .put("outboundTag", "block")
        )

        root.put(
            "routing",
            JSONObject()
                .put("domainStrategy", "IPIfNonMatch")
                .put("rules", rules)
        )

        return root.toString(2)
    }

    private fun buildVmessOutbound(profile: ServerProfile, enableFragment: Boolean): JSONObject {
        val b64 = profile.rawConfig.removePrefix("vmess://")
        val vmess = JSONObject(String(Base64.decode(b64, Base64.DEFAULT)))
        val network = vmess.optString("net", "tcp")
        val tls = vmess.optString("tls", "none")
        val hostHeader = vmess.optString("host", profile.host)
        val path = vmess.optString("path", "/")
        val sni = vmess.optString("sni", hostHeader)

        val streamSettings = JSONObject()
            .put("network", network)
            .put("security", if (tls == "tls") "tls" else "none")

        if (tls == "tls") {
            streamSettings.put(
                "tlsSettings",
                JSONObject().put("serverName", sni).put("fingerprint", "chrome")
            )
        }
        if (network == "ws") {
            streamSettings.put(
                "wsSettings",
                JSONObject().put("path", path).put("headers", JSONObject().put("Host", hostHeader))
            )
        } else if (network == "grpc") {
            streamSettings.put("grpcSettings", JSONObject().put("serviceName", path))
        }
        if (enableFragment) {
            streamSettings.put("sockopt", JSONObject().put("dialerProxy", "fragment"))
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

    private fun buildUriOutbound(profile: ServerProfile, enableFragment: Boolean): JSONObject {
        val uri = URI(profile.rawConfig)
        val params = (uri.rawQuery ?: "").split("&").mapNotNull { kv ->
            val p = kv.split("=", limit = 2)
            if (p.size == 2) p[0] to URLDecoder.decode(p[1], "UTF-8") else null
        }.toMap()

        val network = params["type"] ?: "tcp"
        val security = params["security"] ?: "none"

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
            streamSettings.put(
                "tlsSettings",
                JSONObject()
                    .put("serverName", params["sni"] ?: profile.host)
                    .put("fingerprint", params["fp"] ?: "chrome")
            )
        }

        if (network == "ws") {
            streamSettings.put(
                "wsSettings",
                JSONObject()
                    .put("path", params["path"] ?: "/")
                    .put("headers", JSONObject().put("Host", params["host"] ?: profile.host))
            )
        } else if (network == "grpc") {
            streamSettings.put(
                "grpcSettings",
                JSONObject().put("serviceName", params["serviceName"] ?: "")
            )
        }

        if (enableFragment) {
            streamSettings.put("sockopt", JSONObject().put("dialerProxy", "fragment"))
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
                            .put("password", uri.userInfo)
                    )
                )
            )
        } else {
            val user = JSONObject()
                .put("id", uri.userInfo)
                .put("encryption", params["encryption"] ?: "none")
            params["flow"]?.takeIf { it.isNotBlank() }?.let { user.put("flow", it) }

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
