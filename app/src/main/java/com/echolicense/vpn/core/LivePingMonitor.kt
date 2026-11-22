package com.echolicense.vpn.core

import java.io.BufferedInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.abs
import kotlin.math.roundToInt

data class PingQualityReport(
    val score: Int,
    val level: String,
    val labelFa: String,
    val labelEn: String,
    val deltaPingMs: Int?,
    val deltaJitterMs: Int?
)

object LivePingMonitor {

    private const val TIMEOUT_MS = 1400

    private fun readHttpHeaders(input: BufferedInputStream): String? {
        val sb = StringBuilder(256)
        var prev3 = 0
        var prev2 = 0
        var prev1 = 0
        var count = 0
        while (count < 4096) {
            val b = input.read()
            if (b == -1) break
            sb.append(b.toChar())
            count++
            if (prev3 == '\r'.code && prev2 == '\n'.code && prev1 == '\r'.code && b == '\n'.code) {
                return sb.toString()
            }
            prev3 = prev2
            prev2 = prev1
            prev1 = b
        }
        return if (sb.isNotEmpty()) sb.toString() else null
    }

    private fun readFirstLine(input: BufferedInputStream): String? {
        val sb = StringBuilder(128)
        var count = 0
        while (count < 512) {
            val b = input.read()
            if (b == -1) break
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            count++
        }
        return if (sb.isNotEmpty()) sb.toString() else null
    }

    private fun probeTcpConnect(
        host: String,
        port: Int,
        timeoutMs: Int,
        tcpProtector: ((Socket) -> Unit)?
    ): Int {
        var sock: Socket? = null
        return try {
            sock = Socket()
            runCatching { sock.bind(InetSocketAddress(0)) }
            tcpProtector?.invoke(sock)
            sock.soTimeout = timeoutMs
            sock.tcpNoDelay = true
            val t0 = System.nanoTime()
            sock.connect(InetSocketAddress(host, port), timeoutMs)
            ((System.nanoTime() - t0) / 1_000_000L).toInt().coerceIn(1, 3000)
        } catch (_: Throwable) {
            -1
        } finally {
            runCatching { sock?.close() }
        }
    }

    private fun probeUdpDns(
        host: String,
        timeoutMs: Int,
        udpProtector: ((DatagramSocket) -> Unit)?
    ): Int {
        var ds: DatagramSocket? = null
        return try {
            val query = byteArrayOf(
                0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x03, 'o'.code.toByte(), 'n'.code.toByte(), 'e'.code.toByte(),
                0x03, 'o'.code.toByte(), 'n'.code.toByte(), 'e'.code.toByte(),
                0x03, 'o'.code.toByte(), 'n'.code.toByte(), 'e'.code.toByte(),
                0x03, 'o'.code.toByte(), 'n'.code.toByte(), 'e'.code.toByte(),
                0x00, 0x00, 0x01, 0x00, 0x01
            )
            ds = DatagramSocket()
            udpProtector?.invoke(ds)
            ds.soTimeout = timeoutMs
            val addr = InetAddress.getByName(host)
            val req = DatagramPacket(query, query.size, addr, 53)
            val respBuf = ByteArray(256)
            val resp = DatagramPacket(respBuf, respBuf.size)
            val t0 = System.nanoTime()
            ds.send(req)
            ds.receive(resp)
            if (resp.length >= 12 && respBuf[0] == 0x12.toByte() && respBuf[1] == 0x34.toByte()) {
                ((System.nanoTime() - t0) / 1_000_000L).toInt().coerceIn(1, 3000)
            } else {
                -1
            }
        } catch (_: Throwable) {
            -1
        } finally {
            runCatching { ds?.close() }
        }
    }

