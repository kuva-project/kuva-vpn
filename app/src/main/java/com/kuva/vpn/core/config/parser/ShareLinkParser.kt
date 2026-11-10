package com.kuva.vpn.core.config.parser

import android.net.Uri
import android.util.Base64
import com.kuva.vpn.domain.model.Profile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parses standard share links for all supported protocols plus raw Xray JSON.
 * Malformed input returns null — never throws (validated against crash-on-bad-payload).
 */
@Singleton
class ShareLinkParser @Inject constructor() {

    fun parse(text: String): List<Profile> {
        val out = mutableListOf<Profile>()
        // Pretty-printed (multi-line) JSON: try the WHOLE text as one object
        // first. If it is not valid JSON the per-line scan below still catches
        // the single-line JSON case.
        if (text.trim().startsWith("{")) {
            parseRawJson(text.trim())?.let { return listOf(it) }
        }
        text.trim().lineSequence().forEach { line ->
            val l = unescapeEntities(line.trim())
            if (l.isEmpty()) return@forEach
            when {
                l.startsWith("vless://", true) -> parseVless(l)?.let(out::add)
                l.startsWith("vmess://", true) -> parseVmess(l)?.let(out::add)
                l.startsWith("trojan://", true) -> parseTrojan(l)?.let(out::add)
                l.startsWith("ss://", true) -> parseShadowsocks(l)?.let(out::add)
                l.startsWith("socks://", true) || l.startsWith("socks5://", true) -> parseSocks(l)?.let(out::add)
                l.startsWith("http://", true) || l.startsWith("https://", true) -> {
                    // Only a direct node URL, not a subscription link (handled elsewhere)
                    if (l.contains('@')) parseHttp(l)?.let(out::add)
                }
                l.trim().startsWith("{") -> parseRawJson(l)?.let(out::add)
            }
        }
        return out
    }

    /** A line is a subscription link (not a node) if it is a bare https URL without user@. */
    fun isSubscriptionLink(text: String): Boolean =
        text.trim().startsWith("http", true) && !text.trim().substringAfter("//").contains("@")

    /**
     * WebSocket early data: some share links carry the early-data size as a
     * STANDALONE "ed=<bytes>" query param instead of embedding "?ed=" inside
     * the path. Embed it into the path here so XrayConfigBuilder (which
     * extracts "?ed=N" at build time) handles both spellings uniformly.
     */
    private fun pathWithEarlyData(path: String?, ed: String?): String? {
        if (ed == null || path == null || path.contains("?ed=")) return path
        return path + (if (path.contains("?")) "&" else "?") + "ed=" + ed
    }

    // ---------- VLESS ----------
    private fun parseVless(link: String): Profile? {
        return try {
            val uri = Uri.parse(link)
            val host = uri.host ?: return null
            val port = uri.port.takeIf { it > 0 } ?: return null
            val uuid = uri.userInfo ?: return null
            val q = uri.queryParameters
            val security = when (q["security"]) {
                "tls" -> Profile.Security.TLS
                "reality" -> Profile.Security.REALITY
                else -> Profile.Security.NONE
            }
            // New Xray cores REFUSE plain vless to a literal public IP:
            // "vless without TLS or other encryption is prohibited unless
            // the server address is a private IP or domain". Such configs
            // can never start here — drop them instead of shipping dead rows.
            // q["security"] may be present-but-EMPTY ("security=&..."):
            // check the VALUE, not just key presence.
            if (security == Profile.Security.NONE &&
                q["security"].isNullOrBlank() && isPublicIpLiteral(host)) return null
            Profile(
                scope = Profile.Scope.FREE, // scope set by repository
                name = uri.fragment?.decoded ?: "vless-$host",
                protocol = Profile.Protocol.VLESS,
                address = host, port = port,
                uuid = uuid,
                encryption = q["encryption"] ?: "none",
                flow = q["flow"],
                network = q["type"]?.let { transport(it) } ?: Profile.Transport.TCP,
                security = security,
                sni = q["sni"] ?: q["host"],
                host = q["host"],
                path = pathWithEarlyData(q["path"]?.decoded, q["ed"]),
                serviceName = q["serviceName"],
                alpn = q["alpn"],
                fingerprint = q["fp"],
                publicKey = q["pbk"],
                shortId = q["sid"],
                spiderX = q["spx"]?.decoded,
                allowInsecure = q["allowInsecure"] == "1",
            )
        } catch (_: Exception) { null }
    }

