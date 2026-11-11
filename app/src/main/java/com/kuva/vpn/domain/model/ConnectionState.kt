package com.kuva.vpn.domain.model

/** Real tunnel state — driven only by the VPN service, never by the UI. */
sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    // profileId lets list rows identify THE connected config by id —
    // name-based matching lit the green ring on EVERY same-named row
    // (duplicate rows from a subscription refresh all glowed).
    data class Connecting(val profileName: String?, val profileId: Long? = null) : ConnectionState()

    /** Swift engine: endpoint scan in progress (0..100) — different UI copy. */
    data class SwiftConnecting(val profileName: String?, val progressPct: Int = 0) : ConnectionState()
    data class Connected(val profileName: String?, val profileId: Long? = null) : ConnectionState()
    data object Disconnecting : ConnectionState()
    data class Error(val message: String) : ConnectionState()

    val isTunnelUp: Boolean get() = this is Connected
    val isBusy: Boolean get() = this is Connecting || this is SwiftConnecting || this is Disconnecting
}

/** Live stats snapshot, all values measured — never simulated. */
data class LiveStats(
    val pingMs: Int? = null,
    val downSpeedBps: Long = 0,
    val upSpeedBps: Long = 0,
    val totalDownBytes: Long = 0,
    val totalUpBytes: Long = 0,
    val connectedSinceEpochMs: Long? = null,
    val elapsedSeconds: Long = 0,      // ticking connection duration (1 Hz)
    val livePingMs: Int? = null,       // periodic latency measured while connected
)

/** Result of an exit-IP / geolocation lookup through the tunnel. */
data class ExitIpInfo(
    val ip: String,
    val countryCode: String? = null,  // ISO-3166 alpha-2 ("DE") — drives the flag
    val country: String?,   // display name ("Germany")
    val city: String?,
    val isp: String?,
)
