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

    /** set when the user taps the update banner: Settings auto-runs a check */
    val runUpdateCheck = MutableStateFlow(false)

    override fun onCreate() {
        super.onCreate()
        instance = this
        installCrashHandler()
        // Every fresh process start (user pressed Exit, swiped the app away,
        // or the system killed it in the background) wipes stored ping/speed
        // numbers — old measurements must not be presented as current.
        appScope.launch {
            runCatching { profileDao.resetAllTestResults() }
        }
        // patch 223: every fresh app start begins with EMPTY logs so a
        // bug report never mixes old sessions with the current one (the
        // 2026-11-11 report still carried UTC lines from previous runs
        // alongside Iran-stamped lines). Cores recreate their files on
        // demand, so deleting here is safe — no core can be running yet:
        // the VPN service lives in this same process, so if it were up,
        // onCreate would not have run at all. Crash reports are kept.
        appScope.launch(Dispatchers.IO) {
            runCatching {
                java.io.File(filesDir, "swift/swift.log").delete()
                java.io.File(cacheDir, "logs/xray.log").delete()
                java.io.File(filesDir, "logs/xray_error.log").delete()
                java.io.File(filesDir, "logs/xray_access.log").delete()
                java.io.File(filesDir, "exports").listFiles()
                    ?.forEach { if (it.name.startsWith("swift_log_")) it.delete() }
            }
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
        // per-session choice, not a persisted preference. Psiphon is
        // per-session too — both the toggle and the exit country reset
        // (OFF + Auto) on every fresh app start.
        appScope.launch {
            runCatching {
                settings.setEngineMode("xray")
                settings.setSwiftPsiphon(false)
                settings.setSwiftPsiphonCountry("")
            }
        }
        appScope.launch {
            val s = settings.settings.first()
            applyTheme(s.themeMode)
        }

        // One-time asset prep (geoip/geosite) is done lazily by XrayCoreManager.initCoreEnv
    }

    /**
     * Persists uncaught exceptions to files/crash/ so the bug-report zip can
     * carry them along. The previous handler still runs afterwards, so the
     * system crash flow (dialog, process death) behaves exactly as before.
     * Keeps only the 5 newest files.
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val dir = java.io.File(filesDir, "crash").apply { mkdirs() }
                val stamp = java.text.SimpleDateFormat(
                    "yyyy-MM-dd_HH-mm-ss", java.util.Locale.US
                ).format(java.util.Date())
                java.io.File(dir, "crash_$stamp.txt").writeText(
                    buildString {
                        appendLine("time: $stamp")
                        appendLine("thread: " + thread.name)
                        appendLine("app: " + packageName + " v" + BuildConfig.VERSION_NAME)
                        appendLine(
                            "device: " + android.os.Build.MANUFACTURER + " " +
                                android.os.Build.MODEL + " (Android " +
                                android.os.Build.VERSION.RELEASE + ", SDK " +
                                android.os.Build.VERSION.SDK_INT + ")"
                        )
                        appendLine()
                        appendLine(android.util.Log.getStackTraceString(e))
                    }
                )
                dir.listFiles()
                    ?.sortedByDescending { it.name }
                    ?.drop(5)
                    ?.forEach { it.delete() }
            }
            previous?.uncaughtException(thread, e)
        }
    }

    companion object {
        @Volatile lateinit var instance: KuvaApplication
            private set

        fun applyTheme(mode: String) {
            // setDefaultNightMode ends up calling Activity.recreate(), which
            // must run on the main thread. Callers fire this from coroutine
            // workers (Application onCreate, SettingsViewModel) and that
            // crashed with "Must be called from main thread" — posting to
            // the main looper makes it safe from any thread.
            android.os.Handler(android.os.Looper.getMainLooper()).post {
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
}
