package com.kuva.vpn.data.repository

import com.kuva.vpn.domain.model.ConnectionState
import com.kuva.vpn.domain.model.ExitIpInfo
import com.kuva.vpn.domain.model.LiveStats
import com.kuva.vpn.domain.model.Profile
import com.kuva.vpn.domain.repository.ConnectionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectionRepositoryImpl @Inject constructor(
    private val profileRepo: com.kuva.vpn.domain.repository.ProfileRepository,
) : ConnectionRepository {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _stats = MutableStateFlow(LiveStats())
    override val stats: StateFlow<LiveStats> = _stats.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        // Live duration + periodic ping while connected.
        // duration = connectedSinceEpochMs (wall clock); ping = measured on the
        // active profile via markTested, so the tile survives process restarts too.
        scope.launch {
            while (isActive) {
                val st = _state.value
                val since = _stats.value.connectedSinceEpochMs
                // if != guards the WRITE, not the tick: avoids 60 wasted
                // StateFlow emissions per minute (same value, no UI change)
                if (st is ConnectionState.Connected && since != null) {
                    val sec = (System.currentTimeMillis() - since) / 1000
                    if (sec != _stats.value.elapsedSeconds) {
                        updateStats { it.copy(elapsedSeconds = sec) }
                    }
                } else if (st !is ConnectionState.Connected) {
                    // reset the timer tile after disconnect
                    if (_stats.value.elapsedSeconds != 0L) {
                        updateStats { it.copy(elapsedSeconds = 0, livePingMs = null) }
                    }
                }
                delay(1000)
            }
        }
        scope.launch {
            while (isActive) {
                val connected = _state.value is ConnectionState.Connected
                if (connected && activeProfile != null) {
                    runCatching { pingProfile(activeProfile!!) }
                    delay(60_000)   // refresh every minute while connected
                } else {
                    delay(1_500)    // poll lightly until the user connects
                }
            }
        }
    }

    /** TCP-connect latency to the profile's first host:port (SOCKS/HTTP ports work too). */
    private fun pingProfile(profile: Profile) {
        val host = profile.address
        val port = profile.port
        if (host.isBlank() || port <= 0) return
        Thread {
            try {
                val sock = java.net.Socket()
                val t0 = System.currentTimeMillis()
                sock.connect(java.net.InetSocketAddress(host, port), 4000)
                val ms = (System.currentTimeMillis() - t0).toInt()
                sock.close()
                _stats.value = _stats.value.copy(livePingMs = ms)
                // no markTested here: the periodic ping must NEVER overwrite the
                // speed-test result already stored on the profile
            } catch (_: Exception) {
                _stats.value = _stats.value.copy(livePingMs = -1)
            }
        }.start()
    }


    private val _exitIp = MutableStateFlow<ExitIpInfo?>(null)
    override val exitIp: StateFlow<ExitIpInfo?> = _exitIp.asStateFlow()

    private val _swiftStage = MutableStateFlow<String?>(null)
    override val swiftStage: StateFlow<String?> = _swiftStage.asStateFlow()

    @Volatile private var activeProfile: Profile? = null

    override fun setState(s: ConnectionState) {
        // disconnect (user or auto-reconnect teardown) blanks verified exit info
        if (s is ConnectionState.Disconnected || s is ConnectionState.Disconnecting) {
            _exitIp.value = null
        }
        // stage label only makes sense while connecting
        if (s !is ConnectionState.Connecting && s !is ConnectionState.SwiftConnecting) {
            _swiftStage.value = null
        }
        // fully disconnected: clear ping + speeds + traffic tiles too
        if (s is ConnectionState.Disconnected && _stats.value != LiveStats()) {
            _stats.value = LiveStats()
        }
        _state.value = s
    }

    override fun setActiveProfile(p: Profile) {
        activeProfile = p
    }

    override fun setSwiftStage(key: String?) {
        _swiftStage.value = key
    }

    override fun updateStats(transform: (LiveStats) -> LiveStats) {
        _stats.value = transform(_stats.value)
    }

    override fun setExitIp(info: ExitIpInfo?) { _exitIp.value = info }

    override fun clearExitIp() { _exitIp.value = null }
}
