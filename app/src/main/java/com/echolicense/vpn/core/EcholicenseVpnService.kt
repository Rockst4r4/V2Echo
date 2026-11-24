package com.echolicense.vpn.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.service.quicksettings.TileService
import android.util.Log
import androidx.core.app.NotificationCompat
import com.echolicense.vpn.MainActivity
import com.echolicense.vpn.R
import com.echolicense.vpn.data.AppLanguage
import com.echolicense.vpn.data.ConnectionState
import com.echolicense.vpn.data.DnsProvider
import com.echolicense.vpn.data.IranSplitTunnel
import com.echolicense.vpn.data.VpnProtocol
import com.echolicense.vpn.tile.EcholicenseTileService
import com.echolicense.vpn.worker.AnnouncementWorker
import kotlinx.coroutines.*
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.io.File

class EcholicenseVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.echolicense.vpn.CONNECT"
        const val ACTION_DISCONNECT = "com.echolicense.vpn.DISCONNECT"
        const val ACTION_RECONNECT = "com.echolicense.vpn.RECONNECT"
        private const val TAG = "EcholicenseVPN"
        private const val CHANNEL_ID = "echolicense_vpn_channel"
        private const val NOTIF_ID = 1001
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tunInterface: ParcelFileDescriptor? = null
    private var coreController: CoreController? = null
    private var telemetryJob: Job? = null
    private var pingJob: Job? = null
    private var connectJob: Job? = null
    private var notifJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> stopVpn(stopSelfNow = true)
            ACTION_RECONNECT -> {
                stopVpn(stopSelfNow = false)
                startVpn()
            }
            ACTION_CONNECT -> startVpn()
        }
        return START_STICKY
    }

    private fun ensureGeoAssetsCopied(): Boolean {
        var allReady = true
        listOf("geoip.dat", "geosite.dat").forEach { assetName ->
            val outFile = File(filesDir, assetName)
            if (!outFile.exists() || outFile.length() == 0L) {
                runCatching {
                    assets.open(assetName).use { input ->
                        outFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }.onFailure {
                    Log.w(TAG, "Geo asset copy failed ($assetName): ${it.message}")
                    allReady = false
                }
            }
            if (!outFile.exists() || outFile.length() == 0L) allReady = false
        }
        return allReady
    }

    private fun startVpn() {
        val current = VpnStateManager.state.value
        val server = current.activeServer ?: return
        val isEn = current.language == AppLanguage.EN

        createNotificationChannel()
        startForeground(
            NOTIF_ID,
            buildNotification(if (isEn) "Optimizing DNS & MTU..." else "در حال بهینه‌سازی DNS و MTU...")
        )

        VpnStateManager.update {
            it.copy(
                connectionState = ConnectionState.CONNECTING,
                statusMessage = if (isEn) "Testing DNS & Auto-MTU..." else "در حال تست DNS و MTU خودکار..."
            )
        }

        connectJob?.cancel()
        connectJob = scope.launch {
            try {
                val hasGeoFiles = ensureGeoAssetsCopied()
                val resolvedDns = SmartDnsManager.resolveDns(current.manualDns)
                val finalMtu = if (current.autoMtuEnabled) {
                    if (server.protocol == VpnProtocol.WIREGUARD) {
                        AutoMtuManager.detectOptimalMtu(server.protocol)
                    } else {
                        1500
                    }
                } else {
                    current.mtu.coerceIn(1200, 1500)
                }

                VpnStateManager.update {
                    it.copy(
                        activeDns = resolvedDns,
                        mtu = finalMtu,
                        statusMessage = if (isEn) {
                            "Connecting ${server.protocol.name} (MTU $finalMtu) • ${resolvedDns.name}"
                        } else {
                            "اتصال ${server.protocol.name} (MTU $finalMtu) • DNS ${resolvedDns.name}"
                        }
                    )
                }

                val probePort = runCatching { java.net.ServerSocket(0).use { it.localPort } }.getOrDefault(10809)
                val configJson = XrayConfigBuilder.buildConfigJson(
                    profile = server,
                    primaryDns = resolvedDns,
                    enableIranSplit = current.iranSplitEnabled,
                    enableAdBlocker = current.adBlockerEnabled,
                    mtu = finalMtu,
                    muxConfig = current.muxConfig,
                    hasGeoFiles = hasGeoFiles,
                    probePort = probePort
                )
                startXrayTun(
                    configJson = configJson,
                    dnsIp = resolvedDns.ipv4,
                    mtu = finalMtu,
                    enableIranSplit = current.iranSplitEnabled
                )

                VpnStateManager.setConnected()
                notifJob?.cancel()
                notifJob = scope.launch { delay(1500); while (isActive) { runCatching { AnnouncementWorker.fetchAndProcess(this@EcholicenseVpnService) }; delay(30_000) } }
                runCatching {
                    TileService.requestListeningState(
                        this@EcholicenseVpnService,
                        ComponentName(this@EcholicenseVpnService, EcholicenseTileService::class.java)
                    )
                }
                val muxLabel = if (current.muxConfig.enabled) " • Mux" else ""
                updateNotification("${server.name} • MTU: $finalMtu$muxLabel")
                startTelemetryLoop()
                startLivePingLoop(probePort)

            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "VPN Start Error", t)
                VpnStateManager.update {
                    it.copy(
                        connectionState = ConnectionState.ERROR,
                        statusMessage = "${if (isEn) "Error" else "خطا"}: ${t.localizedMessage ?: t.javaClass.simpleName}"
                    )
                }
                stopVpn(stopSelfNow = true)
            }
        }
    }

    private fun startXrayTun(
        configJson: String,
        dnsIp: String,
        mtu: Int,
        enableIranSplit: Boolean
    ) {
        val safeDnsIp = if (SmartDnsManager.isValidIpv4(dnsIp)) dnsIp else "1.1.1.1"
        val builder = Builder()
            .setSession("Echolicense VPN")
            .setMtu(mtu)
            .addAddress("10.10.10.2", 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(safeDnsIp)
            .addDnsServer("1.0.0.1")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        val bypassPackages = if (enableIranSplit) {
            IranSplitTunnel.getInstalledBypassPackages(this)
        } else {
            listOf(packageName)
        }
        bypassPackages.forEach { pkg ->
            runCatching { builder.addDisallowedApplication(pkg) }
        }

        tunInterface = builder.apply {
                if (com.echolicense.vpn.core.VpnStateManager.state.value.lanMirrorBypassEnabled) {
                    runCatching { allowBypass() }
                    com.echolicense.vpn.data.IranSplitTunnel.getInstalledLanMirrorPackages(this@EcholicenseVpnService).forEach { pkg ->
                        runCatching { addDisallowedApplication(pkg) }
                    }
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        listOf(
                            "10.0.0.0" to 8,
                            "169.254.0.0" to 16,
                            "172.16.0.0" to 12,
                            "192.168.0.0" to 16,
                            "224.0.0.0" to 4,
                            "255.255.255.255" to 32
                        ).forEach { (ip, prefix) ->
                            runCatching {
                                excludeRoute(android.net.IpPrefix(java.net.InetAddress.getByName(ip), prefix))
                            }
                        }
                    }
                }
            }.establish() ?: throw IllegalStateException("VpnService permission denied.")

        Libv2ray.initCoreEnv(filesDir.absolutePath, "")
        val controller = Libv2ray.newCoreController(object : CoreCallbackHandler {
            override fun startup(): Long = 0
            override fun shutdown(): Long = 0
            override fun onEmitStatus(p0: Long, p1: String?): Long {
                if (!p1.isNullOrBlank()) Log.i(TAG, "Xray status [$p0]: $p1")
                return 0
            }
        })
        controller.startLoop(configJson, tunInterface!!.fd)
        coreController = controller
    }

    private fun startTelemetryLoop() {
        telemetryJob?.cancel()
        pingJob?.cancel()
        telemetryJob = scope.launch {
            var lastRx = TrafficStats.getTotalRxBytes()
            var lastTx = TrafficStats.getTotalTxBytes()
            var seconds = 0L

            while (isActive && VpnStateManager.state.value.connectionState == ConnectionState.CONNECTED) {
                delay(1000)
                seconds++
                val nowRx = TrafficStats.getTotalRxBytes()
                val nowTx = TrafficStats.getTotalTxBytes()
                val downKbps = ((nowRx - lastRx).coerceAtLeast(0) / 1024)
                val upKbps = ((nowTx - lastTx).coerceAtLeast(0) / 1024)
                lastRx = nowRx
                lastTx = nowTx

                VpnStateManager.update {
                    it.copy(
                        downloadSpeedKbps = downKbps,
                        uploadSpeedKbps = upKbps,
                        connectedSeconds = seconds
                    )
                }
            }
        }
    }


    private fun startLivePingLoop(probePort: Int) {
        pingJob?.cancel()
        pingJob = scope.launch {
            delay(1400)
            while (isActive && VpnStateManager.state.value.connectionState == ConnectionState.CONNECTED) {
                val directDeferred = async(Dispatchers.IO) {
                    LivePingMonitor.probeDirect1111(
                        tcpProtector = { sock -> runCatching { protect(sock) } },
                        udpProtector = { ds -> runCatching { protect(ds) } }
                    )
                }
                val vpnDeferred = async(Dispatchers.IO) {
                    LivePingMonitor.probeViaLocalHttpProxy(probePort)
                }
                val dMs = directDeferred.await()
                val vMs = vpnDeferred.await()

                VpnStateManager.update { st ->
                    val nextDirectHist = if (dMs > 0) (st.directPingHistory + dMs).takeLast(16) else st.directPingHistory
                    val nextVpnHist = if (vMs > 0) (st.vpnPingHistory + vMs).takeLast(16) else st.vpnPingHistory
                    val nextDirectAttempts = (st.directAttempts + (dMs > 0)).takeLast(10)
                    val nextVpnAttempts = (st.vpnAttempts + (vMs > 0)).takeLast(10)
                    st.copy(
                        directPingMs = dMs,
                        directJitterMs = LivePingMonitor.computeJitter(nextDirectHist),
                        directPingHistory = nextDirectHist,
                        vpnPingMs = vMs,
                        vpnJitterMs = LivePingMonitor.computeJitter(nextVpnHist),
                        vpnPingHistory = nextVpnHist,
                        directAttempts = nextDirectAttempts,
                        vpnAttempts = nextVpnAttempts
                    )
                }
                delay(2500)
            }
        }
    }

    private fun stopVpn(stopSelfNow: Boolean) {
        notifJob?.cancel()
        connectJob?.cancel()
        telemetryJob?.cancel()
        runCatching { coreController?.stopLoop() }
        coreController = null
        runCatching { tunInterface?.close() }
        tunInterface = null
        if (stopSelfNow) {
            VpnStateManager.setDisconnected()
            runCatching {
                TileService.requestListeningState(
                    this,
                    ComponentName(this, EcholicenseTileService::class.java)
                )
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Echolicense VPN",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            NOTIF_ID,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disconnectIntent = Intent(this, EcholicenseVpnService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this,
            NOTIF_ID + 1,
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Echolicense VPN")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_qs_tile)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", disconnectPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    override fun onRevoke() {
        stopVpn(stopSelfNow = true)
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn(stopSelfNow = true)
        scope.cancel()
        super.onDestroy()
    }
}
