package com.echolicense.vpn.core

import com.echolicense.vpn.data.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VpnUiState(
    val language: AppLanguage = AppLanguage.EN,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val activeServer: ServerProfile? = null,
    val servers: List<ServerProfile> = emptyList(),
    val isPinging: Boolean = false,
    val subscriptions: List<SubscriptionItem> = emptyList(),
    val activeSubscriptionId: String? = null,
    val currentSubUrl: String = "",
    val lastSubUpdatedAt: Long = 0L,
    val subscriptionInfo: SubscriptionInfo = SubscriptionInfo(),
    val activeDns: DnsProvider? = null,
    val manualDns: String = "",
    val iranSplitEnabled: Boolean = true,
    val lanMirrorBypassEnabled: Boolean = true,
    val adBlockerEnabled: Boolean = true,
    val aiProOnly: Boolean = false,
    val gamingOnly: Boolean = false,
    val autoMtuEnabled: Boolean = true,
    val mtu: Int = 1360,
    val muxConfig: MuxConfig = MuxConfig(),
    val announcement: DynamicAnnouncement? = null,
    val botUsername: String = "echopress_bot",
    val downloadSpeedKbps: Long = 0L,
    val uploadSpeedKbps: Long = 0L,
    val connectedSeconds: Long = 0L,
    val directPingMs: Int = -1,
    val directJitterMs: Int = -1,
    val directPingHistory: List<Int> = emptyList(),
    val vpnPingMs: Int = -1,
    val vpnJitterMs: Int = -1,
    val vpnPingHistory: List<Int> = emptyList(),
    val directAttempts: List<Boolean> = emptyList(),
    val vpnAttempts: List<Boolean> = emptyList(),
    val updateAvailable: Boolean = false,
    val updateTag: String = "",
    val updateUrl: String = "",
    val updateFileName: String = "",
    val updateSizeMb: Double = 0.0,
    val isCheckingUpdate: Boolean = false,
    val isDownloadingUpdate: Boolean = false,
    val updateDownloadProgress: Int = 0,
    val updateStatusText: String = "",
    val openUpdateDialogRequest: Boolean = false,
    val statusMessage: String = "Ready to connect"
)

object VpnStateManager {
    private val _state = MutableStateFlow(VpnUiState())
    val state: StateFlow<VpnUiState> = _state.asStateFlow()

    fun update(block: (VpnUiState) -> VpnUiState) {
        _state.value = block(_state.value)
    }

    fun setConnected() = update {
        val msg = if (it.language == AppLanguage.EN) "Connected & Secured (TUN)" else "متصل و ایمن (TUN)"
        it.copy(connectionState = ConnectionState.CONNECTED, statusMessage = msg)
    }

    fun setDisconnected() = update {
        val msg = if (it.language == AppLanguage.EN) "Disconnected" else "قطع شده"
        it.copy(
            connectionState = ConnectionState.DISCONNECTED,
            statusMessage = msg,
            downloadSpeedKbps = 0,
            uploadSpeedKbps = 0,
            connectedSeconds = 0,
            vpnPingMs = -1,
            vpnJitterMs = -1,
            vpnPingHistory = emptyList(),
            vpnAttempts = emptyList()
        )
    }
}
