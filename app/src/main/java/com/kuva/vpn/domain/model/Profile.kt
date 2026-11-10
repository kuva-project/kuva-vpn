package com.kuva.vpn.domain.model

/**
 * Normalized outbound configuration, produced by ShareLinkParser / manual entry /
 * QR scan / raw Xray JSON import. Protocol-agnostic: every supported inbound
 * share format maps to this model, and XrayConfigBuilder renders the full JSON.
 */
data class Profile(
    val id: Long = 0,
    /** "free" or "personal" — repositories are strictly separated. */
    val scope: Scope,
    val name: String,
    val protocol: Protocol,
    val address: String,
    val port: Int,
    val uuid: String? = null,          // vless/vmess id, trojan password, ss password
    val method: String? = null,        // shadowsocks cipher, vmess security (scy)
    val alterId: Int = 0,              // vmess
    val flow: String? = null,          // vless flow (xtls-rprx-vision)
    val encryption: String? = null,    // vless encryption (usually "none")
    val network: Transport = Transport.TCP,
    val security: Security = Security.NONE,
    val sni: String? = null,
    val host: String? = null,          // ws/http host header
    val path: String? = null,          // ws/http path
    val serviceName: String? = null,   // grpc
    val alpn: String? = null,
    val fingerprint: String? = null,   // uTLS fingerprint (chrome/firefox/...)
    val publicKey: String? = null,     // REALITY pbk
    val shortId: String? = null,       // REALITY sid
    val spiderX: String? = null,       // REALITY spx
    val allowInsecure: Boolean = false,
    // metadata
    val source: String? = null,        // source attribution (free configs)
    val discoveredAt: Long = System.currentTimeMillis(),
    val fingerprintHash: String? = null, // dedup hash
    val pingMs: Int? = null,           // null = untested, -1 = dead
    val speedDownKbps: Double? = null,
    val speedUpKbps: Double? = null,
    val lastTestedAt: Long? = null,
    val country: String? = null,       // ISO code, verified geolocation
    val isFavorite: Boolean = false,
    val isFromSearch: Boolean = false, // "new" tab of the free repo
    val inVault: Boolean = false,      // true = lives in the vault pane
    val subscriptionId: Long? = null,  // personal: owning subscription
    val rawJson: String? = null,       // original raw Xray JSON, if imported that way
) {
    enum class Scope { FREE, PERSONAL }
    enum class Protocol { VLESS, VMESS, TROJAN, SHADOWSOCKS, SOCKS, HTTP }
    enum class Transport { TCP, WS, GRPC, HTTP, HTTPUPGRADE, XHTTP, KCP, QUIC }
    enum class Security { NONE, TLS, REALITY }

    val displayAddress: String get() = "$address:$port"

    /** Endpoint host:port used for TCP latency probing. */
    val tcpEndpoint: String get() = address
    val tcpPort: Int get() = port

    companion object {
        /** Stable content fingerprint for duplicate detection. */
        fun fingerprintOf(p: Profile): String {
            val core = listOf(
                p.protocol.name, p.address.lowercase(), p.port,
                p.uuid, p.method, p.network.name, p.security.name,
                p.sni, p.host, p.path, p.serviceName, p.publicKey, p.shortId
            ).joinToString("|")
            return Integer.toHexString(core.hashCode()) + ":" +
                Integer.toHexString(core.reversed().hashCode())
        }
    }
}
