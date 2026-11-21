package com.echolicense.vpn.data

enum class VpnProtocol { VLESS, VMESS, TROJAN, WIREGUARD }

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

data class ServerProfile(
    val id: String,
    val name: String,
    val protocol: VpnProtocol,
    val rawConfig: String,
    val host: String,
    val port: Int,
    val pingMs: Int = -1
) {
    fun isAiPro(): Boolean {
        val lower = name.lowercase()
        if (lower.contains("ai pro") || lower.contains("aipro") ||
            lower.contains("ai-pro") || lower.contains("ai_pro")
        ) {
            return true
        }
        return Regex("""(^|[\s\-_\[\]()|])ai([\s\-_\[\]()|]|$)""").containsMatchIn(lower)
    }
}

data class SubscriptionInfo(
    val subUrl: String = "",
    val uploadBytes: Long = 0L,
    val downloadBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val expireTimestampSec: Long = 0L
) {
    val usedBytes: Long get() = uploadBytes + downloadBytes
    val remainingBytes: Long get() = (totalBytes - usedBytes).coerceAtLeast(0L)
    val remainingGb: Double get() = remainingBytes / (1024.0 * 1024.0 * 1024.0)
    val totalGb: Double get() = totalBytes / (1024.0 * 1024.0 * 1024.0)
    val usageProgress: Float
        get() = if (totalBytes > 0) (usedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
    val isLowTraffic: Boolean
        get() = totalBytes > 0 && remainingBytes < (5L * 1024L * 1024L * 1024L)
}

data class DnsProvider(
    val name: String,
    val ipv4: String,
    val dohUrl: String,
    val latencyMs: Int = -1
)
