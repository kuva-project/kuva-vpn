package com.kuva.vpn.core.config.parser

import android.util.Base64
import com.kuva.vpn.domain.model.Profile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parses subscription bodies: base64-encoded link lists, plain line lists,
 * and reads subscription-userinfo metadata (traffic/expiry) from headers.
 */
@Singleton
class SubscriptionParser @Inject constructor(
    private val shareLinkParser: ShareLinkParser,
) {

    data class SubInfo(
        val upload: Long? = null,
        val download: Long? = null,
        val total: Long? = null,
        val expire: Long? = null, // epoch seconds
    )

    fun parseUserInfo(header: String?): SubInfo? {
        if (header.isNullOrBlank()) return null
        val map = header.split(";").mapNotNull {
            val parts = it.trim().split("=", limit = 2)
            if (parts.size == 2) parts[0].trim().lowercase() to parts[1].trim().toLongOrNull() else null
        }.toMap()
        if (map.isEmpty()) return null
        return SubInfo(map["upload"], map["download"], map["total"], map["expire"])
    }

    /** Decode body (base64 or plain) and parse all profiles. */
    fun parseBody(body: String): List<Profile> {
        val decoded = tryDecodeBase64(body) ?: body
        return shareLinkParser.parse(decoded)
    }

    private fun tryDecodeBase64(text: String): String? {
        val t = text.trim()
        if (t.startsWith("vless://") || t.startsWith("vmess://") || t.startsWith("trojan://") || t.startsWith("ss://")) {
            return null // already plain
        }
        return runCatching {
            val cleaned = t.replace("\n", "").replace("\r", "").replace(" ", "")
            val padded = cleaned.padEnd((cleaned.length + 3) / 4 * 4, '=')
            String(Base64.decode(padded, Base64.DEFAULT))
        }.getOrNull()?.takeIf { decoded ->
            decoded.contains("://") // must decode to links
        }
    }
}
