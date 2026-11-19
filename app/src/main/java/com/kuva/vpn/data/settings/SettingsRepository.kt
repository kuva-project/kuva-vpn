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
    val engineMode: String = "swift",  // patch 244: Kuva Swift is the default engine
    val swiftH3: Boolean = false,      // HTTP/3 (QUIC) transport for Swift
    val swiftScan: Boolean = true,     // route scan before Swift connect
    val geoSource: String = "Chocolate4U/Iran-v2ray-rules", // Geo databases repo
    val showNotifSpeed: Boolean = false, // up/down speed in the status-bar notification
    val swiftPsiphon: Boolean = false, // psiphon layer on (dashboard switch)
    // patch 217: HOW the psiphon layer rides the MASQUE tunnel when
    // swiftPsiphon is on:
    //   "chain"   = --psiphon          (WARP -> Psiphon; exit = psiphon)
    //   "reverse" = --psiphon-reverse  (Psiphon -> WARP; exit = WARP)
    val swiftPsiphonMode: String = "chain",
    // preferred psiphon exit country: "" = Auto, otherwise ISO-2 like "US"/"DE"
    val swiftPsiphonCountry: String = "",
    // anti-DPI controls (patch 216). Fragment splits the TLS ClientHello;
    // noize is the core's obfuscation profile. Both default OFF to match the
    // core's and PattNG's defaults — with them always on, Hamrah-Aval
    // stalled the h2 data plane 30-60 s after every handshake.
    val swiftFragment: Boolean = false,
    val swiftNoize: Boolean = false,
    // custom MASQUE SNI; "" = the core's own default (www.cloudflare.com)
    val swiftSni: String = "",
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

