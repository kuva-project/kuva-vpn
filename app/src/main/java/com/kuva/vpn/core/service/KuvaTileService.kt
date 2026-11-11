package com.kuva.vpn.core.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.kuva.vpn.core.control.VpnControl
import com.kuva.vpn.domain.model.ConnectionState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Quick Settings tile: one tap connect / disconnect.
 *
 * The tile must mirror the REAL connection state at all times: a stale tile
 * makes the tap act inverted (tap "connect" while connected, and vice versa).
 * Besides repainting on onStartListening/onClick, we COLLECT the state flow —
 * every transition repaints the tile even while the panel stays open, because
 * the system keeps this service bound as long as the panel is visible.
 */
@AndroidEntryPoint
class KuvaTileService : TileService() {

    @Inject lateinit var vpnControl: VpnControl
    @Inject lateinit var connRepo: com.kuva.vpn.domain.repository.ConnectionRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        // repaint on every state transition, even if the panel is never reopened
        scope.launch {
            connRepo.state.collect { repaint(it) }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        repaint(connRepo.state.value)
    }

    override fun onClick() {
        super.onClick()
        // decide from the CURRENT repo state, not from what the tile shows:
        // the tile icon may lag the flow by a frame, the flow never lies.
        vpnControl.toggle(applicationContext)
        repaint(connRepo.state.value)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun repaint(state: ConnectionState) {
        val tile = qsTile ?: return

        when (state) {
            is ConnectionState.Connected -> {
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = "Connected"
            }
            is ConnectionState.Connecting -> {
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = "Connecting…"
            }
            is ConnectionState.SwiftConnecting -> {
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = "Searching for route…"
            }
            is ConnectionState.Disconnecting -> {
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = "Disconnecting…"
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = "Disconnected"
            }
        }
        tile.updateTile()
    }
}
