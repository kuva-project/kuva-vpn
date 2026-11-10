package com.kuva.vpn.core.config.builder

import android.util.Base64
import com.kuva.vpn.domain.model.Profile
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds standard share links from a [Profile] — the inverse of
 * [com.kuva.vpn.core.config.parser.ShareLinkParser].
 *
 * SECURITY: never emits allowInsecure (removed from new Xray cores).
 */
@Singleton
class ShareLinkBuilder @Inject constructor() {

    fun build(p: Profile): String = when (p.protocol) {
        Profile.Protocol.VLESS -> vless(p)
        Profile.Protocol.VMESS -> vmess(p)
        Profile.Protocol.TROJAN -> trojan(p)
        Profile.Protocol.SHADOWSOCKS -> shadowsocks(p)
        Profile.Protocol.SOCKS -> socks(p)
        Profile.Protocol.HTTP -> http(p)
    }

    private fun enc(v: String): String =
        URLEncoder.encode(v, "UTF-8").replace("+", "%20")

    private fun query(q: Map<String, String>): String =
        q.entries.joinToString("&") { (k, v) -> k + "=" + enc(v) }

    // ---------- VLESS ----------
    private fun vless(p: Profile): String {
        val q = sortedMapOf<String, String>()
        when (p.security) {
            Profile.Security.TLS -> q["security"] = "tls"
            Profile.Security.REALITY -> q["security"] = "reality"
            Profile.Security.NONE -> {}
        }
        q["encryption"] = p.encryption?.takeIf { it.isNotBlank() } ?: "none"
        p.flow?.let { q["flow"] = it }
        q["type"] = transportName(p.network)
        // CORRECTNESS: never invent security=tls just because an sni exists
        // (it often comes from the ws host param on a PLAIN server). A faked
        // TLS link is dead in every other client (V2Box/Hiddify/v2rayNG).
        if (p.security != Profile.Security.NONE) p.sni?.let { q["sni"] = it }
        p.host?.let { q["host"] = it }
        p.path?.let { q["path"] = it }
        p.serviceName?.let { q["serviceName"] = it }
        p.alpn?.let { q["alpn"] = it }
        p.fingerprint?.let { q["fp"] = it }
        p.publicKey?.let { q["pbk"] = it }
        p.shortId?.let { q["sid"] = it }
        p.spiderX?.let { q["spx"] = it }
        return "vless://" + p.uuid + "@" + p.address + ":" + p.port +
            "?" + query(q) + "#" + enc(p.name)
    }

    // ---------- VMESS (base64 JSON, v2rayN format) ----------
    private fun vmess(p: Profile): String {
        val j = org.json.JSONObject()
        j.put("v", "2")
        j.put("ps", p.name)
        j.put("add", p.address)
        j.put("port", p.port.toString())
        j.put("id", p.uuid ?: "")
        j.put("aid", p.alterId.toString())
        j.put("scy", p.method ?: "auto")
        j.put("net", transportName(p.network))
        j.put("type", "none")
        j.put("host", p.host ?: "")
        j.put("path", p.path ?: "")
        j.put("tls", if (p.security == Profile.Security.TLS) "tls" else "")
        j.put("sni", p.sni ?: "")
        j.put("alpn", p.alpn ?: "")
        j.put("fp", p.fingerprint ?: "")
        return "vmess://" + Base64.encodeToString(
            j.toString().toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP,
        )
    }

    // ---------- TROJAN ----------
    private fun trojan(p: Profile): String {
        val q = sortedMapOf<String, String>()
        q["type"] = transportName(p.network)
        if (p.security == Profile.Security.NONE) q["security"] = "none"
        p.sni?.let { q["sni"] = it }
        p.host?.let { q["host"] = it }
        p.path?.let { q["path"] = it }
        p.serviceName?.let { q["serviceName"] = it }
        p.alpn?.let { q["alpn"] = it }
        p.fingerprint?.let { q["fp"] = it }
        return "trojan://" + enc(p.uuid ?: "") + "@" + p.address + ":" + p.port +
            "?" + query(q) + "#" + enc(p.name)
    }

    // ---------- SHADOWSOCKS (SIP002) ----------
    private fun shadowsocks(p: Profile): String {
        val userInfo = Base64.encodeToString(
            ((p.method ?: "aes-256-gcm") + ":" + (p.uuid ?: "")).toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP,
        ).replace("=", "")  // SIP002 uses unpadded base64
        return "ss://" + userInfo + "@" + p.address + ":" + p.port + "#" + enc(p.name)
    }

    // ---------- SOCKS / HTTP ----------
    private fun socks(p: Profile): String =
        "socks://" + enc(p.uuid ?: "") + "@" + p.address + ":" + p.port + "#" + enc(p.name)

    private fun http(p: Profile): String =
        "http://" + enc(p.uuid ?: "") + "@" + p.address + ":" + p.port + "#" + enc(p.name)

    private fun transportName(t: Profile.Transport): String = when (t) {
        Profile.Transport.TCP -> "tcp"
        Profile.Transport.WS -> "ws"
        Profile.Transport.GRPC -> "grpc"
        Profile.Transport.HTTP -> "http"
        Profile.Transport.HTTPUPGRADE -> "httpupgrade"
        Profile.Transport.XHTTP -> "xhttp"
        Profile.Transport.KCP -> "kcp"
        Profile.Transport.QUIC -> "quic"
    }
}