// patch 264: cap on the dead-server tombstone set, keeps DataStore small;
// entries beyond the cap are evicted arbitrarily
private const val DEAD_SERVER_CAP = 2000

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
        val swiftH3 = booleanPreferencesKey("swift_h3")
        val swiftScan = booleanPreferencesKey("swift_scan")
        val geoSource = stringPreferencesKey("geo_source")
        val showNotifSpeed = booleanPreferencesKey("notif_show_speed")
        // patch 217: psiphon chain direction; the legacy boolean stays the
        // on/off source for the dashboard switch, service gate and notif
        val swiftPsiphonMode = stringPreferencesKey("swift_psiphon_mode")
        val swiftFragment = booleanPreferencesKey("swift_fragment")
        val swiftNoize = booleanPreferencesKey("swift_noize")
        val swiftSni = stringPreferencesKey("swift_sni")
        // patch 264: tombstones of dead free-config servers (search memory)
        val deadServers = stringSetPreferencesKey("dead_server_identities")
    }

    // patch 254: engine + psiphon layer state are PER-SESSION RUNTIME state,
    // deliberately NOT persisted. Every fresh start boots on Kuva Swift with
    // psiphon OFF/Auto (locked user decision) — inherently, with no forced
    // write. The old DataStore-backed values raced the first UI read: the
    // stale stored "xray" hit the dashboard before the forced "swift" write
    // landed, causing the visible xray->swift jump on every app start.
    // In-memory state cannot race — the very first emission is already
    // "swift". The dashboard switch mutates these for the current session.
    private val runtimeEngine = kotlinx.coroutines.flow.MutableStateFlow("swift")
    private val runtimePsiphon = kotlinx.coroutines.flow.MutableStateFlow(false)
    private val runtimePsiphonCountry = kotlinx.coroutines.flow.MutableStateFlow("")

    val settings: Flow<AppSettings> = kotlinx.coroutines.flow.combine(
        context.dataStore.data, runtimeEngine, runtimePsiphon, runtimePsiphonCountry,
    ) { p, engine, psi, psiCc ->
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
            engineMode = engine,  // patch 254: runtime session state
            swiftH3 = p[Keys.swiftH3] ?: false,
            swiftScan = p[Keys.swiftScan] ?: true,
            geoSource = p[Keys.geoSource] ?: "Chocolate4U/Iran-v2ray-rules",
            showNotifSpeed = p[Keys.showNotifSpeed] ?: false,
            swiftPsiphon = psi,  // patch 254: runtime session state
            swiftPsiphonMode = p[Keys.swiftPsiphonMode] ?: "chain",
            swiftPsiphonCountry = psiCc,  // patch 254: runtime session state
            swiftFragment = p[Keys.swiftFragment] ?: false,
            swiftNoize = p[Keys.swiftNoize] ?: false,
            swiftSni = p[Keys.swiftSni] ?: "",
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
    // patch 254: session-scoped — mutates runtime state, nothing persisted
    suspend fun setSwiftPsiphon(b: Boolean) { runtimePsiphon.value = b }

    /**
     * patch 217: psiphon chain direction, "chain" or "reverse"; anything
     * else is coerced to chain. The legacy swiftPsiphon boolean stays the
     * on/off source of truth for the dashboard switch, service gate and
     * notification, so picking a direction here also turns the layer on —
     * the settings card is where the mode is chosen, the dashboard switch
     * simply toggles it.
     */
    // patch 254: mode persists (a preference), but picking a direction also
    // turns the layer ON for this session — same behavior as before
    suspend fun setSwiftPsiphonMode(mode: String) {
        context.dataStore.edit {
            it[Keys.swiftPsiphonMode] = if (mode == "reverse") "reverse" else "chain"
        }
        runtimePsiphon.value = true
    }

    /**
     * Preferred psiphon exit country. The core (aether) only accepts values
     * of exactly two ASCII letters (it uppercases and validates them), so we
     * store "" for Auto and uppercase ISO-2 codes otherwise — anything else
     * falls back to Auto.
     */
    // patch 254: session-scoped — mutates runtime state, nothing persisted
    suspend fun setSwiftPsiphonCountry(cc: String) {
        val v = cc.trim().uppercase()
        runtimePsiphonCountry.value =
            if (v.length == 2 && v.all { it in 'A'..'Z' }) v else ""
    }
    suspend fun setSwiftScan(b: Boolean) = context.dataStore.edit { it[Keys.swiftScan] = b }

    /** SNI must be a printable-ASCII host name without spaces, <=253 chars. */
    private fun sniSafe(v: String): String =
        v.trim().takeIf { s -> s.isNotEmpty() && s.length <= 253 &&
            s.all { it.code in 0x21..0x7e } } ?: ""

    suspend fun setSwiftFragment(b: Boolean) = context.dataStore.edit { it[Keys.swiftFragment] = b }
    suspend fun setSwiftNoize(b: Boolean) = context.dataStore.edit { it[Keys.swiftNoize] = b }
    suspend fun setSwiftSni(v: String) = context.dataStore.edit { it[Keys.swiftSni] = sniSafe(v) }
    suspend fun setGeoSource(src: String) = context.dataStore.edit { it[Keys.geoSource] = src }
    suspend fun setShowNotifSpeed(b: Boolean) = context.dataStore.edit { it[Keys.showNotifSpeed] = b }
    suspend fun setStatsEnabled(b: Boolean) = context.dataStore.edit { it[Keys.stats] = b }
    suspend fun setMtu(v: Int) = context.dataStore.edit { it[Keys.mtu] = v }
    suspend fun setSocksPort(v: Int) = context.dataStore.edit { it[Keys.socksPort] = v }
    suspend fun setSpeedTestLimit(mb: Int) = context.dataStore.edit { it[Keys.speedLimit] = mb }
    // patch 254: session-scoped — mutates runtime state, nothing persisted
    suspend fun setEngineMode(mode: String) {
        runtimeEngine.value = if (mode == "xray") "xray" else "swift"
    }

    // patch 264: dead-server tombstones — server identities that failed a
    // ping, consulted by every free-config search so dead servers are never
    // re-imported. FREE scope only; subscriptions/personal never touched.
    suspend fun deadServers(): Set<String> =
        context.dataStore.data.first()[Keys.deadServers] ?: emptySet()

    suspend fun addDeadServers(ids: Collection<String>) {
        if (ids.isEmpty()) return
        context.dataStore.edit { prefs ->
            val merged = (prefs[Keys.deadServers] ?: emptySet()) + ids
            prefs[Keys.deadServers] =
                if (merged.size > DEAD_SERVER_CAP) merged.drop(merged.size - DEAD_SERVER_CAP).toSet()
                else merged
        }
    }

    suspend fun resetAll() {
        context.dataStore.edit { it.clear() }
    }
}
