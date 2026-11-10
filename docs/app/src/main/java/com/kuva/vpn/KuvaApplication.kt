package com.kuva.vpn

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.kuva.vpn.data.db.ProfileDao
import com.kuva.vpn.data.settings.SettingsRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class KuvaApplication : Application() {

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var profileDao: ProfileDao

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Every fresh process start (user pressed Exit, swiped the app away,
        // or the system killed it in the background) wipes stored ping/speed
        // numbers — old measurements must not be presented as current.
        appScope.launch {
            runCatching { profileDao.resetAllTestResults() }
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