    // ---------- VMESS (base64 JSON) ----------
    private fun parseVmess(link: String): Profile? {
        return try {
            val b64 = link.removePrefix("vmess://").trim()
            val json = String(base64Decode(b64) ?: return null)
            val j = org.json.JSONObject(json)
            val host = j.optString("add").takeIf { it.isNotEmpty() } ?: return null
            val port = j.optInt("port", -1).takeIf { it > 0 } ?: return null
            val id = j.optString("id").takeIf { it.isNotEmpty() } ?: return null
            val net = j.optString("net", "tcp")
            val tlsStr = j.optString("tls")
            Profile(
                scope = Profile.Scope.FREE,
                name = j.optString("ps").takeIf { it.isNotEmpty() } ?: "vmess-$host",
                protocol = Profile.Protocol.VMESS,
                address = host, port = port,
                uuid = id,
                alterId = j.optInt("aid", 0),
                method = j.optString("scy").takeIf { it.isNotEmpty() } ?: "auto",
                network = transport(net),
                security = if (tlsStr == "tls") Profile.Security.TLS else Profile.Security.NONE,
                sni = j.optString("sni").takeIf { it.isNotEmpty() },
                host = j.optString("host").takeIf { it.isNotEmpty() },
                path = pathWithEarlyData(
                    j.optString("path").takeIf { it.isNotEmpty() },
                    j.optString("ed").takeIf { it.isNotEmpty() },
                ),
                serviceName = j.optString("path").takeIf { j.optString("net") == "grpc" && it.isNotEmpty() },
                alpn = j.optString("alpn").takeIf { it.isNotEmpty() },
                fingerprint = j.optString("fp").takeIf { it.isNotEmpty() },
                allowInsecure = j.optBoolean("allowInsecure", false),
            )
        } catch (_: Exception) { null }
    }

    // ---------- TROJAN ----------
    private fun parseTrojan(link: String): Profile? {
        return try {
            val uri = Uri.parse(link)
            val host = uri.host ?: return null
            val port = uri.port.takeIf { it > 0 } ?: return null
            val password = uri.userInfo ?: return null
            val q = uri.queryParameters
            // New Xray cores also refuse plain trojan to public IPs.
            if (q["security"] == "none" && isPublicIpLiteral(host)) return null
            Profile(
                scope = Profile.Scope.FREE,
                name = uri.fragment?.decoded ?: "trojan-$host",
                protocol = Profile.Protocol.TROJAN,
                address = host, port = port,
                uuid = password,
                network = q["type"]?.let { transport(it) } ?: Profile.Transport.TCP,
                security = if (q["security"] == "none") Profile.Security.NONE else Profile.Security.TLS,
                sni = q["sni"] ?: q["peer"] ?: q["host"],
                host = q["host"],
                path = pathWithEarlyData(q["path"]?.decoded, q["ed"]),
                serviceName = q["serviceName"],
                alpn = q["alpn"],
                fingerprint = q["fp"],
                allowInsecure = q["allowInsecure"] == "1",
            )
        } catch (_: Exception) { null }
    }

