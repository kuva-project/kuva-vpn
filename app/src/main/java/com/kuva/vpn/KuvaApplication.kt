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

    /** Latest GitHub release version when it differs from the installed one;
     *  null = up to date or the check failed. Drives the dashboard banner,
     *  which stays visible until the app is actually updated (patch 283). */
    val latestVersion = MutableStateFlow<String?>(null)

    override fun onCreate() {
        super.onCreate()
        instance = this
        // patch 262: set the dark-only theme ONCE here, synchronously, before
        // any Activity exists — applyDayNight then finds zero activities to
        // recreate. Every later applyTheme call hits the patch-261 guard
        // (mode already YES) and becomes a no-op, so NO recreation can ever
        // happen during the app's lifetime — the cold-start jump ("پرش رو به
        // بالا") is structurally gone.
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
        )
        installCrashHandler()
        // patch 283: once-per-app-start GitHub update check — when a newer
        // release exists, latestVersion is set and the dashboard shows the
        // update banner (tap opens the Play Store page) until updated
        appScope.launch {
            runCatching {
                val r = updater.check(com.kuva.vpn.BuildConfig.VERSION_NAME)
                if (r.updateAvailable) latestVersion.value = r.latestVersion
            }
        }
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
            // patch 261: this app is dark-only (every mode maps to
            // MODE_NIGHT_YES). Calling setDefaultNightMode again while it is
            // already YES still ends up in Activity.recreate(), which fired
            // mid-draw on cold start and made the whole UI jump up for a
            // frame ("پرش رو به بالا"). Skip entirely when the requested
            // mode is already active — the first call at process start sets
            // YES once, every later call (KuvaApplication, MainActivity,
            // SettingsViewModel) becomes a no-op.
            if (AppCompatDelegate.getDefaultNightMode()
                == AppCompatDelegate.MODE_NIGHT_YES
            ) return
            // setDefaultNightMode ends up calling Activity.recreate(), which
            // must run on the main thread. Callers fire this from coroutine
            // workers (Application onCreate, SettingsViewModel) and that
            // crashed with "Must be called from main thread" — posting to
            // the main looper makes it safe from any thread.
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                AppCompatDelegate.setDefaultNightMode(
                    AppCompatDelegate.MODE_NIGHT_YES  // dark only — patch 261
                )
            }
        }
    }
}
