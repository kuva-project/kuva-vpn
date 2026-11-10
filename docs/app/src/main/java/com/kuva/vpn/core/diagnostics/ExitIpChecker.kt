package com.kuva.vpn.core.diagnostics

import com.kuva.vpn.domain.model.ExitIpInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Verifies the real public exit IP and geolocation by making the request
 * THROUGH the running tunnel (local SOCKS inbound). HTTPS only; the result
 * reflects the actual exit node, not the server hostname.
 */
@Singleton
class ExitIpChecker @Inject constructor() {

    /** @return verified info, or null when lookup fails (UI shows "unavailable"). */
    suspend fun lookup(socksPort: Int?): ExitIpInfo? = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .apply {
                // null port = NO proxy: measures the user's direct (no-tunnel) IP
                if (socksPort != null) proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))
            }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        for (url in ENDPOINTS) {
            try {
                val resp = client.newCall(
                    Request.Builder()
                        .url(url)
                        .header("User-Agent", "KUVAVpn/1.0")
                        .build()
                ).execute()
                resp.use {
                    if (!it.isSuccessful) return@use
                    val body = it.body?.string() ?: return@use
                    parse(url, body)?.let { info -> return@withContext info }
                }
            } catch (_: Exception) {
                // try next endpoint
            }
        }
        null
    }

    /** Direct (no-tunnel) lookup: what the user's own connection looks like. */
    suspend fun lookupDirect(): ExitIpInfo? = lookup(null)

    private fun parse(url: String, body: String): ExitIpInfo? = try {
        val j = JSONObject(body)
        val ip = j.optString("ip").ifEmpty { j.optString("query") }
        if (ip.isEmpty()) null
        else ExitIpInfo(
            ip = ip,
            countryCode = j.optString("country_code").ifEmpty { j.optString("countryCode") }.takeIf { it.isNotEmpty() },
            country = j.optString("country").takeIf { it.isNotEmpty() }
                ?: j.optString("country_code").ifEmpty { j.optString("countryCode") }.takeIf { it.isNotEmpty() },
            city = j.optString("city").takeIf { it.isNotEmpty() },
            isp = j.optString("isp").ifEmpty { j.optString("org") }.takeIf { it.isNotEmpty() },
        )
    } catch (_: Exception) { null }

    companion object {
        // HTTPS-only endpoints; ip-api is used via its HTTPS endpoint (pro).
        private val ENDPOINTS = listOf(
            "https://api.ip.sb/geoip",
            "https://ipapi.co/json/",
            "https://ipwho.is/",
        )
    }
}
