package com.kuva.vpn.domain.repository

import com.kuva.vpn.domain.model.ConnectionState
import com.kuva.vpn.domain.model.ExitIpInfo
import com.kuva.vpn.domain.model.LiveStats
import com.kuva.vpn.domain.model.Profile
import kotlinx.coroutines.flow.StateFlow

/**
 * Single source of truth for tunnel state, live stats and connection history.
 * Written by KuvaVpnService + diagnostics workers, observed by the UI.
 */
interface ConnectionRepository {
    val state: StateFlow<ConnectionState>
    val stats: StateFlow<LiveStats>
    val exitIp: StateFlow<ExitIpInfo?>
    val swiftStage: StateFlow<String?>

    fun setState(s: ConnectionState)
    fun setActiveProfile(p: Profile)
    fun updateStats(transform: (LiveStats) -> LiveStats)
    fun setExitIp(info: ExitIpInfo?)
    fun clearExitIp()

    /** Swift connect stage key (see SwiftCoreManager.ProgressStages); null = none. */
    fun setSwiftStage(key: String?)
}
