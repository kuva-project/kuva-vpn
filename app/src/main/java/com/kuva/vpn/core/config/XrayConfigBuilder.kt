package com.kuva.vpn.core.config

import com.kuva.vpn.domain.model.Profile
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds a complete Xray-core JSON config for the VPN tunnel:
 * tun inbound via socks on loopback + the selected outbound + routing + DNS.
 * The tun->socks data path is hev-socks5-tunnel; Xray only sees a socks inbound.
 */
object XrayConfigBuilder {

    data class Options(
        val socksPort: Int,
        val httpPort: Int,
        val dnsServers: List<String>,
        val bypassLan: Boolean,
        val enableStats: Boolean = true,
        val logLevel: String = "info",
        // when set, the core writes access/error logs to <logDir>/xray_*.log —
        // core log lines do NOT reach onEmitStatus (they go to stdout, which is
        // invisible on Android), so file logging is the only way to see dial
        // failures / rejected connections during e.g. a failed Google signup.
        val logDir: String? = null,
        // server domain -> IP pins merged into dns.hosts. Breaks the
        // server-resolution recursion now that the DNS module's upstream runs
        // through the proxy: resolving the proxy server's own domain via the
        // core would need the proxy dial that needs the resolution first.
        // v2Box does the same by embedding the server IP in dns.hosts.
        val dnsHostPins: Map<String, String> = emptyMap(),
        // geosite:ir / geoip:ir -> direct (Iranian banks & gov sites reject
        // foreign exit IPs, and it saves tunnel traffic). Needs the bundled
        // geoip.dat / geosite.dat in the core asset dir.
        val bypassIran: Boolean = true,
    )

