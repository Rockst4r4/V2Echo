package com.echolicense.vpn.tile

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
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
        val state = VpnStateManager.state.value.connectionState
        val action = if (state == ConnectionState.CONNECTED) {
            EcholicenseVpnService.ACTION_DISCONNECT
        } else {
            EcholicenseVpnService.ACTION_CONNECT
        }
        val intent = Intent(this, EcholicenseVpnService::class.java).setAction(action)
        ContextCompat.startForegroundService(this, intent)
        syncTile()
    }

    private fun syncTile() {
        val tile = qsTile ?: return
        val connected = VpnStateManager.state.value.connectionState == ConnectionState.CONNECTED
        tile.state = if (connected) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (connected) "Echolicense (وصل)" else "Echolicense"
        tile.updateTile()
    }
}
