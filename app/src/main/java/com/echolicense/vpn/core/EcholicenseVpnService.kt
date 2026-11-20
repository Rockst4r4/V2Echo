package com.echolicense.vpn.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.echolicense.vpn.data.ConnectionState
import com.echolicense.vpn.data.IranSplitTunnel
import com.echolicense.vpn.data.VpnProtocol
import kotlinx.coroutines.*
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.io.File

class EcholicenseVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.echolicense.vpn.CONNECT"
        const val ACTION_DISCONNECT = "com.echolicense.vpn.DISCONNECT"
        private const val CHANNEL_ID = "echolicense_vpn_channel"
        private const val NOTIF_ID = 1001
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tunInterface: ParcelFileDescriptor? = null
    private var coreController: CoreController? = null
    private var wireGuardEngine: WireGuardEngine? = null
    private var telemetryJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> stopVpn()
            ACTION_CONNECT -> startVpn()
        }
        return START_STICKY
    }

    private fun ensureGeoAssetsCopied() {
        listOf("geoip.dat", "geosite.dat").forEach { assetName ->
            val outFile = File(filesDir, assetName)
            if (!outFile.exists()) {
                runCatching {
                    assets.open(assetName).use { input ->
                        outFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        }
    }

    private fun startVpn() {
        val current = VpnStateManager.state.value
        val server = current.activeServer ?: return

        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("در حال انتخاب بهترین DNS و اتصال..."))

        VpnStateManager.update {
            it.copy(connectionState = ConnectionState.CONNECTING, statusMessage = "در حال تست DNS خودکار...")
        }

        scope.launch {
            try {
                ensureGeoAssetsCopied()
                val bestDns = SmartDnsManager.resolveBestWorkingDns()
                VpnStateManager.update {
                    it.copy(
                        activeDns = bestDns,
                        statusMessage = "متصل شدن با DNS ${bestDns.name}..."
                    )
                }

                if (server.protocol == VpnProtocol.WIREGUARD) {
                    val wg = wireGuardEngine ?: WireGuardEngine(this@EcholicenseVpnService).also {
                        wireGuardEngine = it
                    }
                    wg.connect(server.rawConfig, bestDns, current.iranSplitEnabled)
                } else {
                    startXrayTunnel(
                        configJson = XrayConfigBuilder.buildConfigJson(
                            profile = server,
                            primaryDns = bestDns,
                            enableIranSplit = current.iranSplitEnabled,
                            enableFragment = current.fragmentEnabled
                        ),
                        dnsIp = bestDns.ipv4,
                        enableIranSplit = current.iranSplitEnabled
                    )
                }

                VpnStateManager.setConnected()
                updateNotification("متصل به ${server.name} • DNS: ${bestDns.name}")
                startTelemetryLoop()

            } catch (e: Exception) {
                VpnStateManager.update {
                    it.copy(
                        connectionState = ConnectionState.ERROR,
                        statusMessage = "خطای اتصال: ${e.localizedMessage ?: "نامشخص"}"
                    )
                }
                stopVpn()
            }
        }
    }

    private fun startXrayTunnel(configJson: String, dnsIp: String, enableIranSplit: Boolean) {
        val builder = Builder()
            .setSession("Echolicense VPN")
            .setMtu(1500)
            .addAddress("10.10.10.2", 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(dnsIp)
            .addDnsServer("1.0.0.1")

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
            override fun onEmitStatus(p0: Long, p1: String?): Long = 0
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

    private fun stopVpn() {
        telemetryJob?.cancel()
        runCatching { coreController?.stopLoop() }
        coreController = null
        runCatching { tunInterface?.close() }
        tunInterface = null
        wireGuardEngine?.disconnect()
        VpnStateManager.setDisconnected()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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

    override fun onDestroy() {
        stopVpn()
        scope.cancel()
        super.onDestroy()
    }
}
