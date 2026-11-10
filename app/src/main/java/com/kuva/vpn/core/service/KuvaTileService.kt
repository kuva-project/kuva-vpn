package com.kuva.vpn.core.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.kuva.vpn.core.control.VpnControl
import com.kuva.vpn.domain.model.ConnectionState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Quick Settings tile: one tap connect / disconnect. */
@AndroidEntryPoint
class KuvaTileService : TileService() {

    @Inject lateinit var vpnControl: VpnControl
    @Inject lateinit var connRepo: com.kuva.vpn.domain.repository.ConnectionRepository

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        vpnControl.toggle(applicationContext)
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        when (connRepo.state.value) {
            is ConnectionState.Connected -> {
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = "Connected"
            }
            is ConnectionState.Connecting -> {
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = "Connecting…"
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = "Disconnected"
            }
        }
        tile.updateTile()
    }
}
