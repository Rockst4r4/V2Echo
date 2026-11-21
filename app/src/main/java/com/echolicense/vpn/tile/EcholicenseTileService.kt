package com.echolicense.vpn.tile

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.echolicense.vpn.MainActivity
import com.echolicense.vpn.core.EcholicenseVpnService
import com.echolicense.vpn.core.VpnStateManager
import com.echolicense.vpn.data.ConnectionState

class EcholicenseTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        syncTile()
    }

    override fun onClick() {
        super.onClick()
        val ui = VpnStateManager.state.value
        val isRunning = ui.connectionState == ConnectionState.CONNECTED ||
            ui.connectionState == ConnectionState.CONNECTING

        if (isRunning) {
            val intent = Intent(this, EcholicenseVpnService::class.java)
                .setAction(EcholicenseVpnService.ACTION_DISCONNECT)
            ContextCompat.startForegroundService(this, intent)
            syncTile(forceState = Tile.STATE_INACTIVE)
            return
        }

        val needsPermission = VpnService.prepare(this) != null
        if (needsPermission || ui.activeServer == null) {
            openAppFromTile()
            return
        }

        val intent = Intent(this, EcholicenseVpnService::class.java)
            .setAction(EcholicenseVpnService.ACTION_CONNECT)
        ContextCompat.startForegroundService(this, intent)
        syncTile(forceState = Tile.STATE_ACTIVE)
    }

    private fun openAppFromTile() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun syncTile(forceState: Int? = null) {
        val tile = qsTile ?: return
        val state = VpnStateManager.state.value.connectionState
        val active = forceState == Tile.STATE_ACTIVE ||
            (forceState == null && (state == ConnectionState.CONNECTED || state == ConnectionState.CONNECTING))

        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Echolicense"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when (state) {
                ConnectionState.CONNECTED -> "Connected"
                ConnectionState.CONNECTING -> "Connecting..."
                else -> "Off"
            }
        }
        tile.updateTile()
    }
}
