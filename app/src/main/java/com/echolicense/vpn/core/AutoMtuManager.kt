package com.echolicense.vpn.core

import com.echolicense.vpn.data.VpnProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object AutoMtuManager {
    private val probeHosts = listOf("1.1.1.1", "8.8.8.8", "9.9.9.9")

    /**
     * بهترین MTU شبکه فعلی را با ارسال بسته DF پیدا می‌کند و سربار تانل را از آن کم می‌کند.
     */
    suspend fun detectOptimalMtu(protocol: VpnProtocol): Int = withContext(Dispatchers.IO) {
        val overhead = if (protocol == VpnProtocol.WIREGUARD) 80 else 60
        val candidates = listOf(1472, 1452, 1422, 1392, 1372, 1352, 1322, 1292, 1252)

        for (host in probeHosts) {
            for (payload in candidates) {
                if (canSendDfPing(host, payload)) {
                    val pathMtu = payload + 28
                    return@withContext (pathMtu - overhead).coerceIn(1280, 1440)
                }
            }
        }
        // مقدار امن پیش‌فرض در صورت بسته بودن ICMP روی اپراتور
        if (protocol == VpnProtocol.WIREGUARD) 1280 else 1360
    }

    private fun canSendDfPing(host: String, payloadSize: Int): Boolean {
        return try {
            val process = ProcessBuilder(
                "ping", "-M", "do", "-c", "1", "-W", "1", "-s", payloadSize.toString(), host
            ).redirectErrorStream(true).start()
            val finished = process.waitFor(1400, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return false
            }
            process.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }
}
