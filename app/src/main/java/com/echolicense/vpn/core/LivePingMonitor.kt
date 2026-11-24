package com.echolicense.vpn.core

import java.io.BufferedInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.abs
import kotlin.math.max
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

    private val FAR_FLAGS = listOf("🇺🇸", "🇨🇦", "🇧🇷", "🇦🇷", "🇲🇽", "🇯🇵", "🇸🇬", "🇰🇷", "🇦🇺", "🇭🇰")
    private val NEAR_FLAGS = listOf("🇩🇪", "🇳🇱", "🇨🇭", "🇬🇧", "🇫🇷", "🇫🇮", "🇸🇪", "🇵🇱", "🇦🇹", "🇮🇹", "🇪🇸", "🇹🇷", "🇦🇪")

    private val FAR_WORDS = Regex(
        "(^|[^a-zA-Z])(us|usa|ca|jp|sg|au|kr|br)([^a-zA-Z]|$)|united\\s*states|america|canada|brazil|japan|singapore|australia|korea|hong\\s*kong|new\\s*york|los\\s*angeles|dallas|miami|chicago|seattle|ashburn|virginia|washington|california|texas|silicon|toronto|montreal|vancouver|tokyo|seoul|sydney|آمریکا|امریکا|کانادا|ژاپن|سنگاپور|استرالیا|برزیل|کره|نیویورک|شیکاگو|دالاس",
        RegexOption.IGNORE_CASE
    )

    private val NEAR_WORDS = Regex(
        "(^|[^a-zA-Z])(de|nl|ch|uk|tr|ae|fr|fi|se|at)([^a-zA-Z]|$)|germany|frankfurt|falkenstein|nuremberg|netherlands|amsterdam|switzerland|zurich|london|england|france|paris|finland|helsinki|sweden|poland|austria|vienna|italy|spain|turkey|istanbul|uae|dubai|emirates|آلمان|المان|فرانکفورت|هلند|سوئیس|سوییس|انگلیس|لندن|فرانسه|فنلاند|ترکیه|استانبول|امارات|دبی",
        RegexOption.IGNORE_CASE
    )

    private fun isFarServer(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        if (FAR_FLAGS.any { name.contains(it) }) return true
        return FAR_WORDS.containsMatchIn(name)
    }

    private fun isNearServer(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        if (NEAR_FLAGS.any { name.contains(it) }) return true
        return NEAR_WORDS.containsMatchIn(name)
    }

    private fun smoothedPing(history: List<Int>, current: Int): Int {
        val valid = history.filter { it > 0 }.takeLast(4)
        if (valid.isEmpty()) return current
        return (valid.sum().toDouble() / valid.size).roundToInt()
    }

    fun evaluateQuality(
        isConnected: Boolean,
        directPingMs: Int,
        directJitterMs: Int,
        vpnPingMs: Int,
        vpnJitterMs: Int,
        serverName: String? = null,
        directHistory: List<Int> = emptyList(),
        vpnHistory: List<Int> = emptyList()
    ): PingQualityReport {
        val dMs = smoothedPing(directHistory, directPingMs)
        val vMs = smoothedPing(vpnHistory, vpnPingMs)
        val deltaPing = if (isConnected && directPingMs > 0 && vpnPingMs > 0) vpnPingMs - directPingMs else null
        val deltaJitter = if (isConnected && directJitterMs >= 0 && vpnJitterMs >= 0) vpnJitterMs - directJitterMs else null

        if (dMs <= 0 && (!isConnected || vMs <= 0)) {
            return PingQualityReport(0, "offline", "در حال تست / عدم پاسخ 1.1.1.1", "Probing / 1.1.1.1 Unreachable", null, null)
        }

        if (!isConnected) {
            val j = directJitterMs.coerceAtLeast(0)
            return when {
                dMs <= 95 && j <= 15 ->
                    PingQualityReport(95, "excellent", "اینترنت کاربر عالی و پایدار", "User ISP: Excellent & Stable", null, null)
                dMs <= 150 && j <= 30 ->
                    PingQualityReport(82, "good", "اینترنت کاربر خوب (آماده اتصال)", "User ISP: Good (Ready)", null, null)
                dMs <= 225 && j <= 50 ->
                    PingQualityReport(56, "fair", "نوسان متوسط روی اینترنت کاربر", "User ISP: Moderate Jitter", null, null)
                else ->
                    PingQualityReport(28, "poor", "اختلال و نوسان بالا روی اینترنت کاربر", "User ISP: High Instability", null, null)
            }
        }

        if (vMs <= 0) {
            return PingQualityReport(40, "fair", "در حال سنجش پینگ داخل VPN...", "Measuring VPN ping...", null, null)
        }

        val dj = directJitterMs.coerceAtLeast(0)
        val vj = vpnJitterMs.coerceAtLeast(0)

        if (dMs > 0 && (dMs > 230 || dj > 50)) {
            val ispScore = (42 - max(0, dj - 50) / 2).coerceIn(20, 42)
            return PingQualityReport(
                ispScore,
                "poor",
                "افت کیفیت از اینترنت پایه شماست (نوسان نت کاربر بالاست)",
                "ISP Issue (High Base Latency/Jitter)",
                deltaPing,
                deltaJitter
            )
        }

        val explicitFar = isFarServer(serverName)
        val explicitNear = !explicitFar && isNearServer(serverName)
        val sDelta = if (dMs > 0) (vMs - dMs) else (vMs - 95)
        val inferredFar = !explicitFar && !explicitNear && (sDelta in 90..215) && vj <= 24
        val isLongDistance = explicitFar || inferredFar

        val geoAllowance = when {
            explicitFar -> 140
            inferredFar -> 115
            else -> 55
        }

        val excessLatency = max(0, sDelta - geoAllowance)
        val netJitter = if (directJitterMs >= 0) max(0, vj - dj / 2) else vj
        val latPenalty = excessLatency * 0.35
        val jitPenalty = max(0, netJitter - 14) * 1.35
        val score = (96.0 - latPenalty - jitPenalty).roundToInt().coerceIn(22, 97)

        return when {
            score >= 85 -> PingQualityReport(
                score,
                "excellent",
                if (isLongDistance) "کیفیت عالی (سرور دوربرد) • پایدار و بدون نوسان" else "کیفیت عالی • پینگ و نوسان کاملاً پایدار",
                if (isLongDistance) "Excellent (Long-Distance) • Stable Low Jitter" else "Excellent • Stable Low Jitter",
                deltaPing,
                deltaJitter
            )
            score >= 68 -> PingQualityReport(
                score,
                "good",
                if (isLongDistance) "کیفیت خوب (مسیر دوربرد) • اتصال پایدار" else "کیفیت خوب • مسیر VPN پایدار",
                if (isLongDistance) "Good (Long-Distance) • Stable Route" else "Good • Stable VPN Route",
                deltaPing,
                deltaJitter
            )
            score >= 45 -> PingQualityReport(
                score,
                "fair",
                if (vj > 38) "کیفیت متوسط • کمی نوسان در مسیر دیده می‌شود" else "کیفیت متوسط • برای پینگ کمتر سرور نزدیک‌تر انتخاب کنید",
                if (vj > 38) "Fair • Moderate Route Jitter" else "Fair • Pick a closer server for lower ping",
                deltaPing,
                deltaJitter
            )
            else -> PingQualityReport(
                score,
                "poor",
                "نوسان یا تأخیر مسیر بالاست • سرور نزدیک‌تر یا خلوت‌تر انتخاب کنید",
                "High Route Jitter/Latency • Try a closer server",
                deltaPing,
                deltaJitter
            )
        }
    }
}
