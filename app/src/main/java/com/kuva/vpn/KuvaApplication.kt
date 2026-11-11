package com.kuva.vpn

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.kuva.vpn.data.db.ProfileDao
import com.kuva.vpn.data.settings.SettingsRepository
import com.kuva.vpn.core.update.GitHubUpdater
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject

@HiltAndroidApp
class KuvaApplication : Application() {

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var profileDao: ProfileDao
    @Inject lateinit var updater: GitHubUpdater

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** result of the once-per-app-start update check; drives the banner + red dot */
    val updateAvailable = MutableStateFlow(false)

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Every fresh process start (user pressed Exit, swiped the app away,
        // or the system killed it in the background) wipes stored ping/speed
        // numbers — old measurements must not be presented as current.
        appScope.launch {
            runCatching { profileDao.resetAllTestResults() }
        }
        // silent update check once per app start; failures are ignored —
        // the user just sees no banner / no red dot
        appScope.launch {
            runCatching {
                updateAvailable.value =
                    updater.check(BuildConfig.VERSION_NAME).updateAvailable
            }
        }
        // Fresh start always boots on the Xray engine: the engine switch is a
        // per-session choice, not a persisted preference.
        appScope.launch {
            runCatching { settings.setEngineMode("xray") }
        }
        appScope.launch {
            val s = settings.settings.first()
            applyTheme(s.themeMode)
        }
        // One-time asset prep (geoip/geosite) is done lazily by XrayCoreManager.initCoreEnv
    }

    companion object {
        @Volatile lateinit var instance: KuvaApplication
            private set

        fun applyTheme(mode: String) {
            AppCompatDelegate.setDefaultNightMode(
                when (mode) {
                    "light" -> AppCompatDelegate.MODE_NIGHT_YES  // light palette removed — dark only
                    "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_YES
                }
            )
        }
    }
}
