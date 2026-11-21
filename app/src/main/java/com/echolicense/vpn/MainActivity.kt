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
import com.echolicense.vpn.data.*
import com.echolicense.vpn.ui.MainScreen
import com.echolicense.vpn.worker.AnnouncementWorker
import com.echolicense.vpn.worker.SubscriptionAutoUpdateWorker
import com.echolicense.vpn.worker.TrafficAlertWorker
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class MainActivity : ComponentActivity() {

    companion object {
        private const val KEY_MTU = "custom_mtu"
        private const val KEY_AUTO_MTU = "auto_mtu"
        private const val KEY_MANUAL_DNS = "manual_dns"
        private const val KEY_LANG = "app_lang"
        private const val KEY_ADBLOCK = "adblock_enabled"
        private const val KEY_SUBS_JSON = "saved_subscriptions_json"
        private const val KEY_ACTIVE_SUB_ID = "active_subscription_id"
        private const val KEY_MUX_ENABLED = "mux_enabled"
        private const val KEY_MUX_CONC = "mux_concurrency"
        private const val KEY_MUX_XUDP = "mux_xudp"
        private const val KEY_MUX_UDP443 = "mux_udp443"
    }

    private val repo = SubscriptionRepository()
    private val prefs by lazy { getSharedPreferences(TrafficAlertWorker.PREFS_NAME, Context.MODE_PRIVATE) }

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

    private val qrScannerLauncher = registerForActivityResult(ScanContract()) { result ->
        val scanned = result.contents?.trim()
        if (!scanned.isNullOrBlank()) {
            val count = VpnStateManager.state.value.subscriptions.size + 1
            handleAddSubscription("QR Sub #$count", scanned)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val savedLang = if (prefs.getString(KEY_LANG, "en") == "fa") AppLanguage.FA else AppLanguage.EN
        val savedAdBlock = prefs.getBoolean(KEY_ADBLOCK, true)
        val savedMtu = prefs.getInt(KEY_MTU, 1360).coerceIn(1200, 1500)
        val savedAutoMtu = prefs.getBoolean(KEY_AUTO_MTU, true)
        val savedManualDns = prefs.getString(KEY_MANUAL_DNS, "").orEmpty()
        val savedMux = MuxConfig(
            enabled = prefs.getBoolean(KEY_MUX_ENABLED, false),
            concurrency = prefs.getInt(KEY_MUX_CONC, 8),
            xudpConcurrency = prefs.getInt(KEY_MUX_XUDP, 16),
            xudpProxyUDP443 = prefs.getString(KEY_MUX_UDP443, "reject") ?: "reject"
        )

        var subs = loadSubscriptions()
        var activeSubId = prefs.getString(KEY_ACTIVE_SUB_ID, null)
        val legacySub = prefs.getString(TrafficAlertWorker.KEY_SUB_URL, "").orEmpty()
        if (subs.isEmpty() && legacySub.isNotBlank()) {
            val item = SubscriptionItem(UUID.randomUUID().toString(), "Main Plan", legacySub)
            subs = listOf(item)
            activeSubId = item.id
            saveSubscriptions(subs, activeSubId)
        }
        val activeSub = subs.firstOrNull { it.id == activeSubId } ?: subs.firstOrNull()

        VpnStateManager.update {
            it.copy(
                language = savedLang,
                adBlockerEnabled = savedAdBlock,
                mtu = savedMtu,
                autoMtuEnabled = savedAutoMtu,
                manualDns = savedManualDns,
                muxConfig = savedMux,
                subscriptions = subs,
                activeSubscriptionId = activeSub?.id,
                currentSubUrl = activeSub?.url.orEmpty()
            )
        }

        AnnouncementWorker.schedule(this)
        lifecycleScope.launch { AnnouncementWorker.fetchAndProcess(this@MainActivity) }

        if (activeSub != null) {
            SubscriptionAutoUpdateWorker.schedule(this, replace = false)
            if (VpnStateManager.state.value.servers.isEmpty()) {
                loadSubscriptionUrl(activeSub.url)
            }
        }

        setContent {
            val uiState by VpnStateManager.state.collectAsStateWithLifecycle()
            val dir = if (uiState.language == AppLanguage.FA) LayoutDirection.Rtl else LayoutDirection.Ltr

            CompositionLocalProvider(LocalLayoutDirection provides dir) {
                MainScreen(
                    uiState = uiState,
                    onToggleLanguage = {
                        val next = if (uiState.language == AppLanguage.FA) AppLanguage.EN else AppLanguage.FA
                        prefs.edit().putString(KEY_LANG, next.code).apply()
                        VpnStateManager.update { it.copy(language = next) }
                    },
                    onScanQrClick = { launchQrScanner() },
                    onPowerClick = { handlePowerToggle() },
                    onAddSubscription = { name, input -> handleAddSubscription(name, input) },
                    onSwitchSubscription = { item -> handleSwitchSubscription(item) },
                    onDeleteSubscription = { item -> handleDeleteSubscription(item) },
                    onRefreshSubscription = {
                        val url = VpnStateManager.state.value.currentSubUrl
                        if (url.isNotBlank()) loadSubscriptionUrl(url)
                    },
                    onSelectServer = { srv ->
                        val wasConnected = VpnStateManager.state.value.connectionState == ConnectionState.CONNECTED
                        VpnStateManager.update { it.copy(activeServer = srv) }
                        if (wasConnected) dispatchVpnAction(EcholicenseVpnService.ACTION_RECONNECT)
                    },
                    onSmartPingClick = { runSmartPing() },
                    onToggleIranSplit = { enabled ->
                        VpnStateManager.update { it.copy(iranSplitEnabled = enabled) }
                    },
                    onToggleAdBlocker = { enabled ->
                        prefs.edit().putBoolean(KEY_ADBLOCK, enabled).apply()
                        VpnStateManager.update { it.copy(adBlockerEnabled = enabled) }
                        if (VpnStateManager.state.value.connectionState == ConnectionState.CONNECTED) {
                            dispatchVpnAction(EcholicenseVpnService.ACTION_RECONNECT)
                        }
                    },
                    onToggleAiPro = { handleToggleAiPro() },
                    onToggleAutoMtu = { enabled ->
                        prefs.edit().putBoolean(KEY_AUTO_MTU, enabled).apply()
                        VpnStateManager.update { it.copy(autoMtuEnabled = enabled) }
                    },
                    onChangeMtu = { newMtu ->
                        val clamped = newMtu.coerceIn(1200, 1500)
                        prefs.edit().putInt(KEY_MTU, clamped).putBoolean(KEY_AUTO_MTU, false).apply()
                        VpnStateManager.update { it.copy(mtu = clamped, autoMtuEnabled = false) }
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
                    onDismissAnnouncement = { id -> AnnouncementWorker.dismissBanner(this@MainActivity, id) },
                    onOpenUrl = { url -> openExternalUrl(url) },
                    onOpenBot = { openTelegramBot(VpnStateManager.state.value.botUsername) }
                )
            }
        }
    }

    private fun launchQrScanner() {
        val isEn = VpnStateManager.state.value.language == AppLanguage.EN
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt(if (isEn) "Scan subscription or config QR Code" else "کد QR اشتراک یا کانفیگ را اسکن کنید")
            setBeepEnabled(true)
            setBarcodeImageEnabled(false)
            setOrientationLocked(false)
        }
        qrScannerLauncher.launch(options)
    }

    private fun loadSubscriptions(): List<SubscriptionItem> {
        val raw = prefs.getString(KEY_SUBS_JSON, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                SubscriptionItem(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    url = obj.getString("url")
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun saveSubscriptions(list: List<SubscriptionItem>, activeId: String?) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("url", it.url))
        }
        prefs.edit()
            .putString(KEY_SUBS_JSON, arr.toString())
            .putString(KEY_ACTIVE_SUB_ID, activeId)
            .apply()
    }

    private fun handleAddSubscription(name: String, input: String) {
        val isEn = VpnStateManager.state.value.language == AppLanguage.EN
        if (input.startsWith("http://", true) || input.startsWith("https://", true)) {
            val currentList = VpnStateManager.state.value.subscriptions.toMutableList()
            val label = name.ifBlank { if (isEn) "Sub #${currentList.size + 1}" else "اشتراک #${currentList.size + 1}" }
            val newItem = SubscriptionItem(UUID.randomUUID().toString(), label, input)
            currentList.add(newItem)
            saveSubscriptions(currentList, newItem.id)
            VpnStateManager.update {
                it.copy(
                    subscriptions = currentList,
                    activeSubscriptionId = newItem.id,
                    currentSubUrl = newItem.url
                )
            }
            loadSubscriptionUrl(newItem.url)
        } else {
            val parsed = repo.parseSingleInput(input)
            VpnStateManager.update {
                it.copy(
                    servers = it.servers + parsed,
                    activeServer = parsed.firstOrNull() ?: it.activeServer,
                    statusMessage = if (parsed.isNotEmpty()) {
                        if (isEn) "Config added" else "کانفیگ اضافه شد"
                    } else {
                        if (isEn) "Invalid QR / config" else "کانفیگ یا QR نامعتبر است"
                    }
                )
            }
        }
    }

    private fun handleSwitchSubscription(item: SubscriptionItem) {
        if (VpnStateManager.state.value.connectionState != ConnectionState.DISCONNECTED) {
            dispatchVpnAction(EcholicenseVpnService.ACTION_DISCONNECT)
        }
        saveSubscriptions(VpnStateManager.state.value.subscriptions, item.id)
        VpnStateManager.update { it.copy(activeSubscriptionId = item.id, currentSubUrl = item.url) }
        loadSubscriptionUrl(item.url)
    }

    private fun handleDeleteSubscription(item: SubscriptionItem) {
        val updated = VpnStateManager.state.value.subscriptions.filterNot { it.id == item.id }
        val wasActive = VpnStateManager.state.value.activeSubscriptionId == item.id
        val nextActive = if (wasActive) updated.firstOrNull() else {
            updated.firstOrNull { it.id == VpnStateManager.state.value.activeSubscriptionId }
        }
        saveSubscriptions(updated, nextActive?.id)

        if (wasActive && VpnStateManager.state.value.connectionState != ConnectionState.DISCONNECTED) {
            dispatchVpnAction(EcholicenseVpnService.ACTION_DISCONNECT)
        }

        if (nextActive == null) {
            TrafficAlertWorker.cancel(this)
            SubscriptionAutoUpdateWorker.cancel(this)
            VpnStateManager.update {
                it.copy(
                    subscriptions = updated,
                    activeSubscriptionId = null,
                    currentSubUrl = "",
                    subscriptionInfo = SubscriptionInfo(),
                    servers = emptyList(),
                    activeServer = null,
                    statusMessage = if (it.language == AppLanguage.EN) "Subscription deleted" else "اشتراک حذف شد"
                )
            }
        } else {
            VpnStateManager.update {
                it.copy(
                    subscriptions = updated,
                    activeSubscriptionId = nextActive.id,
                    currentSubUrl = nextActive.url
                )
            }
            if (wasActive) loadSubscriptionUrl(nextActive.url)
        }
    }

    private fun loadSubscriptionUrl(url: String) {
        lifecycleScope.launch {
            val isEn = VpnStateManager.state.value.language == AppLanguage.EN
            VpnStateManager.update {
                it.copy(statusMessage = if (isEn) "Syncing servers & HWID..." else "در حال دریافت سرورها و ثبت HWID...")
            }
            try {
                val (subInfo, servers) = repo.fetchSubscription(url, this@MainActivity)
                val sorted = repo.pingAllServers(servers)
                VpnStateManager.update { state ->
                    val preferred = if (state.aiProOnly) sorted.firstOrNull { it.isAiPro() } else sorted.firstOrNull()
                    state.copy(
                        currentSubUrl = url,
                        subscriptionInfo = subInfo,
                        servers = sorted,
                        activeServer = preferred,
                        statusMessage = if (isEn) "${sorted.size} servers ready" else "${sorted.size} سرور آماده اتصال"
                    )
                }
                TrafficAlertWorker.schedule(this@MainActivity, url)
                SubscriptionAutoUpdateWorker.schedule(this@MainActivity, replace = true)
                TrafficAlertWorker.evaluateAndNotify(this@MainActivity, subInfo)
            } catch (e: Exception) {
                VpnStateManager.update {
                    it.copy(statusMessage = "${if (isEn) "Error" else "خطا"}: ${e.localizedMessage ?: "Invalid link"}")
                }
            }
        }
    }

    private fun openTelegramBot(username: String) {
        val clean = username.trim().removePrefix("@").ifBlank { "echopress_bot" }
        val tgIntent = Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=$clean"))
        runCatching { startActivity(tgIntent) }.onFailure { openExternalUrl("https://t.me/$clean") }
    }

    private fun openExternalUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    private fun handleToggleAiPro() {
        val current = VpnStateManager.state.value
        val nextEnabled = !current.aiProOnly
        if (nextEnabled) {
            val aiServers = current.servers.filter { it.isAiPro() }
            if (aiServers.isEmpty()) {
                VpnStateManager.update {
                    it.copy(
                        aiProOnly = false,
                        statusMessage = if (it.language == AppLanguage.EN) "No AI Pro server found" else "سرور AI Pro یافت نشد"
                    )
                }
                return
            }
            val bestAi = aiServers.first()
            val isConnected = current.connectionState == ConnectionState.CONNECTED
            VpnStateManager.update { it.copy(aiProOnly = true, activeServer = bestAi) }
            if (isConnected) dispatchVpnAction(EcholicenseVpnService.ACTION_RECONNECT) else startVpnWithPermission()
        } else {
            VpnStateManager.update { it.copy(aiProOnly = false) }
        }
    }

    private fun handlePowerToggle() {
        val state = VpnStateManager.state.value
        if (state.connectionState == ConnectionState.CONNECTED || state.connectionState == ConnectionState.CONNECTING) {
            dispatchVpnAction(EcholicenseVpnService.ACTION_DISCONNECT)
            return
        }
        if (state.aiProOnly) {
            val bestAi = state.servers.firstOrNull { it.isAiPro() } ?: return
            if (state.activeServer?.isAiPro() != true) VpnStateManager.update { it.copy(activeServer = bestAi) }
        }
        if (VpnStateManager.state.value.activeServer == null) return
        startVpnWithPermission()
    }

    private fun startVpnWithPermission() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) vpnPermissionLauncher.launch(prepareIntent)
        else dispatchVpnAction(EcholicenseVpnService.ACTION_CONNECT)
    }

    private fun dispatchVpnAction(action: String) {
        ContextCompat.startForegroundService(this, Intent(this, EcholicenseVpnService::class.java).setAction(action))
    }

    private fun runSmartPing() {
        lifecycleScope.launch {
            val currentState = VpnStateManager.state.value
            val current = currentState.servers
            val isEn = currentState.language == AppLanguage.EN
            if (current.isEmpty()) {
                VpnStateManager.update {
                    it.copy(statusMessage = if (isEn) "No configs to test" else "کانفیگی برای تست وجود ندارد")
                }
                return@launch
            }
            VpnStateManager.update {
                it.copy(
                    isPinging = true,
                    statusMessage = if (isEn) "Testing ping of all ${current.size} configs..." else "در حال تست پینگ ${current.size} کانفیگ..."
                )
            }
            val sorted = repo.pingAllServers(current)
            val fastest = sorted.firstOrNull { it.pingMs > 0 }
            VpnStateManager.update { state ->
                val activeNow = state.activeServer
                val nextActive = if (state.connectionState == ConnectionState.CONNECTED && activeNow != null) {
                    sorted.firstOrNull { it.id == activeNow.id } ?: activeNow
                } else if (state.aiProOnly) {
                    sorted.firstOrNull { it.isAiPro() } ?: sorted.firstOrNull()
                } else {
                    sorted.firstOrNull()
                }
                val doneMsg = if (fastest != null) {
                    if (isEn) "Ping done • Fastest: ${fastest.pingMs}ms" else "پینگ انجام شد • سریع‌ترین: ${fastest.pingMs}ms"
                } else {
                    if (isEn) "Ping finished (check internet)" else "تست پینگ انجام شد (اینترنت را چک کنید)"
                }
                state.copy(
                    isPinging = false,
                    servers = sorted,
                    activeServer = nextActive,
                    statusMessage = doneMsg
                )
            }
        }
    }
}
