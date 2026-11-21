package com.echolicense.vpn.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.echolicense.vpn.data.ConnectionState
import com.echolicense.vpn.data.IranSplitTunnel
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
    private var connectJob: Job? = null

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
        val mtu = current.mtu.coerceIn(1200, 1500)

        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("در حال تنظیم DNS و اتصال..."))

        VpnStateManager.update {
            it.copy(
                connectionState = ConnectionState.CONNECTING,
                statusMessage = if (current.manualDns.isNotBlank()) "اتصال با DNS دستی..." else "در حال تست DNS خودکار..."
            )
        }

        connectJob?.cancel()
        connectJob = scope.launch {
            try {
                val hasGeoFiles = ensureGeoAssetsCopied()
                val resolvedDns = SmartDnsManager.resolveDns(current.manualDns)
                VpnStateManager.update {
                    it.copy(
                        activeDns = resolvedDns,
                        statusMessage = "اتصال ${server.protocol.name} (MTU $mtu) • DNS ${resolvedDns.name}"
                    )
                }

                val configJson = XrayConfigBuilder.buildConfigJson(
                    profile = server,
                    primaryDns = resolvedDns,
                    enableIranSplit = current.iranSplitEnabled,
                    mtu = mtu,
                    muxConfig = current.muxConfig,
                    hasGeoFiles = hasGeoFiles
                )
                startXrayTun(
                    configJson = configJson,
                    dnsIp = resolvedDns.ipv4,
                    mtu = mtu,
                    enableIranSplit = current.iranSplitEnabled
                )

                VpnStateManager.setConnected()
                val muxLabel = if (current.muxConfig.enabled) " • Mux" else ""
                updateNotification("متصل به ${server.name} • MTU: $mtu$muxLabel")
                startTelemetryLoop()

            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "VPN Start Error", t)
                VpnStateManager.update {
                    it.copy(
                        connectionState = ConnectionState.ERROR,
                        statusMessage = "خطا: ${t.localizedMessage ?: t.javaClass.simpleName}"
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

        tunInterface = builder.establish() ?: throw IllegalStateException("دسترسی VpnService صادر نشد.")

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

    private fun stopVpn(stopSelfNow: Boolean) {
        connectJob?.cancel()
        telemetryJob?.cancel()
        runCatching { coreController?.stopLoop() }
        coreController = null
        runCatching { tunInterface?.close() }
        tunInterface = null
        if (stopSelfNow) {
            VpnStateManager.setDisconnected()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "اتصال Echolicense VPN",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Echolicense VPN")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()

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
