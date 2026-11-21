package com.echolicense.vpn

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.echolicense.vpn.core.EcholicenseVpnService
import com.echolicense.vpn.core.VpnStateManager
import com.echolicense.vpn.data.ConnectionState
import com.echolicense.vpn.data.MuxConfig
import com.echolicense.vpn.data.SubscriptionInfo
import com.echolicense.vpn.data.SubscriptionRepository
import com.echolicense.vpn.ui.MainScreen
import com.echolicense.vpn.worker.AnnouncementWorker
import com.echolicense.vpn.worker.TrafficAlertWorker
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        private const val KEY_MTU = "custom_mtu"
        private const val KEY_MANUAL_DNS = "manual_dns"
        private const val KEY_MUX_ENABLED = "mux_enabled"
        private const val KEY_MUX_CONC = "mux_concurrency"
        private const val KEY_MUX_XUDP = "mux_xudp"
        private const val KEY_MUX_UDP443 = "mux_udp443"
    }

    private val repo = SubscriptionRepository()

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            dispatchVpnAction(EcholicenseVpnService.ACTION_CONNECT)
        }
    }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val prefs = getSharedPreferences(TrafficAlertWorker.PREFS_NAME, Context.MODE_PRIVATE)
        val savedMtu = prefs.getInt(KEY_MTU, 1400).coerceIn(1200, 1500)
        val savedManualDns = prefs.getString(KEY_MANUAL_DNS, "").orEmpty()
        val savedMux = MuxConfig(
            enabled = prefs.getBoolean(KEY_MUX_ENABLED, false),
            concurrency = prefs.getInt(KEY_MUX_CONC, 8),
            xudpConcurrency = prefs.getInt(KEY_MUX_XUDP, 16),
            xudpProxyUDP443 = prefs.getString(KEY_MUX_UDP443, "reject") ?: "reject"
        )
        val savedSub = prefs.getString(TrafficAlertWorker.KEY_SUB_URL, "").orEmpty()

        VpnStateManager.update {
            it.copy(
                mtu = savedMtu,
                manualDns = savedManualDns,
                muxConfig = savedMux,
                currentSubUrl = savedSub
            )
        }

        // فعال‌سازی و بررسی فوری اطلاعیه‌ها و کدهای تخفیف داینامیک
        AnnouncementWorker.schedule(this)
        lifecycleScope.launch {
            AnnouncementWorker.fetchAndProcess(this@MainActivity)
        }

        if (savedSub.isNotBlank() && VpnStateManager.state.value.servers.isEmpty()) {
            handleImport(savedSub)
        }

        setContent {
            val uiState by VpnStateManager.state.collectAsStateWithLifecycle()

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MainScreen(
                    uiState = uiState,
                    onPowerClick = { handlePowerToggle() },
                    onImportInput = { handleImport(it) },
                    onRefreshSubscription = {
                        val url = VpnStateManager.state.value.currentSubUrl
                        if (url.isNotBlank()) handleImport(url)
                    },
                    onDeleteSubscription = { handleDeleteSubscription() },
                    onSelectServer = { srv ->
                        val wasConnected = VpnStateManager.state.value.connectionState == ConnectionState.CONNECTED
                        VpnStateManager.update { it.copy(activeServer = srv) }
                        if (wasConnected) dispatchVpnAction(EcholicenseVpnService.ACTION_RECONNECT)
                    },
                    onSmartPingClick = { runSmartPing() },
                    onToggleIranSplit = { enabled ->
                        VpnStateManager.update { it.copy(iranSplitEnabled = enabled) }
                    },
                    onToggleAiPro = { handleToggleAiPro() },
                    onChangeMtu = { newMtu ->
                        val clamped = newMtu.coerceIn(1200, 1500)
                        prefs.edit().putInt(KEY_MTU, clamped).apply()
                        VpnStateManager.update { it.copy(mtu = clamped) }
                    },
                    onApplyReconnect = {
                        if (VpnStateManager.state.value.connectionState == ConnectionState.CONNECTED) {
                            dispatchVpnAction(EcholicenseVpnService.ACTION_RECONNECT)
                        }
                    },
                    onSaveManualDns = { dns ->
                        prefs.edit().putString(KEY_MANUAL_DNS, dns).apply()
                        VpnStateManager.update { it.copy(manualDns = dns) }
                        if (VpnStateManager.state.value.connectionState == ConnectionState.CONNECTED) {
                            dispatchVpnAction(EcholicenseVpnService.ACTION_RECONNECT)
                        }
                    },
                    onUpdateMuxConfig = { mux ->
                        prefs.edit()
                            .putBoolean(KEY_MUX_ENABLED, mux.enabled)
                            .putInt(KEY_MUX_CONC, mux.concurrency)
                            .putInt(KEY_MUX_XUDP, mux.xudpConcurrency)
                            .putString(KEY_MUX_UDP443, mux.xudpProxyUDP443)
                            .apply()
                        VpnStateManager.update { it.copy(muxConfig = mux) }
                        if (VpnStateManager.state.value.connectionState == ConnectionState.CONNECTED) {
                            dispatchVpnAction(EcholicenseVpnService.ACTION_RECONNECT)
                        }
                    },
                    onDismissAnnouncement = { id ->
                        AnnouncementWorker.dismissBanner(this@MainActivity, id)
                    },
                    onOpenUrl = { url -> openExternalUrl(url) },
                    onOpenBot = { openTelegramBot(VpnStateManager.state.value.botUsername) }
                )
            }
        }
    }

    private fun openTelegramBot(username: String) {
        val clean = username.trim().removePrefix("@").ifBlank { "echopress_bot" }
        val tgIntent = Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=$clean"))
        runCatching { startActivity(tgIntent) }.onFailure {
            openExternalUrl("https://t.me/$clean")
        }
    }

    private fun openExternalUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    private fun handleDeleteSubscription() {
        if (VpnStateManager.state.value.connectionState != ConnectionState.DISCONNECTED) {
            dispatchVpnAction(EcholicenseVpnService.ACTION_DISCONNECT)
        }
        TrafficAlertWorker.cancel(this)
        VpnStateManager.update {
            it.copy(
                currentSubUrl = "",
                subscriptionInfo = SubscriptionInfo(),
                servers = emptyList(),
                activeServer = null,
                aiProOnly = false,
                statusMessage = "اشتراک فعلی حذف شد"
            )
        }
    }

    private fun handleToggleAiPro() {
        val current = VpnStateManager.state.value
        val nextEnabled = !current.aiProOnly

        if (nextEnabled) {
            val aiServers = current.servers.filter { it.isAiPro() }
            if (aiServers.isEmpty()) {
                VpnStateManager.update {
                    it.copy(aiProOnly = false, statusMessage = "سرور AI Pro در اشتراک شما یافت نشد")
                }
                return
            }
            val bestAi = aiServers.first()
            val isConnected = current.connectionState == ConnectionState.CONNECTED
            VpnStateManager.update {
                it.copy(
                    aiProOnly = true,
                    activeServer = bestAi,
                    statusMessage = "حالت AI Pro فعال شد (${bestAi.name})"
                )
            }
            if (isConnected) dispatchVpnAction(EcholicenseVpnService.ACTION_RECONNECT) else startVpnWithPermission()
        } else {
            VpnStateManager.update {
                it.copy(aiProOnly = false, statusMessage = "حالت عادی (تمام سرورها)")
            }
        }
    }

    private fun handlePowerToggle() {
        val state = VpnStateManager.state.value
        if (state.connectionState == ConnectionState.CONNECTED ||
            state.connectionState == ConnectionState.CONNECTING
        ) {
            dispatchVpnAction(EcholicenseVpnService.ACTION_DISCONNECT)
            return
        }

        if (state.aiProOnly) {
            val bestAi = state.servers.firstOrNull { it.isAiPro() }
            if (bestAi == null) {
                VpnStateManager.update { it.copy(statusMessage = "سرور AI Pro در اشتراک یافت نشد") }
                return
            }
            if (state.activeServer?.isAiPro() != true) {
                VpnStateManager.update { it.copy(activeServer = bestAi) }
            }
        }

        if (VpnStateManager.state.value.activeServer == null) {
            VpnStateManager.update { it.copy(statusMessage = "ابتدا لینک اشتراک یا کانفیگ اضافه کنید") }
            return
        }

        startVpnWithPermission()
    }

    private fun startVpnWithPermission() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            dispatchVpnAction(EcholicenseVpnService.ACTION_CONNECT)
        }
    }

    private fun dispatchVpnAction(action: String) {
        val intent = Intent(this, EcholicenseVpnService::class.java).setAction(action)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun handleImport(input: String) {
        lifecycleScope.launch {
            VpnStateManager.update { it.copy(statusMessage = "در حال دریافت سرورها و حجم...") }
            try {
                if (input.startsWith("http://", true) || input.startsWith("https://", true)) {
                    val (subInfo, servers) = repo.fetchSubscription(input)
                    val sorted = repo.pingAllServers(servers)
                    VpnStateManager.update { state ->
                        val preferred = if (state.aiProOnly) {
                            sorted.firstOrNull { it.isAiPro() }
                        } else {
                            sorted.firstOrNull()
                        }
                        state.copy(
                            currentSubUrl = input,
                            subscriptionInfo = subInfo,
                            servers = sorted,
                            activeServer = preferred,
                            statusMessage = "${sorted.size} سرور آماده اتصال"
                        )
                    }
                    TrafficAlertWorker.schedule(this@MainActivity, input)
                    TrafficAlertWorker.evaluateAndNotify(this@MainActivity, subInfo)
                } else {
                    val parsed = repo.parseSingleInput(input)
                    VpnStateManager.update {
                        val merged = it.servers + parsed
                        it.copy(
                            servers = merged,
                            activeServer = parsed.firstOrNull() ?: it.activeServer,
                            statusMessage = "کانفیگ اضافه شد"
                        )
                    }
                }
            } catch (e: Exception) {
                VpnStateManager.update {
                    it.copy(statusMessage = "خطا: ${e.localizedMessage ?: "لینک نامعتبر"}")
                }
            }
        }
    }

    private fun runSmartPing() {
        lifecycleScope.launch {
            val current = VpnStateManager.state.value.servers
            if (current.isEmpty()) return@launch
            val sorted = repo.pingAllServers(current)
            VpnStateManager.update { state ->
                val preferred = if (state.aiProOnly) {
                    sorted.firstOrNull { it.isAiPro() }
                } else {
                    sorted.firstOrNull()
                }
                state.copy(servers = sorted, activeServer = preferred)
            }
        }
    }
}
