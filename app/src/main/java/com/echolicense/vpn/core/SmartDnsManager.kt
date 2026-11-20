package com.echolicense.vpn.core

import com.echolicense.vpn.data.DnsProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

object SmartDnsManager {
    private val dnsPool = listOf(
        DnsProvider("Cloudflare", "1.1.1.1", "https://1.1.1.1/dns-query"),
        DnsProvider("Quad9", "9.9.9.9", "https://dns.quad9.net/dns-query"),
        DnsProvider("Google", "8.8.8.8", "https://dns.google/dns-query"),
        DnsProvider("AdGuard", "94.140.14.14", "https://dns.adguard-dns.com/dns-query"),
        DnsProvider("OpenDNS", "208.67.222.222", "https://doh.opendns.com/dns-query"),
        DnsProvider("Cloudflare Alt", "1.0.0.1", "https://1.0.0.1/dns-query")
    )

    const val IRAN_DIRECT_DNS = "178.22.122.100"

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .build()

    suspend fun resolveBestWorkingDns(): DnsProvider = withContext(Dispatchers.IO) {
        for (provider in dnsPool) {
            val latency = probeDns(provider)
            if (latency in 1..1500) {
                return@withContext provider.copy(latencyMs = latency)
            }
        }
        dnsPool.first().copy(latencyMs = 0)
    }

    private fun probeDns(provider: DnsProvider): Int {
        val tcpTime = try {
            val start = System.currentTimeMillis()
            Socket().use { s ->
                s.connect(InetSocketAddress(provider.ipv4, 443), 1200)
            }
            (System.currentTimeMillis() - start).toInt()
        } catch (_: Exception) {
            -1
        }
        if (tcpTime > 0) return tcpTime

        return try {
            val start = System.currentTimeMillis()
            val req = Request.Builder()
                .url("${provider.dohUrl}?name=example.com&type=A")
                .header("Accept", "application/dns-json")
                .build()
            probeClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) (System.currentTimeMillis() - start).toInt() else -1
            }
        } catch (_: Exception) {
            -1
        }
    }
}
