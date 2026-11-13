package com.kuva.vpn.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

enum class RoutingMode { BYPASS_SELECTED, ONLY_SELECTED }
enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val themeMode: String = "system",
    val language: String = "system",
    val routingMode: RoutingMode = RoutingMode.BYPASS_SELECTED,
    val selectedApps: Set<String> = emptySet(),
    val bypassLan: Boolean = true,
    // Iranian domains (geosite:ir) and IPs (geoip:ir) go DIRECT instead of
    // through the tunnel — Iranian banks and gov sites block foreign IPs, and
    // it saves tunnel traffic. Routing rules come from the bundled geo files.
    val bypassIran: Boolean = true,
    val dnsProvider: String = "cloudflare",
    val customDns: String = "",
    val dnsLeakProtection: Boolean = true,
    val autoConnectLaunch: Boolean = false,
    val autoConnectBoot: Boolean = false,
    val autoReconnect: Boolean = true,
    val connTimeoutSec: Int = 15,
    val retryIntervalSec: Int = 5,
    val autoPingAfterImport: Boolean = true,
    val autoSelectMode: String = "manual", // manual | lowest_ping | best_performance
    val killSwitch: Boolean = false,
    // IPv6 ON by default: with v6 disabled the TUN only routes 0.0.0.0/0 and
    // Android keeps ::/0 on the PHYSICAL network — IPv6 traffic LEAKS past the
    // tunnel entirely. Google endpoints prefer v6 on WiFi, so signup requests
    // went out unproxied from Iran and failed ("Something went wrong") while
    // v2Box (which routes ::/0 through the tunnel) worked with the same config.
    val ipv6Enabled: Boolean = true,
    val statsEnabled: Boolean = true,
    val mtu: Int = 1420,
    val socksPort: Int = 10808,
    val speedTestLimitMB: Int = 10,
    // "xray" (config-driven) or "swift" (bundled configless MASQUE core)
    val engineMode: String = "xray",
    val swiftH3: Boolean = false,      // HTTP/3 (QUIC) transport for Swift
    val swiftScan: Boolean = true,     // route scan before Swift connect
    val swiftPsiphon: Boolean = false, // route Swift through the Psiphon network
    // preferred psiphon exit country: "" = Auto, otherwise ISO-2 like "US"/"DE"
    val swiftPsiphonCountry: String = "",
) {
    fun effectiveDnsServers(): List<String> = when (dnsProvider) {
        "cloudflare" -> listOf("1.1.1.1", "1.0.0.1")
        "google" -> listOf("8.8.8.8", "8.8.4.4")
        "quad9" -> listOf("9.9.9.9", "149.112.112.112")
        "adguard" -> listOf("94.140.14.14", "94.140.15.15")
        "custom" -> customDns.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        else -> listOf("1.1.1.1")
    }
}

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "kuva_settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val theme = stringPreferencesKey("theme")
        val language = stringPreferencesKey("language")
        val routingMode = stringPreferencesKey("routing_mode")
        val bypassIran = booleanPreferencesKey("bypass_iran")
        val selectedApps = stringSetPreferencesKey("selected_apps")
        val bypassLan = booleanPreferencesKey("bypass_lan")
        val dnsProvider = stringPreferencesKey("dns_provider")
        val customDns = stringPreferencesKey("custom_dns")
        val dnsLeak = booleanPreferencesKey("dns_leak")
        val autoLaunch = booleanPreferencesKey("auto_connect_launch")
        val autoBoot = booleanPreferencesKey("auto_connect_boot")
        val autoReconnect = booleanPreferencesKey("auto_reconnect")
        val connTimeout = intPreferencesKey("conn_timeout")
        val retryInterval = intPreferencesKey("retry_interval")
        val autoPing = booleanPreferencesKey("auto_ping")
        val autoSelect = stringPreferencesKey("auto_select")
        val killSwitch = booleanPreferencesKey("kill_switch")
        val ipv6 = booleanPreferencesKey("ipv6")
        val stats = booleanPreferencesKey("stats_enabled")
        val mtu = intPreferencesKey("mtu")
        val socksPort = intPreferencesKey("socks_port")
        val speedLimit = intPreferencesKey("speed_test_limit_mb")
        val engineMode = stringPreferencesKey("engine_mode")
        val swiftH3 = booleanPreferencesKey("swift_h3")
        val swiftScan = booleanPreferencesKey("swift_scan")
        val swiftPsiphon = booleanPreferencesKey("swift_psiphon")
        val swiftPsiphonCountry = stringPreferencesKey("swift_psiphon_country")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            themeMode = p[Keys.theme] ?: "system",
            language = p[Keys.language] ?: "system",
            routingMode = p[Keys.routingMode]?.let { runCatching { RoutingMode.valueOf(it) }.getOrNull() }
                ?: RoutingMode.BYPASS_SELECTED,
            selectedApps = p[Keys.selectedApps] ?: emptySet(),
            bypassLan = p[Keys.bypassLan] ?: true,
            bypassIran = p[Keys.bypassIran] ?: true,
            dnsProvider = p[Keys.dnsProvider] ?: "cloudflare",
            customDns = p[Keys.customDns] ?: "",
            dnsLeakProtection = p[Keys.dnsLeak] ?: true,
            autoConnectLaunch = p[Keys.autoLaunch] ?: false,
            autoConnectBoot = p[Keys.autoBoot] ?: false,
            autoReconnect = p[Keys.autoReconnect] ?: true,
            connTimeoutSec = p[Keys.connTimeout] ?: 15,
            retryIntervalSec = p[Keys.retryInterval] ?: 5,
            autoPingAfterImport = p[Keys.autoPing] ?: true,
            autoSelectMode = p[Keys.autoSelect] ?: "manual",
            killSwitch = p[Keys.killSwitch] ?: false,
            ipv6Enabled = p[Keys.ipv6] ?: true,
            statsEnabled = p[Keys.stats] ?: true,
            mtu = p[Keys.mtu] ?: 1420,
            socksPort = p[Keys.socksPort] ?: 10808,
            speedTestLimitMB = p[Keys.speedLimit] ?: 10,
            engineMode = p[Keys.engineMode] ?: "xray",
            swiftH3 = p[Keys.swiftH3] ?: false,
            swiftScan = p[Keys.swiftScan] ?: true,
            swiftPsiphon = p[Keys.swiftPsiphon] ?: false,
            swiftPsiphonCountry = p[Keys.swiftPsiphonCountry] ?: "",
        )
    }

    /** Snapshot for the VPN service (non-suspend contexts). */
    val current: AppSettings get() = runBlocking { settings.first() }

    suspend fun setTheme(mode: String) = context.dataStore.edit { it[Keys.theme] = mode }
    suspend fun setLanguage(lang: String) = context.dataStore.edit { it[Keys.language] = lang }
    suspend fun setRoutingMode(mode: RoutingMode) = context.dataStore.edit { it[Keys.routingMode] = mode.name }
    suspend fun setSelectedApps(apps: Set<String>) = context.dataStore.edit { it[Keys.selectedApps] = apps }
    suspend fun setBypassLan(b: Boolean) = context.dataStore.edit { it[Keys.bypassLan] = b }
    suspend fun setBypassIran(b: Boolean) = context.dataStore.edit { it[Keys.bypassIran] = b }
    suspend fun setDnsProvider(provider: String) = context.dataStore.edit { it[Keys.dnsProvider] = provider }
    suspend fun setCustomDns(dns: String) = context.dataStore.edit { it[Keys.customDns] = dns }
    suspend fun setDnsLeakProtection(b: Boolean) = context.dataStore.edit { it[Keys.dnsLeak] = b }
    suspend fun setAutoConnectLaunch(b: Boolean) = context.dataStore.edit { it[Keys.autoLaunch] = b }
    suspend fun setAutoConnectBoot(b: Boolean) = context.dataStore.edit { it[Keys.autoBoot] = b }
    suspend fun setAutoReconnect(b: Boolean) = context.dataStore.edit { it[Keys.autoReconnect] = b }
    suspend fun setConnTimeout(sec: Int) = context.dataStore.edit { it[Keys.connTimeout] = sec }
    suspend fun setRetryInterval(sec: Int) = context.dataStore.edit { it[Keys.retryInterval] = sec }
    suspend fun setAutoPing(b: Boolean) = context.dataStore.edit { it[Keys.autoPing] = b }
    suspend fun setAutoSelect(mode: String) = context.dataStore.edit { it[Keys.autoSelect] = mode }
    suspend fun setKillSwitch(b: Boolean) = context.dataStore.edit { it[Keys.killSwitch] = b }
    suspend fun setIpv6(b: Boolean) = context.dataStore.edit { it[Keys.ipv6] = b }
    suspend fun setSwiftH3(b: Boolean) = context.dataStore.edit { it[Keys.swiftH3] = b }
    suspend fun setSwiftPsiphon(b: Boolean) = context.dataStore.edit { it[Keys.swiftPsiphon] = b }

    /**
     * Preferred psiphon exit country. The core (aether) only accepts values
     * of exactly two ASCII letters (it uppercases and validates them), so we
     * store "" for Auto and uppercase ISO-2 codes otherwise — anything else
     * falls back to Auto.
     */
    suspend fun setSwiftPsiphonCountry(cc: String) {
        val v = cc.trim().uppercase()
        val safe = if (v.length == 2 && v.all { it in 'A'..'Z' }) v else ""
        context.dataStore.edit { it[Keys.swiftPsiphonCountry] = safe }
    }
    suspend fun setSwiftScan(b: Boolean) = context.dataStore.edit { it[Keys.swiftScan] = b }
    suspend fun setStatsEnabled(b: Boolean) = context.dataStore.edit { it[Keys.stats] = b }
    suspend fun setMtu(v: Int) = context.dataStore.edit { it[Keys.mtu] = v }
    suspend fun setSocksPort(v: Int) = context.dataStore.edit { it[Keys.socksPort] = v }
    suspend fun setSpeedTestLimit(mb: Int) = context.dataStore.edit { it[Keys.speedLimit] = mb }
    suspend fun setEngineMode(mode: String) = context.dataStore.edit { it[Keys.engineMode] = mode }

    suspend fun resetAll() {
        context.dataStore.edit { it.clear() }
    }
}
