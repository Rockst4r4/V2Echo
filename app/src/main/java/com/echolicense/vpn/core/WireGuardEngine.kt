package com.echolicense.vpn.core

import android.content.Context
import com.echolicense.vpn.data.DnsProvider
import com.echolicense.vpn.data.IranSplitTunnel
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.BufferedReader
import java.io.StringReader

class WireGuardEngine(private val context: Context) {
    private val backend: GoBackend by lazy { GoBackend(context.applicationContext) }

    private val tunnel = object : Tunnel {
        override fun getName(): String = "echolicense_wg"
        override fun onStateChange(newState: Tunnel.State) {
            when (newState) {
                Tunnel.State.UP -> VpnStateManager.setConnected()
                Tunnel.State.DOWN -> VpnStateManager.setDisconnected()
                Tunnel.State.TOGGLE -> Unit
            }
        }
    }

    fun connect(
        rawWireGuardConfig: String,
        activeDns: DnsProvider,
        enableIranSplit: Boolean
    ) {
        val patchedConfig = injectDnsAndSplit(rawWireGuardConfig, activeDns, enableIranSplit)
        val parsed = Config.parse(BufferedReader(StringReader(patchedConfig)))
        backend.setState(tunnel, Tunnel.State.UP, parsed)
    }

    fun disconnect() {
        runCatching {
            backend.setState(tunnel, Tunnel.State.DOWN, null)
        }
    }

    private fun injectDnsAndSplit(
        raw: String,
        dns: DnsProvider,
        enableIranSplit: Boolean
    ): String {
        val excludedApps = if (enableIranSplit) {
            IranSplitTunnel.getInstalledBypassPackages(context).joinToString(", ")
        } else {
            context.packageName
        }

        val out = mutableListOf<String>()
        var inInterface = false
        var dnsWritten = false
        var excludedWritten = false

        for (line in raw.lines()) {
            val trimmed = line.trim()
            if (trimmed.equals("[Interface]", ignoreCase = true)) {
                inInterface = true
                out.add(line)
                continue
            }
            if (trimmed.startsWith("[") && inInterface) {
                if (!dnsWritten) out.add("DNS = ${dns.ipv4}, 1.0.0.1")
                if (!excludedWritten) out.add("ExcludedApplications = $excludedApps")
                inInterface = false
            }

            if (inInterface && trimmed.startsWith("DNS", ignoreCase = true)) {
                out.add("DNS = ${dns.ipv4}, 1.0.0.1")
                dnsWritten = true
            } else if (inInterface && trimmed.startsWith("ExcludedApplications", ignoreCase = true)) {
                out.add("ExcludedApplications = $excludedApps")
                excludedWritten = true
            } else {
                out.add(line)
            }
        }

        if (inInterface) {
            if (!dnsWritten) out.add("DNS = ${dns.ipv4}, 1.0.0.1")
            if (!excludedWritten) out.add("ExcludedApplications = $excludedApps")
        }
        return out.joinToString("\n")
    }
}
