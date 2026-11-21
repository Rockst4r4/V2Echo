package com.echolicense.vpn.data

enum class VpnProtocol { VLESS, VMESS, TROJAN, WIREGUARD }

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

enum class AppLanguage(val code: String) { FA("fa"), EN("en") }

data class SubscriptionItem(
    val id: String,
    val name: String,
    val url: String
)

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
    val expireTimestampSec: Long = 0L,
    val hasUserInfo: Boolean = false
) {
    val usedBytes: Long get() = uploadBytes + downloadBytes
    val usedGb: Double get() = usedBytes / (1024.0 * 1024.0 * 1024.0)
    val remainingBytes: Long get() = (totalBytes - usedBytes).coerceAtLeast(0L)
    val remainingGb: Double get() = remainingBytes / (1024.0 * 1024.0 * 1024.0)
    val totalGb: Double get() = totalBytes / (1024.0 * 1024.0 * 1024.0)
    val isUnlimited: Boolean get() = hasUserInfo && totalBytes == 0L
    val usageProgress: Float
        get() = if (totalBytes > 0) (usedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
    val isLowTraffic: Boolean
        get() = totalBytes > 0 && remainingBytes < (5L * 1024L * 1024L * 1024L)
    val remainingDays: Long?
        get() {
            if (expireTimestampSec <= 0L) return null
            val diffSec = expireTimestampSec - (System.currentTimeMillis() / 1000L)
            return if (diffSec <= 0L) 0L else (diffSec / 86400L) + 1L
        }
}

data class DnsProvider(
    val name: String,
    val ipv4: String,
    val dohUrl: String,
    val latencyMs: Int = -1
)

data class MuxConfig(
    val enabled: Boolean = false,
    val concurrency: Int = 8,
    val xudpConcurrency: Int = 16,
    val xudpProxyUDP443: String = "reject"
)

data class DynamicAnnouncement(
    val id: Int = 0,
    val active: Boolean = false,
    val title: String = "",
    val message: String = "",
    val promoCode: String = "",
    val buttonText: String = "",
    val actionUrl: String = "",
    val botUsername: String = "echopress_bot"
)
