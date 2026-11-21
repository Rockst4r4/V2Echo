package com.echolicense.vpn.core

import com.echolicense.vpn.data.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VpnUiState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val activeServer: ServerProfile? = null,
    val servers: List<ServerProfile> = emptyList(),
    val currentSubUrl: String = "",
    val subscriptionInfo: SubscriptionInfo = SubscriptionInfo(),
    val activeDns: DnsProvider? = null,
    val manualDns: String = "",
    val iranSplitEnabled: Boolean = true,
    val aiProOnly: Boolean = false,
    val mtu: Int = 1400,
    val muxConfig: MuxConfig = MuxConfig(),
    val announcement: DynamicAnnouncement? = null,
    val botUsername: String = "echopress_bot",
    val downloadSpeedKbps: Long = 0L,
    val uploadSpeedKbps: Long = 0L,
    val connectedSeconds: Long = 0L,
    val statusMessage: String = "آماده اتصال"
)

object VpnStateManager {
    private val _state = MutableStateFlow(VpnUiState())
    val state: StateFlow<VpnUiState> = _state.asStateFlow()

    fun update(block: (VpnUiState) -> VpnUiState) {
        _state.value = block(_state.value)
    }

    fun setConnected() = update {
        it.copy(connectionState = ConnectionState.CONNECTED, statusMessage = "متصل و ایمن (TUN)")
    }

    fun setDisconnected() = update {
        it.copy(
            connectionState = ConnectionState.DISCONNECTED,
            statusMessage = "قطع شده",
            downloadSpeedKbps = 0,
            uploadSpeedKbps = 0,
            connectedSeconds = 0
        )
    }
}
