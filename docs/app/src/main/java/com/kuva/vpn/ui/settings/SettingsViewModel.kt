package com.kuva.vpn.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kuva.vpn.data.settings.AppSettings
import com.kuva.vpn.data.settings.RoutingMode
import com.kuva.vpn.data.settings.SettingsRepository
import android.content.Context
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

    fun openAppPicker() { /* nav event */ }
    fun openPrivacy() { /* nav event */ }
    fun openLicenses() { /* nav event */ }
}
