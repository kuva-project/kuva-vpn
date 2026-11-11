package com.kuva.vpn.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kuva.vpn.core.control.VpnControl
import com.kuva.vpn.core.diagnostics.ConfigTester
import com.kuva.vpn.core.diagnostics.ExitIpChecker
import com.kuva.vpn.data.settings.SettingsRepository
import com.kuva.vpn.domain.model.ConnectionState
import com.kuva.vpn.domain.model.ExitIpInfo
import com.kuva.vpn.domain.model.LiveStats
import com.kuva.vpn.domain.model.Profile
import com.kuva.vpn.domain.repository.ConnectionRepository
import com.kuva.vpn.domain.repository.ProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlinx.coroutines.flow.map

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val vpnControl: VpnControl,
    private val connRepo: ConnectionRepository,
    private val profileRepo: ProfileRepository,
    private val settings: SettingsRepository,
    private val exitIpChecker: ExitIpChecker,
    private val tester: ConfigTester,
) : ViewModel() {

    @Volatile private var fastestBusy = false

    val state: StateFlow<ConnectionState> = connRepo.state
    val stats: StateFlow<LiveStats> = connRepo.stats
    val exitIp: StateFlow<ExitIpInfo?> = connRepo.exitIp

    /** user's DIRECT (no-tunnel) public IP / location, shown when disconnected */
    private val _directExitIp = MutableStateFlow<ExitIpInfo?>(null)
    val directExitIp: StateFlow<ExitIpInfo?> = _directExitIp.asStateFlow()

    val selectedProfile: StateFlow<Profile?> = profileRepo.observeSelected()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** dashboard engine switch: "xray" (configs) vs "swift" (configless) */
    val engineMode: StateFlow<String> = settings.settings
        .map { it.engineMode }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "xray")

    init {
        // refresh Exit IP / Location automatically on every successful connect.
        // IMPORTANT: reads connRepo.state (constructor param, already initialized),
        // never the `state` property, and delays inside a CHILD coroutine so the
        // collection itself is never suspended.
        // direct IP right away (dashboard shows it while disconnected)
        viewModelScope.launch { _directExitIp.value = exitIpChecker.lookupDirect() }
        viewModelScope.launch {
            var wasConnected = false
            connRepo.state.collect { st ->
                val connected = st is ConnectionState.Connected
                if (connected && !wasConnected) {
                    launch {
                        kotlinx.coroutines.delay(2500)   // let the tunnel settle
                        if (connRepo.state.value is ConnectionState.Connected) {
                            val port = settings.current.socksPort
                            connRepo.setExitIp(exitIpChecker.lookup(port))
                        }
                    }
                } else if (!connected && wasConnected) {
                    // tunnel dropped — refresh the direct (no-tunnel) IP
                    launch { _directExitIp.value = exitIpChecker.lookupDirect() }
                }
                wasConnected = connected
            }
        }
    }

    fun clearExitIp() = connRepo.clearExitIp()

    fun setEngineMode(mode: String) = viewModelScope.launch { settings.setEngineMode(mode) }

    fun toggle() {
        vpnControl.toggle(app())
    }

    /** stop the tunnel if running (used by the exit dialog) */
    fun requestDisconnect() {
        vpnControl.requestStop(app())
    }

    /**
     * Pings EVERY config of the currently displayed list first, then connects
     * to the lowest-latency one. Ignores taps while a run is in progress.
     */
    fun connectFastest() {
        if (fastestBusy) return
        fastestBusy = true
        viewModelScope.launch {
            try {
                val pool = profileRepo.all(Profile.Scope.PERSONAL)
                    .ifEmpty { profileRepo.all(Profile.Scope.FREE) }
                if (pool.isEmpty()) return@launch
                profileRepo.clearPingFor(pool.map { it.id })
                val pingResults = java.util.concurrent.ConcurrentHashMap<Long, Int>()
                tester.testPingBatch(pool) { id, ms -> pingResults[id] = ms }
                profileRepo.markTestedBatch(
                    pingResults.filterValues { it > 0 }.map { it.toPair() }
                )
                val best = profileRepo.bestByPing() ?: profileRepo.bestByPerformance()
                best?.let {
                    profileRepo.select(it.id)
                    vpnControl.requestStart(app())
                }
            } finally {
                fastestBusy = false
            }
        }
    }

    fun openServerPicker() { /* navigation event handled via shared flow */ }
    fun openSettings() { /* navigation event handled via shared flow */ }

    /** After Connected: verify real exit IP through the tunnel. */
    fun verifyExitIp() {
        viewModelScope.launch {
            if (connRepo.state.value is ConnectionState.Connected) {
                val port = settings.current.socksPort
                val info = exitIpChecker.lookup(port)
                connRepo.setExitIp(info)
            }
        }
    }

    private fun app() = com.kuva.vpn.KuvaApplication.instance!!
}