    // ---------- SHADOWSOCKS ----------
    private fun parseShadowsocks(link: String): Profile? {
        return try {
            val body = link.removePrefix("ss://")
            // form A: base64(method:password)@host:port#name
            // form B: base64(method:password@host:port) (whole thing)
            if (body.contains("@", ignoreCase = true) && !body.substringBefore("@").all {
                    it.isLetterOrDigit() || it in "+/=_-"
                }) {
                parseSsFormA(link)
            } else {
                val decoded = String(base64Decode(body.substringBefore("#")) ?: return null)
                parseSsFormA("ss://" + decoded + (if (body.contains("#")) "#${body.substringAfter("#")}" else ""))
            }
        } catch (_: Exception) { null }
    }

    private fun parseSsFormA(link: String): Profile? {
        val uri = Uri.parse(link)
        val host = uri.host ?: return null
        val port = uri.port.takeIf { it > 0 } ?: return null
        val userinfo = uri.userInfo ?: return null
        val decodedUserinfo = runCatching {
            String(base64Decode(userinfo) ?: userinfo.toByteArray())
        }.getOrDefault(userinfo)
        val method = decodedUserinfo.substringBefore(":").takeIf { it.isNotEmpty() } ?: return null
        val password = decodedUserinfo.substringAfter(":", "")
        if (password.isEmpty()) return null
        val q = uri.queryParameters
        return Profile(
            scope = Profile.Scope.FREE,
            name = uri.fragment?.decoded ?: "ss-$host",
            protocol = Profile.Protocol.SHADOWSOCKS,
            address = host, port = port,
            uuid = password,
            method = method,
            network = Profile.Transport.TCP,
            security = if (q["plugin"]?.startsWith("v2ray-plugin") == true) Profile.Security.TLS else Profile.Security.NONE,
        )
    }

    // ---------- SOCKS / HTTP ----------
    private fun parseSocks(link: String): Profile? {
        return try {
            val uri = Uri.parse(link)
            val host = uri.host ?: return null
            val port = uri.port.takeIf { it > 0 } ?: return null
            val auth = uri.userInfo?.let { String(base64Decode(it) ?: it.toByteArray()) }
            Profile(
                scope = Profile.Scope.FREE,
                name = uri.fragment?.decoded ?: "socks-$host",
                protocol = Profile.Protocol.SOCKS,
                address = host, port = port,
                uuid = auth?.substringBefore(":")?.takeIf { it.isNotEmpty() },
                method = auth?.substringAfter(":", "")?.takeIf { it.isNotEmpty() },
            )
        } catch (_: Exception) { null }
    }

    private fun parseHttp(link: String): Profile? {
        return try {
            val uri = Uri.parse(link)
            val host = uri.host ?: return null
            val port = uri.port.takeIf { it > 0 } ?: return null
            Profile(
                scope = Profile.Scope.FREE,
                name = uri.fragment?.decoded ?: "http-$host",
                protocol = Profile.Protocol.HTTP,
                address = host, port = port,
                uuid = uri.userInfo?.substringBefore(":")?.takeIf { it.isNotEmpty() },
                method = uri.userInfo?.substringAfter(":", "")?.takeIf { it.isNotEmpty() },
                security = if (uri.scheme == "https") Profile.Security.TLS else Profile.Security.NONE,
            )
        } catch (_: Exception) { null }
    }

    // ---------- Raw Xray JSON (single outbound) ----------
    private fun parseRawJson(text: String): Profile? {
        return try {
            val j = org.json.JSONObject(text)
            val outbounds = j.optJSONArray("outbounds") ?: return null
            for (i in 0 until outbounds.length()) {
                val ob = outbounds.getJSONObject(i)
                val proto = ob.optString("protocol")
                if (proto in setOf("vless", "vmess", "trojan", "shadowsocks", "socks", "http")) {
                    val p = Profile(
                        scope = Profile.Scope.FREE,
                        name = j.optString("remark").ifEmpty { "imported-$proto" },
                        protocol = Profile.Protocol.valueOf(proto.uppercase()),
                        address = "", port = 0, rawJson = ob.toString(),
                    )
                    fillFromRawOutbound(p, ob)?.let { return it }
                }
            }
            null
        } catch (_: Exception) { null }
    }

    private fun fillFromRawOutbound(base: Profile, ob: org.json.JSONObject): Profile? {
        return try {
            val settings = ob.optJSONObject("settings") ?: return null
            val vnextArr = settings.optJSONArray("vnext") ?: settings.optJSONArray("servers") ?: return null
            if (vnextArr.length() == 0) return null
            val v = vnextArr.getJSONObject(0)
            val stream = ob.optJSONObject("streamSettings") ?: org.json.JSONObject()
            val sec = stream.optString("security", "none")
            val tls = stream.optJSONObject("tlsSettings")
            val reality = stream.optJSONObject("realitySettings")
            val ws = stream.optJSONObject("wsSettings")
            val grpc = stream.optJSONObject("grpcSettings")
            val tcp = stream.optJSONObject("tcpSettings")
            base.copy(
                address = v.optString("address"),
                port = v.optInt("port"),
                uuid = v.optString("id").takeIf { it.isNotEmpty() }
                    ?: v.optString("password").takeIf { it.isNotEmpty() },
                method = v.optString("method").takeIf { it.isNotEmpty() },
                alterId = v.optInt("alterId", 0),
                flow = v.optString("flow").takeIf { it.isNotEmpty() },
                network = transport(stream.optString("network", "tcp")),
                security = when (sec) {
                    "tls" -> Profile.Security.TLS
                    "reality" -> Profile.Security.REALITY
                    else -> Profile.Security.NONE
                },
                sni = tls?.optString("serverName")?.takeIf { it.isNotEmpty() }
                    ?: reality?.optString("serverName")?.takeIf { it.isNotEmpty() },
                fingerprint = tls?.optString("fingerprint")?.takeIf { it.isNotEmpty() }
                    ?: reality?.optString("fingerprint")?.takeIf { it.isNotEmpty() },
                publicKey = reality?.optString("publicKey")?.takeIf { it.isNotEmpty() },
                shortId = reality?.optString("shortId")?.takeIf { it.isNotEmpty() },
                host = ws?.optJSONObject("headers")?.optString("Host")?.takeIf { it.isNotEmpty() },
                path = ws?.optString("path")?.takeIf { it.isNotEmpty() },
                serviceName = grpc?.optString("serviceName")?.takeIf { it.isNotEmpty() },
            )
        } catch (_: Exception) { null }
    }

    // ---------- helpers ----------
    /** Some public sources HTML-escape their lists: &amp; instead of &.
     *  Without this, query params after the first one get mangled
     *  (amp;pbk=...) and REALITY configs lose their publicKey. */
    private fun unescapeEntities(s: String): String = s
        .replace(Regex("&amp;", RegexOption.IGNORE_CASE), "&")
        .replace(Regex("&lt;", RegexOption.IGNORE_CASE), "<")
        .replace(Regex("&gt;", RegexOption.IGNORE_CASE), ">")
        .replace(Regex("&quot;", RegexOption.IGNORE_CASE), "\"")
        .replace(Regex("&#39;|&apos;", RegexOption.IGNORE_CASE), "'")

    /** Literal IPv4 that is NOT RFC1918/loopback — the case new Xray cores reject. */
    private fun isPublicIpLiteral(h: String): Boolean {
        if (!Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(h)) return false
        val o = h.split(".").map { it.toInt() }
        return when {
            o[0] == 10 || o[0] == 127 || o[0] == 0 -> false
            o[0] == 192 && o[1] == 168 -> false
            o[0] == 172 && o[1] in 16..31 -> false
            o[0] == 169 && o[1] == 254 -> false
            else -> true
        }
    }

    private fun transport(t: String): Profile.Transport = when (t.lowercase()) {
        "ws" -> Profile.Transport.WS
        "grpc" -> Profile.Transport.GRPC
        "h2", "http" -> Profile.Transport.HTTP
        "httpupgrade" -> Profile.Transport.HTTPUPGRADE
        "xhttp", "splithttp" -> Profile.Transport.XHTTP
        "kcp" -> Profile.Transport.KCP
        "quic" -> Profile.Transport.QUIC
        else -> Profile.Transport.TCP
    }

    private fun base64Decode(s: String): ByteArray? = runCatching {
        Base64.decode(s.trim().padEnd((s.length + 3) / 4 * 4, '='), Base64.NO_WRAP or Base64.URL_SAFE)
    }.recoverCatching {
        Base64.decode(s.trim().padEnd((s.length + 3) / 4 * 4, '='), Base64.DEFAULT)
    }.getOrNull()

    private val Uri.queryParameters: Map<String, String>
        get() {
            val m = mutableMapOf<String, String>()
            for (name in queryParameterNames) {
                m[name] = getQueryParameter(name) ?: ""
            }
            return m
        }

    private val String?.decoded: String
        get() = this?.let { Uri.decode(it) } ?: ""
}