    private fun probeIcmp1111(): Int {
        return try {
            val proc = ProcessBuilder("/system/bin/ping", "-c", "1", "-W", "2", "1.1.1.1")
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.bufferedReader().use { it.readText() }
            proc.waitFor()
            val m = Regex("time[=<]\\s*([0-9.]+)\\s*ms", RegexOption.IGNORE_CASE).find(out)
            m?.groupValues?.get(1)?.toDoubleOrNull()?.roundToInt()?.coerceIn(1, 3000) ?: -1
        } catch (_: Throwable) {
            -1
        }
    }

    /**
     * پینگ مستقیم از اینترنت خام کاربر (خارج از تونل VPN)
     */
    fun probeDirect1111(
        tcpProtector: ((Socket) -> Unit)? = null,
        udpProtector: ((DatagramSocket) -> Unit)? = null
    ): Int {
        val r1 = probeTcpConnect("1.1.1.1", 443, TIMEOUT_MS, tcpProtector)
        if (r1 > 0) return r1

        val r2 = probeTcpConnect("1.0.0.1", 443, TIMEOUT_MS, tcpProtector)
        if (r2 > 0) return r2

        val r3 = probeTcpConnect("8.8.8.8", 443, TIMEOUT_MS, tcpProtector)
        if (r3 > 0) return r3

        val r4 = probeUdpDns("1.1.1.1", TIMEOUT_MS, udpProtector)
        if (r4 > 0) return r4

        val icmp = probeIcmp1111()
        if (icmp > 0) return icmp

        return probeTcpConnect("1.1.1.1", 80, TIMEOUT_MS, tcpProtector)
    }