    fun build(profile: Profile, opts: Options): String {
        val root = JSONObject()

        val log = JSONObject()
        if (opts.logDir != null) {
            log.put("access", opts.logDir + "/xray_access.log")
            log.put("error", opts.logDir + "/xray_error.log")
        }
        log.put("loglevel", opts.logLevel)
        root.put("log", log)

        // ---------- inbounds ----------
        val inbounds = JSONArray()
        val socksIn = JSONObject()
            .put("tag", "socks-in")
            .put("listen", "127.0.0.1")
            .put("port", opts.socksPort)
            .put("protocol", "socks")
            .put("settings", JSONObject()
                .put("udp", true)
                .put("auth", "noauth"))
            .put("sniffing", JSONObject()
                .put("enabled", true)
                .put("destOverride", JSONArray(listOf("http", "tls", "quic")))
                // routeOnly=false (REVERTED fixes_86): rewrite destination IP->domain.
                // ROOT CAUSE of Google signup failing while v2Box worked with the
                // SAME config: on WiFi Google prefers IPv6, so the phone connects
                // with a raw IPv6 destination. With routeOnly=true that IPv6 address
                // is forwarded verbatim to the server, which must then dial IPv6
                // itself — our AWS exit has no usable v6 egress, so every Google v6
                // connection died ("Something went wrong") while IPv4 traffic (IP
                // checks) worked. v2Box ships routeOnly:false + fakedns: the sniffer
                // rewrites the dest back to the DOMAIN, the server resolves it with
                // its own (working) resolver and connects over IPv4. Same behavior,
                // now identical here. Routing rules are IP/port based, unaffected.
                .put("routeOnly", false))
        inbounds.put(socksIn)
        val httpIn = JSONObject()
            .put("tag", "http-in")
            .put("listen", "127.0.0.1")
            .put("port", opts.httpPort)
            .put("protocol", "http")
            .put("settings", JSONObject())
        inbounds.put(httpIn)
        // NOTE: no api-in inbound. The stats API is unused (traffic numbers
        // come from the hev tunnel counters) and it bound socksPort+1, which
        // COLLIDED with http-in (also socksPort+1) -> the core failed to
        // start, showing as connect errors or a dead no-traffic tunnel.
        root.put("inbounds", inbounds)

        // ---------- outbound ----------
        val obs = JSONArray()
        obs.put(buildOutbound(profile))
        obs.put(blockedOutbound())
        obs.put(directOutbound())
        // built-in resolver: answers plaintext DNS queries (routed below)
        obs.put(JSONObject().put("tag", "dns-out").put("protocol", "dns"))
        root.put("outbounds", obs)

        // ---------- DNS (all through tunnel = leak protection) ----------
        val dns = JSONObject()
        val servers = JSONArray()
        // Force TCP for the upstream resolver: UDP-through-proxy is dead on
        // most free servers, which is the classic "connected but NOTHING
        // opens" failure (DNS never resolves). Xray relays TCP fine.
        opts.dnsServers.forEach { v ->
            servers.put(if (v.contains("://")) v else "tcp://" + v)
        }
        dns.put("servers", servers)
        // inbound tag carried by the DNS module's OWN upstream queries — the
        // routing rule below sends them through the tunnel (v2Box parity).
        dns.put("tag", "dns-module")
        val hosts = JSONObject()
        if (opts.bypassLan) hosts.put("dns.google", "8.8.8.8")
        opts.dnsHostPins.forEach { (d, ip) -> hosts.put(d, ip) }
        if (hosts.length() > 0) dns.put("hosts", hosts)
        // UseIP (default): allow AAAA answers too. Forced UseIPv4 stripped every
        // AAAA record, pushing apps onto a different path than v2Box for the same
        // config (Google signup failed in our app, worked in v2Box).
        dns.put("queryStrategy", "UseIP")
        dns.put("disableCache", false)
        root.put("dns", dns)

        // ---------- routing ----------
        val rules = JSONArray()
        // block ads port 80 quic optional — keep minimal & predictable
        if (opts.bypassLan) {
            rules.put(JSONObject()
                .put("type", "field")
                .put("ip", JSONArray(listOf("geoip:private")))
                .put("outboundTag", "direct"))
        }
        // Iranian sites & IPs -> DIRECT (v2rayNG/v2Box "bypass Iran" parity).
        // BEFORE the proxy rules so they win; domains match via TUN sniffing.
        if (opts.bypassIran) {
            rules.put(JSONObject()
                .put("type", "field")
                .put("domain", JSONArray(listOf("ext:geosite.dat:CATEGORY-IR")))
                .put("outboundTag", "direct"))
            rules.put(JSONObject()
                .put("type", "field")
                .put("ip", JSONArray(listOf("ext:geoip.dat:IR")))
                .put("outboundTag", "direct"))
        }
        // QUIC (HTTP/3) over a tunneled UDP path often blackholes: the app
        // sticks to HTTP/3 and NOTHING opens even though the tunnel is up.
        // Block udp/443 so clients fall back to TCP-based HTTP/2.
        // NOTE: "network" must be exactly "udp" (a bare protocol list) with
        // the port in its own "port" field — the previous
        // "udp,0-65535,443,443" was NOT valid Xray syntax, the rule never
        // matched, QUIC flowed into the tunnel's UDP path and blackholed
        // (Instagram clips never loaded).
        rules.put(JSONObject()
            .put("type", "field")
            .put("network", "udp")
            .put("port", "443")
            .put("outboundTag", "block"))
        // ALL plaintext DNS (udp+tcp port 53) -> built-in resolver (dns-out):
        // lookups never depend on UDP-through-proxy. Listed AFTER the private
        // rule so router/LAN DNS stays direct when bypass-LAN is on.
        // DNS module's own upstream queries -> through the tunnel. Dialing
        // the resolvers DIRECTLY from Iran gets tampered (the core log showed
        // 963x "record not found" in one session) — v2Box routes dns-module
        // through the proxy the same way.
        rules.put(JSONObject()
            .put("type", "field")
            .put("inboundTag", JSONArray(listOf("dns-module")))
            .put("outboundTag", "proxy"))
        // ALL plaintext DNS arriving via the TUN -> built-in resolver. MUST be
        // scoped with inboundTag: unscoped, it ALSO caught the DNS module's own
        // upstream queries and fed them back into dns-out — an infinite loop in
        // which every lookup timed out with "record not found". ROOT CAUSE of
        // the Gmail/Google failures (found in the exported core log).
        rules.put(JSONObject()
            .put("type", "field")
            .put("inboundTag", JSONArray(listOf("socks-in", "http-in")))
            .put("port", "53")
            .put("outboundTag", "dns-out"))
        // AsIs: route on the domain/IP as-is, no per-connection DNS resolve.
        // IPIfNonMatch resolved EVERY new domain through the tunnel DNS
        // (tcp://8.8.8.8) before routing — dozens of extra round-trips for
        // media-heavy apps (Instagram clips timing out). LAN bypass is
        // unaffected: LAN traffic is IP-literal and still matches geoip:private.
        root.put("routing", JSONObject()
            .put("domainStrategy", "AsIs")
            .put("rules", rules))


        return root.toString()
    }

