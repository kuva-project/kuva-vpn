package com.kuva.vpn.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kuva.vpn.data.settings.AppSettings
import com.kuva.vpn.data.settings.RoutingMode
import com.kuva.vpn.data.settings.SettingsRepository
import com.kuva.vpn.core.control.VpnControl
import com.kuva.vpn.domain.model.ConnectionState
import com.kuva.vpn.domain.repository.ConnectionRepository
import android.content.Context
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: SettingsRepository,
    private val geoUpdater: com.kuva.vpn.core.engine.GeoUpdater,
    private val vpnControl: VpnControl,
    private val connRepo: ConnectionRepository,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = repo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /** Version of the installed geo databases + last update source tag. */
    private val _geoState = kotlinx.coroutines.flow.MutableStateFlow(
        com.kuva.vpn.core.engine.GeoUpdater.Info("", "", "")
    )
    val geoState: StateFlow<com.kuva.vpn.core.engine.GeoUpdater.Info> = _geoState

    init {
        refreshGeo()
    }

    fun setTheme(mode: String) = viewModelScope.launch {
        repo.setTheme(mode)
        com.kuva.vpn.KuvaApplication.applyTheme(mode)
    }
    fun setLanguage(lang: String) = viewModelScope.launch {
        repo.setLanguage(lang)
        val locales = when (lang) {
            "en" -> androidx.core.os.LocaleListCompat.forLanguageTags("en")
            "fa" -> androidx.core.os.LocaleListCompat.forLanguageTags("fa")
            else -> androidx.core.os.LocaleListCompat.getEmptyLocaleList()
        }
        androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(locales)
    }
    fun setRoutingMode(mode: RoutingMode) = viewModelScope.launch { repo.setRoutingMode(mode) }
    fun setBypassLan(b: Boolean) = viewModelScope.launch { repo.setBypassLan(b) }
    fun setBypassIran(b: Boolean) = viewModelScope.launch { repo.setBypassIran(b) }
    fun setDnsProvider(p: String) = viewModelScope.launch { repo.setDnsProvider(p) }
    fun setCustomDns(d: String) = viewModelScope.launch { repo.setCustomDns(d) }
    fun setDnsLeak(b: Boolean) = viewModelScope.launch { repo.setDnsLeakProtection(b) }
    fun setAutoLaunch(b: Boolean) = viewModelScope.launch { repo.setAutoConnectLaunch(b) }
    fun setAutoBoot(b: Boolean) = viewModelScope.launch { repo.setAutoConnectBoot(b) }
    fun setAutoReconnect(b: Boolean) = viewModelScope.launch { repo.setAutoReconnect(b) }
    fun setAutoPing(b: Boolean) = viewModelScope.launch { repo.setAutoPing(b) }
    fun setAutoSelect(m: String) = viewModelScope.launch { repo.setAutoSelect(m) }
    fun setKillSwitch(b: Boolean) = viewModelScope.launch { repo.setKillSwitch(b) }
    fun setIpv6(b: Boolean) = viewModelScope.launch { repo.setIpv6(b) }
    fun setStats(b: Boolean) = viewModelScope.launch { repo.setStatsEnabled(b) }
    fun setSelectedApps(apps: Set<String>) = viewModelScope.launch { repo.setSelectedApps(apps) }
    fun setConnTimeout(sec: Int) = viewModelScope.launch { repo.setConnTimeout(sec) }
    fun setRetryInterval(sec: Int) = viewModelScope.launch { repo.setRetryInterval(sec) }
    /** Switch engine; a live connection restarts on the NEW engine right
     *  away instead of waiting for the next connect (matches the dashboard). */
    fun setEngineMode(mode: String) = viewModelScope.launch {
        repo.setEngineMode(mode)
        when (connRepo.state.value) {
            is ConnectionState.Connected,
            is ConnectionState.Connecting,
            is ConnectionState.SwiftConnecting -> {
                vpnControl.requestStop(context)
                kotlinx.coroutines.delay(1200)   // let the service tear down
                vpnControl.requestStart(context)
            }
            else -> Unit
        }
    }
    fun setSwiftScan(b: Boolean) = viewModelScope.launch { repo.setSwiftScan(b) }
    fun setShowNotifSpeed(b: Boolean) = viewModelScope.launch { repo.setShowNotifSpeed(b) }
    fun setSwiftH3(b: Boolean) = viewModelScope.launch { repo.setSwiftH3(b) }
    fun setSwiftPsiphon(b: Boolean) = viewModelScope.launch { repo.setSwiftPsiphon(b) }

    /**
     * patch 217: pick the psiphon chain direction in the Swift card.
     * A live swift connection restarts so the new flags apply right away,
     * mirroring the dashboard toggle behaviour.
     */
    fun setSwiftPsiphonMode(mode: String) = viewModelScope.launch {
        if (repo.settings.first().swiftPsiphonMode == mode) return@launch
        repo.setSwiftPsiphonMode(mode)
        val st = connRepo.state.value
        if (st is ConnectionState.Connected || st is ConnectionState.SwiftConnecting) {
            vpnControl.requestStop(context)
            kotlinx.coroutines.delay(1200)
            vpnControl.requestStart(context)
        }
    }
    fun setSwiftFragment(b: Boolean) = viewModelScope.launch { repo.setSwiftFragment(b) }
    fun setSwiftNoize(b: Boolean) = viewModelScope.launch { repo.setSwiftNoize(b) }
    fun setSwiftSni(v: String) = viewModelScope.launch { repo.setSwiftSni(v) }

    fun setSwiftPsiphonCountry(cc: String) =
        viewModelScope.launch { repo.setSwiftPsiphonCountry(cc) }
    fun setGeoSource(src: String) = viewModelScope.launch { repo.setGeoSource(src) }
    fun setMtu(v: Int) = viewModelScope.launch { repo.setMtu(v) }
    fun setSocksPort(v: Int) = viewModelScope.launch { repo.setSocksPort(v) }
    fun setSpeedLimit(mb: Int) = viewModelScope.launch { repo.setSpeedTestLimit(mb) }
    fun resetSettings() = viewModelScope.launch { repo.resetAll() }

    /** wipes the app cache dir (best effort) */
    fun clearCache() = viewModelScope.launch {
        runCatching { context.cacheDir.deleteRecursively() }
    }

    private val _geoMessage = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val geoMessage: kotlinx.coroutines.flow.StateFlow<String?> = _geoMessage

    /** Pull the latest geo databases from the selected upstream repo (sha256 checked). */
    fun updateGeo() = viewModelScope.launch {
        _geoMessage.value = "Checking…"
        geoUpdater.update(context, { _geoMessage.value = it }, settings.value.geoSource)
            .onSuccess { tag ->
                _geoMessage.value = "Updated to $tag — reconnect to apply"
                refreshGeo()
            }
            .onFailure { _geoMessage.value = "Failed: ${it.message ?: "unknown"}" }
    }

    /** Restore the geo databases bundled inside the APK. */
    fun resetGeo() = viewModelScope.launch {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            geoUpdater.resetToBundled(context)
        }
        _geoMessage.value = "Restored bundled databases — reconnect to apply"
        refreshGeo()
    }

    private fun refreshGeo() = viewModelScope.launch {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            geoUpdater.info(context)
        }
    }

    /** Shareable URI of the current core log (null when nothing logged yet). */
    fun coreLogSnapshot(): android.net.Uri? {
        val f = com.kuva.vpn.core.engine.CoreLogStore.snapshotFile(context) ?: return null
        return androidx.core.content.FileProvider.getUriForFile(
            context, context.packageName + ".fileprovider", f)
    }

    /**
     * Shareable URI of the Swift core log (null when nothing logged yet).
     * FileProvider only exposes files/exports/, so the raw log from
     * files/swift/swift.log is copied there first.
     */
    fun swiftLogSnapshot(): android.net.Uri? {
        val src = java.io.File(context.filesDir, "swift/swift.log")
        if (!src.exists() || src.length() == 0L) return null
        val exports = java.io.File(context.filesDir, "exports").apply { mkdirs() }
        val dst = java.io.File(exports, "swift_log_" + System.currentTimeMillis() + ".txt")
        runCatching { src.copyTo(dst, overwrite = true) }
        if (!dst.exists()) return null
        return androidx.core.content.FileProvider.getUriForFile(
            context, context.packageName + ".fileprovider", dst)
    }

    /**
     * Tail of the Swift core log for the in-app viewer (null when empty).
     * swift.log is capped at ~512KB so reading it whole is cheap.
     */
    suspend fun swiftLogText(maxLines: Int = 300): String? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val f = java.io.File(context.filesDir, "swift/swift.log")
            if (!f.exists() || f.length() == 0L) return@withContext null
            runCatching {
                val lines = f.readLines()
                if (lines.size <= maxLines) lines.joinToString("\n")
                else lines.takeLast(maxLines).joinToString("\n")
            }.getOrNull()
        }

    /**
     * Tail of the Xray core log for the in-app viewer (null when empty).
     * Uses the combined snapshot (xray_error + xray_access) that sharing uses.
     */
    suspend fun xrayLogText(maxLines: Int = 300): String? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val f = com.kuva.vpn.core.engine.CoreLogStore.snapshotFile(context)
                ?: return@withContext null
            runCatching {
                val lines = f.readLines()
                if (lines.size <= maxLines) lines.joinToString("\n")
                else lines.takeLast(maxLines).joinToString("\n")
            }.getOrNull()
        }

    // ---------------- in-app update (GitHub Releases) ----------------

    // patch 283: the update check moved to KuvaApplication (once per app
    // start) and the banner action to GitHubUpdater.openPlayStore — none of
    // the in-app update state machine is needed here anymore

    /** Stock Android layer: true while this app is ON the battery-optimization
     *  list (i.e. the OS may kill the VPN tunnel in the background). */
    val batteryRestricted = androidx.compose.runtime.mutableStateOf(false)

    /** True on Xiaomi/MIUI devices. There the stock battery dialog is rerouted
     *  to PowerKeeper's HiddenAppsConfigActivity and the package URI is dropped
     *  in transit (logcat 2026-11-17 18:19: `dat=package:` empty), so the
     *  dialog opens without a target app and the setting never sticks — the
     *  MIUI rows below are the primary path there. */
    val miuiDevice: Boolean = runCatching {
        android.os.Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)
    }.getOrDefault(false)

    /** Refreshes the battery-optimization flag (call on every resume). */
    fun refreshBatteryRestricted() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        batteryRestricted.value = !pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Opens the stock battery-optimization dialog for this app. */
    fun openBatteryOptimizationSettings() {
        runCatching {
            context.startActivity(
                android.content.Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:" + context.packageName),
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** Opens MIUI PowerKeeper's per-app battery screen DIRECTLY, with the
     *  target package passed as the extra PowerKeeper expects — bypassing the
     *  stock action whose rerouted intent loses the package URI. The user
     *  then picks "بدون محدودیت" and it sticks. */
    fun openMiuiBatterySettings() {
        val ok = runCatching {
            val intent = android.content.Intent().setClassName(
                "com.miui.powerkeeper",
                "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
            )
            intent.putExtra("package_name", context.packageName)
            intent.putExtra("package_uid", android.os.Process.myUid())
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }.isSuccess
        // PowerKeeper missing or renamed: fall back to the stock dialog
        if (!ok) openBatteryOptimizationSettings()
    }

    /** Opens MIUI's autostart manager. PowerKeeper restricts background work
     *  of apps without autostart regardless of the battery setting, and
     *  autostart is OFF by default for sideloaded apps. */
    fun openMiuiAutostart() {
        runCatching {
            val intent = android.content.Intent().setClassName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            )
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    fun openAppPicker() { /* nav event */ }
    fun openPrivacy() { /* nav event */ }
    fun openLicenses() { /* nav event */ }
}
