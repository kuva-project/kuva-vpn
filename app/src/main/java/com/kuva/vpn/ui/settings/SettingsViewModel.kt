package com.kuva.vpn.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kuva.vpn.data.settings.AppSettings
import com.kuva.vpn.data.settings.RoutingMode
import com.kuva.vpn.data.settings.SettingsRepository
import android.content.Context
import com.kuva.vpn.core.update.GitHubUpdater
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: SettingsRepository,
    private val geoUpdater: com.kuva.vpn.core.engine.GeoUpdater,
    private val updater: GitHubUpdater,
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
    fun setEngineMode(mode: String) = viewModelScope.launch { repo.setEngineMode(mode) }
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

    /** Pull the latest geo databases from the upstream repo (sha256 checked). */
    fun updateGeo() = viewModelScope.launch {
        _geoMessage.value = "Checking…"
        geoUpdater.update(context) { _geoMessage.value = it }
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

    // ---------------- in-app update (GitHub Releases) ----------------

    sealed class UpdateState {
        data object Idle : UpdateState()
        data object Checking : UpdateState()
        data object UpToDate : UpdateState()
        data class Available(
            val version: String,
            val assetName: String,
            val assetUrl: String,
        ) : UpdateState()
        data class Downloading(val percent: Int) : UpdateState()
        /** APK staged with the system installer; the OS prompt is next. */
        data object InstallPrompted : UpdateState()
        /** "Install unknown apps" not granted for this app — PackageInstaller
         *  would fail silently, so ask for it first. */
        data object PermissionNeeded : UpdateState()
        data class Failed(val message: String) : UpdateState()
    }

    private val _updateState = kotlinx.coroutines.flow.MutableStateFlow<UpdateState>(
        UpdateState.Idle
    )
    val updateState: kotlinx.coroutines.flow.StateFlow<UpdateState> = _updateState

    fun checkForUpdates() {
        if (_updateState.value is UpdateState.Checking ||
            _updateState.value is UpdateState.Downloading
        ) return
        _updateState.value = UpdateState.Checking
        viewModelScope.launch {
            try {
                val r = updater.check(com.kuva.vpn.BuildConfig.VERSION_NAME)
                _updateState.value = if (r.updateAvailable && r.assetUrl != null) {
                    UpdateState.Available(
                        version = r.latestVersion,
                        assetName = r.assetName ?: "",
                        assetUrl = r.assetUrl,
                    )
                } else {
                    UpdateState.UpToDate
                }
            } catch (t: Throwable) {
                _updateState.value = UpdateState.Failed(t.message ?: "network error")
            }
        }
    }

    fun downloadAndInstall(version: String, assetName: String, assetUrl: String) {
        if (_updateState.value is UpdateState.Downloading) return
        // without "install unknown apps" the PackageInstaller commit fails
        // silently — request it first, then the user retries the download
        if (!context.packageManager.canRequestPackageInstalls()) {
            _updateState.value = UpdateState.PermissionNeeded
            return
        }
        _updateState.value = UpdateState.Downloading(0)
        viewModelScope.launch {
            try {
                val apk = updater.download(assetUrl, assetName, context) { pct ->
                    _updateState.value = UpdateState.Downloading(pct)
                }
                updater.install(apk, context)
                _updateState.value = UpdateState.InstallPrompted
            } catch (t: Throwable) {
                _updateState.value = UpdateState.Failed(t.message ?: "download failed")
            }
        }
    }

    fun dismissUpdateState() {
        _updateState.value = UpdateState.Idle
    }

    /** True while this app is ON the battery-optimization list (i.e. the OS
     *  may kill the VPN tunnel in the background). */
    val batteryRestricted = androidx.compose.runtime.mutableStateOf(false)

    /** Refreshes the battery-optimization flag (call on resume). */
    fun refreshBatteryRestricted() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        batteryRestricted.value = !pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Opens the system battery-optimization screen for this app. */
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

    /** Opens the system "install unknown apps" screen for this app, then
     *  resets the flow so the user can retry the download. */
    fun openInstallPermissionSettings() {
        runCatching {
            context.startActivity(
                android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    android.net.Uri.parse("package:" + context.packageName),
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        _updateState.value = UpdateState.Idle
    }

    fun openAppPicker() { /* nav event */ }
    fun openPrivacy() { /* nav event */ }
    fun openLicenses() { /* nav event */ }
}