    /** Builds just one outbound (used for MeasureOutboundDelay tests). */
    fun buildTestConfig(profile: Profile, socksPort: Int): String {
        val root = JSONObject()
        root.put("log", JSONObject().put("loglevel", "none"))
        root.put("inbounds", JSONArray(listOf(JSONObject()
            .put("listen", "127.0.0.1").put("port", socksPort)
            .put("protocol", "socks")
            .put("settings", JSONObject().put("udp", false).put("auth", "noauth")))))
        root.put("outbounds", JSONArray(listOf(
            buildOutbound(profile),
            JSONObject().put("tag", "dns-out").put("protocol", "dns"))))
        root.put("dns", JSONObject()
            .put("servers", JSONArray(listOf("tcp://1.1.1.1", "tcp://8.8.8.8")))
            .put("queryStrategy", "UseIP"))
        root.put("routing", JSONObject().put("domainStrategy", "IPIfNonMatch")
            .put("rules", JSONArray(listOf(JSONObject()
                .put("type", "field").put("port", "53")
                .put("outboundTag", "dns-out")))))
        return root.toString()
    }

    private fun buildOutbound(p: Profile): JSONObject {
        val ob = JSONObject()
        val settings = JSONObject()
        val stream = JSONObject()
        stream.put("network", networkName(p.network))

        when (p.protocol) {
            Profile.Protocol.VLESS -> {
                ob.put("protocol", "vless")
                val user = JSONObject().put("id", p.uuid ?: "")
                    .put("encryption", p.encryption ?: "none")
                    .put("level", 8)
                p.flow?.let { user.put("flow", it) }
                settings.put("vnext", JSONArray(listOf(JSONObject()
                    .put("address", p.address).put("port", p.port)
                    .put("users", JSONArray(listOf(user))))))
            }
            Profile.Protocol.VMESS -> {
                ob.put("protocol", "vmess")
                val user = JSONObject().put("id", p.uuid ?: "")
                    .put("alterId", p.alterId)
                    .put("security", p.method ?: "auto")
                    .put("level", 8)
                settings.put("vnext", JSONArray(listOf(JSONObject()
                    .put("address", p.address).put("port", p.port)
                    .put("users", JSONArray(listOf(user))))))
            }
            Profile.Protocol.TROJAN -> {
                ob.put("protocol", "trojan")
                val user = JSONObject().put("password", p.uuid ?: "").put("level", 8)
                settings.put("servers", JSONArray(listOf(JSONObject()
                    .put("address", p.address).put("port", p.port)
                    .put("users", JSONArray(listOf(user))))))
            }
            Profile.Protocol.SHADOWSOCKS -> {
                ob.put("protocol", "shadowsocks")
                settings.put("servers", JSONArray(listOf(JSONObject()
                    .put("address", p.address).put("port", p.port)
                    .put("method", p.method ?: "aes-256-gcm")
                    .put("password", p.uuid ?: "")
                    .put("uot", true)
                    .put("level", 8))))
            }
            Profile.Protocol.SOCKS -> {
                ob.put("protocol", "socks")
                val server = JSONObject().put("address", p.address).put("port", p.port)
                if (!p.uuid.isNullOrEmpty()) {
                    server.put("users", JSONArray(listOf(JSONObject()
                        .put("user", p.uuid).put("pass", p.method ?: ""))))
                }
                settings.put("servers", JSONArray(listOf(server)))
            }
            Profile.Protocol.HTTP -> {
                ob.put("protocol", "http")
                val server = JSONObject().put("address", p.address).put("port", p.port)
                if (!p.uuid.isNullOrEmpty()) {
                    server.put("users", JSONArray(listOf(JSONObject()
                        .put("user", p.uuid).put("pass", p.method ?: ""))))
                }
                settings.put("servers", JSONArray(listOf(server)))
            }
        }
        ob.put("settings", settings)
        ob.put("tag", "proxy")

        // ---------- streamSettings ----------
        when (p.security) {
            Profile.Security.TLS -> {
                // allowInsecure was REMOVED from newer Xray cores and makes the whole
                // config fail to parse (pinnedPeerCertSha256 / verifyPeerCertByName replaced it).
                // Never emit it, or the core rejects the config outright.
                val tls = JSONObject()
                p.sni?.let { tls.put("serverName", it) }
                p.alpn?.let { tls.put("alpn", JSONArray(it.split(",").map { a -> a.trim() })) }
                // uTLS fingerprint: v2rayNG/v2Box default to "chrome" when the
                // link has no fp= param. Without it the core uses Go's native
                // TLS stack, whose handshake fingerprint stands out to Google's
                // anti-abuse (account signup "Something went wrong" while the
                // same config works fine in v2Box). Always match the clients.
                tls.put("fingerprint", p.fingerprint?.takeIf { it.isNotBlank() } ?: "chrome")
                stream.put("security", "tls").put("tlsSettings", tls)
            }
            Profile.Security.REALITY -> {
                val pk = p.publicKey?.takeIf { it.isNotBlank() }
                if (pk != null) {
                    val reality = JSONObject()
                        .put("show", false)
                    p.sni?.let { reality.put("serverName", it) }
                    // REALITY is built on uTLS — never leave the fingerprint empty
                    reality.put("fingerprint", p.fingerprint?.takeIf { it.isNotBlank() } ?: "chrome")
                    reality.put("publicKey", pk)
                    p.shortId?.let { reality.put("shortId", it) }
                    p.spiderX?.let { reality.put("spiderX", it) }
                    stream.put("security", "reality").put("realitySettings", reality)
                } else {
                    // no publicKey (e.g. mangled params from a bad source):
                    // a REALITY outbound without it is rejected at parse time
                    // by the core — degrade to TLS so the config still parses
                    val tls = JSONObject()
                    p.sni?.let { tls.put("serverName", it) }
                    tls.put("fingerprint", p.fingerprint?.takeIf { it.isNotBlank() } ?: "chrome")
                    stream.put("security", "tls").put("tlsSettings", tls)
                }
            }
            Profile.Security.NONE -> stream.put("security", "none")
        }

        when (p.network) {
            Profile.Transport.WS -> {
                val ws = JSONObject()
                // WebSocket early data: panels append "?ed=2048" to the ws
                // path — the first N bytes must travel in the
                // Sec-WebSocket-Protocol header, NOT inside the path. Raw
                // cores do not do this extraction (v2rayNG/v2Box do it at
                // parse time); we do it at BUILD time so no schema change is
                // needed and already-saved configs are healed too.
                var wsPath = p.path
                if (wsPath != null) {
                    val ed = Regex("\\?ed=(\\d+)\\s*$").find(wsPath)
                    if (ed != null) {
                        ws.put("maxEarlyData", ed.groupValues[1].toInt())
                        ws.put("earlyDataHeaderName", "Sec-WebSocket-Protocol")
                        wsPath = wsPath.substring(0, ed.range.first).ifEmpty { "/" }
                    }
                }
                wsPath?.let { ws.put("path", it) }
                val headers = JSONObject()
                p.host?.let { headers.put("Host", it) }
                if (headers.length() > 0) ws.put("headers", headers)
                stream.put("wsSettings", ws)
            }
            Profile.Transport.GRPC -> {
                val grpc = JSONObject()
                p.serviceName?.let { grpc.put("serviceName", it) }
                grpc.put("multiMode", false)
                grpc.put("idle_timeout", 60)
                stream.put("grpcSettings", grpc)
            }
            Profile.Transport.HTTP -> {
                val h2 = JSONObject()
                p.path?.let { h2.put("path", it) }
                p.host?.let { h2.put("host", JSONArray(listOf(it))) }
                stream.put("httpSettings", h2)
            }
            Profile.Transport.HTTPUPGRADE -> {
                val hu = JSONObject()
                p.path?.let { hu.put("path", it) }
                p.host?.let { hu.put("host", it) }
                stream.put("httpupgradeSettings", hu)
            }
            Profile.Transport.XHTTP -> {
                val xh = JSONObject()
                p.path?.let { xh.put("path", it) }
                p.host?.let { xh.put("host", it) }
                // Full v2Box parity for the runtime-only difference found by
                // exporting both configs with the SAME row (GER 4-8443-xhttp):
                // v2Box sends mode:"auto" + xPaddingBytes:"100-1000" (+ the same
                // pair inside "extra"). Without padding, XHTTP frames have a
                // fixed/absent padding pattern that middleboxes can fingerprint
                // and tamper with — Google signup (long POSTs over h2) broke in
                // our app while the identical config worked in v2Box. Go's JSON
                // decoder ignores unknown fields, so emitting "extra" is safe on
                // older cores too.
                xh.put("mode", "auto")
                xh.put("xPaddingBytes", "100-1000")
                xh.put("extra", JSONObject()
                    .put("mode", "auto")
                    .put("xPaddingBytes", "100-1000"))
                stream.put("xhttpSettings", xh)
            }
            Profile.Transport.KCP -> {
                stream.put("kcpSettings", JSONObject()
                    .put("mtu", 1350).put("tti", 50)
                    .put("uplinkCapacity", 12).put("downlinkCapacity", 100)
                    .put("congestion", false).put("readBufferSize", 1).put("writeBufferSize", 1))
            }
            Profile.Transport.QUIC -> {
                stream.put("quicSettings", JSONObject()
                    .put("security", "none").put("key", "").put("header", JSONObject().put("type", "none")))
            }
            Profile.Transport.TCP -> {
                // Optional HTTP obfs header if host set on tcp
                if (!p.host.isNullOrEmpty()) {
                    val req = JSONObject().put("version", "1.1")
                        .put("headers", JSONObject().put("Host", JSONArray(listOf(p.host))))
                    stream.put("tcpSettings", JSONObject().put("header", JSONObject()
                        .put("type", "http").put("request", req)))
                }
            }
        }
        // sockopt UseIP + happyEyeballs: last remaining v2Box parity item from
        // the side-by-side JSON export. The phone dials the VPS domain itself;
        // UseIP makes the core resolve it through its own DNS module instead of
        // the raw system resolver, and happyEyeballs races v4/v6 so a broken
        // v6 path to the VPS never stalls the connection.
        stream.put("sockopt", JSONObject()
            .put("domainStrategy", "UseIP")
            .put("happyEyeballs", JSONObject()
                .put("interleave", 2)
                .put("maxConcurrentTry", 4)
                .put("prioritizeIPv6", true)
                .put("tryDelayMs", 250)))
        ob.put("streamSettings", stream)
        // mux off by default: stable + REALITY compatible
        ob.put("mux", JSONObject().put("enabled", false).put("concurrency", -1))
        return ob
    }

    private fun blockedOutbound(): JSONObject = JSONObject()
        .put("protocol", "blackhole").put("tag", "block")
        .put("settings", JSONObject().put("response", JSONObject().put("type", "http")))

    private fun directOutbound(): JSONObject = JSONObject()
        .put("protocol", "freedom").put("tag", "direct")
        .put("settings", JSONObject())

    private fun networkName(t: Profile.Transport): String = when (t) {
        Profile.Transport.HTTP -> "http"
        Profile.Transport.HTTPUPGRADE -> "httpupgrade"
        Profile.Transport.XHTTP -> "xhttp"
        else -> t.name.lowercase()
    }
}