    private fun probeProxyTarget(port: Int, host: String, path: String): Int {
        var sock: Socket? = null
        return try {
            sock = Socket()
            sock.soTimeout = TIMEOUT_MS + 900
            sock.tcpNoDelay = true
            sock.connect(InetSocketAddress("127.0.0.1", port), 1000)

            val out = sock.getOutputStream()
            val inp = BufferedInputStream(sock.getInputStream())

            out.write("CONNECT $host:80 HTTP/1.1\r\nHost: $host:80\r\n\r\n".toByteArray())
            out.flush()
            val connResp = readHttpHeaders(inp) ?: return -1
            if (!connResp.contains("200")) return -1

            val tFirst0 = System.nanoTime()
            out.write("HEAD $path HTTP/1.1\r\nHost: $host\r\nConnection: keep-alive\r\n\r\n".toByteArray())
            out.flush()
            val h1 = readHttpHeaders(inp) ?: return -1
            val firstMs = ((System.nanoTime() - tFirst0) / 1_000_000L).toInt().coerceAtLeast(1)

            if (!h1.contains("connection: close", ignoreCase = true)) {
                val t0 = System.nanoTime()
                out.write("HEAD $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()
                val line2 = readFirstLine(inp)
                if (!line2.isNullOrBlank() && line2.startsWith("HTTP/")) {
                    return ((System.nanoTime() - t0) / 1_000_000L).toInt().coerceIn(1, 3000)
                }
            }
            (firstMs / 2).coerceIn(15, 3000)
        } catch (_: Throwable) {
            -1
        } finally {
            runCatching { sock?.close() }
        }
    }

    fun probeViaLocalHttpProxy(port: Int): Int {
        if (port <= 0) return -1
        val r1 = probeProxyTarget(port, "1.1.1.1", "/cdn-cgi/trace")
        if (r1 > 0) return r1
        return probeProxyTarget(port, "cp.cloudflare.com", "/generate_204")
    }

    fun computeJitter(samples: List<Int>): Int {
        val valid = samples.filter { it > 0 }
        if (valid.size < 2) return -1
        var sum = 0.0
        for (i in 1 until valid.size) {
            sum += abs(valid[i] - valid[i - 1])
        }
        return (sum / (valid.size - 1)).roundToInt()
    }

    fun evaluateQuality(
        isConnected: Boolean,
        directPingMs: Int,
        directJitterMs: Int,
        vpnPingMs: Int,
        vpnJitterMs: Int
    ): PingQualityReport {
        val deltaPing = if (isConnected && directPingMs > 0 && vpnPingMs > 0) vpnPingMs - directPingMs else null
        val deltaJitter = if (isConnected && directJitterMs >= 0 && vpnJitterMs >= 0) vpnJitterMs - directJitterMs else null

        if (directPingMs <= 0 && (!isConnected || vpnPingMs <= 0)) {
            return PingQualityReport(0, "offline", "در حال تست / عدم پاسخ 1.1.1.1", "Probing / 1.1.1.1 Unreachable", null, null)
        }

        if (!isConnected) {
            val j = directJitterMs.coerceAtLeast(0)
            return when {
                directPingMs <= 70 && j <= 12 ->
                    PingQualityReport(94, "excellent", "اینترنت کاربر عالی و پایدار", "User ISP: Excellent & Stable", null, null)
                directPingMs <= 130 && j <= 28 ->
                    PingQualityReport(78, "good", "اینترنت کاربر خوب (آماده اتصال)", "User ISP: Good (Ready)", null, null)
                directPingMs <= 220 && j <= 55 ->
                    PingQualityReport(52, "fair", "نوسان متوسط روی اینترنت کاربر", "User ISP: Moderate Jitter", null, null)
                else ->
                    PingQualityReport(24, "poor", "اختلال و نوسان بالا روی اینترنت کاربر", "User ISP: High Instability", null, null)
            }
        }

        if (vpnPingMs <= 0) {
            return PingQualityReport(35, "fair", "در حال سنجش پینگ داخل VPN...", "Measuring VPN ping...", null, null)
        }

        val dj = directJitterMs.coerceAtLeast(0)
        val vj = vpnJitterMs.coerceAtLeast(0)

        if (directPingMs > 0 && (directPingMs > 180 || dj > 45)) {
            return PingQualityReport(
                28,
                "poor",
                "افت کیفیت از اینترنت کاربر (نوسان نت پایه بالاست)",
                "ISP Issue (High Base Jitter)",
                deltaPing,
                deltaJitter
            )
        }

        if (deltaPing != null) {
            val jOverhead = (deltaJitter ?: 0).coerceAtLeast(0)
            return when {
                deltaPing <= 90 && vpnPingMs <= 155 && vj <= 18 ->
                    PingQualityReport(95, "excellent", "کیفیت عالی • پینگ و نوسان کاملاً پایدار", "Excellent • Stable Low Jitter", deltaPing, deltaJitter)
                deltaPing <= 150 && vpnPingMs <= 235 && vj <= 35 ->
                    PingQualityReport(78, "good", "کیفیت خوب • مسیر VPN پایدار", "Good • Stable VPN Route", deltaPing, deltaJitter)
                deltaPing > 150 || jOverhead > 30 ->
                    PingQualityReport(44, "fair", "اختلاف پینگ VPN زیاد است • تعویض سرور پیشنهاد می‌شود", "High VPN Delta • Switch Server", deltaPing, deltaJitter)
                vpnPingMs > 320 || vj > 60 ->
                    PingQualityReport(25, "poor", "کیفیت ضعیف • نوسان شدید اتصال", "Poor • High Latency/Jitter", deltaPing, deltaJitter)
                else ->
                    PingQualityReport(60, "fair", "کیفیت متوسط • قابل استفاده", "Fair • Moderate Quality", deltaPing, deltaJitter)
            }
        }

        return when {
            vpnPingMs <= 145 && vj <= 20 ->
                PingQualityReport(92, "excellent", "اتصال VPN عالی و پایدار", "VPN: Excellent", null, null)
            vpnPingMs <= 235 && vj <= 38 ->
                PingQualityReport(76, "good", "اتصال VPN خوب", "VPN: Good", null, null)
            vpnPingMs <= 320 ->
                PingQualityReport(52, "fair", "اتصال VPN متوسط", "VPN: Fair", null, null)
            else ->
                PingQualityReport(26, "poor", "پینگ VPN بالاست", "VPN: High Ping", null, null)
        }
    }
}
