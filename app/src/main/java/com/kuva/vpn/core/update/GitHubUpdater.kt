package com.kuva.vpn.core.update

import android.content.Context
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-app self-update from this project's GitHub Releases.
 *
 * Flow: query api.github.com/repos/kuva-project/kuva-vpn/releases/latest ->
 * compare tag with the running BuildConfig.VERSION_NAME -> pick the APK asset
 * matching this device's primary ABI (fall back to universal) -> stream it to
 * cache -> hand it to the system PackageInstaller. The user then sees the
 * standard Android install prompt; on a signed release build installed over
 * the existing one, data (profiles, settings) is preserved.
 *
 * No new permissions beyond REQUEST_INSTALL_PACKAGES (Settings -> install
 * unknown apps for this app). Downloads ride the active tunnel when the VPN
 * is up, which also makes the update reachable while GitHub is throttled.
 */
@Singleton
class GitHubUpdater @Inject constructor() {

    companion object {
        const val RELEASES_API =
            "https://api.github.com/repos/kuva-project/kuva-vpn/releases/latest"
        private const val TAG = "KuvaUpdater"
        private const val CONNECT_TIMEOUT = 10_000
        private const val READ_TIMEOUT = 30_000
    }

    /** Result of [check]: a newer release exists, or we are already current. */
    data class CheckResult(
        val updateAvailable: Boolean,
        val latestVersion: String,
        val notes: String,
        /** Asset name chosen for this device (null when nothing to download). */
        val assetName: String?,
        /** Download URL of the chosen asset (browser URL, redirects to S3). */
        val assetUrl: String?,
        val assetSizeBytes: Long,
    )

    /** Primary ABI of this device, in APK-asset naming. */
    fun deviceAbi(): String = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

    /** Asset selection: exact ABI match first, then universal, then any .apk. */
    private fun pickAsset(assets: org.json.JSONArray): Triple<String, String, Long>? {
        val abi = deviceAbi()
        var fallback: Triple<String, String, Long>? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name")
            if (!name.endsWith(".apk")) continue
            val url = a.optString("browser_download_url")
            val size = a.optLong("size", 0L)
            val t = Triple(name, url, size)
            if (name.contains(abi)) return t
            if (name.contains("universal") && fallback == null) fallback = t
        }
        return fallback
    }

    /** Query the latest release; cheap (no download). */
    suspend fun check(currentVersion: String): CheckResult = withContext(Dispatchers.IO) {
        val conn = URL(RELEASES_API).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT
        conn.readTimeout = READ_TIMEOUT
        conn.setRequestProperty("User-Agent", "KUVAVpn/$currentVersion")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (conn.responseCode != 200) {
                throw IllegalStateException("GitHub API HTTP ${conn.responseCode}")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val j = JSONObject(body)
            val tag = j.optString("tag_name", "").removePrefix("v")
            val assets = j.optJSONArray("assets") ?: org.json.JSONArray()
            val picked = pickAsset(assets)
            val newer = tag.isNotEmpty() && tag != currentVersion &&
                compareVersions(tag, currentVersion) > 0
            CheckResult(
                updateAvailable = newer && picked != null,
                latestVersion = tag,
                notes = j.optString("body", "").take(400),
                assetName = picked?.first.takeIf { newer },
                assetUrl = picked?.second.takeIf { newer },
                assetSizeBytes = picked?.third ?: 0L,
            )
        } finally {
            conn.disconnect()
        }
    }

    /** Stream the chosen asset into cacheDir and return the local file. */
    suspend fun download(
        url: String,
        name: String,
        ctx: Context,
        onProgress: (Int) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "KUVAVpn")
            try {
                if (conn.responseCode != 200) {
                    throw IllegalStateException("download HTTP ${conn.responseCode}")
                }
                val out = File(ctx.cacheDir, name)
                val tmp = File(ctx.cacheDir, "$name.part")
                val total = conn.contentLengthLong
                var copied = 0L
                var lastPct = -1
                conn.inputStream.use { input ->
                    tmp.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val r = input.read(buf)
                            if (r < 0) break
                            output.write(buf, 0, r)
                            copied += r
                            if (total > 0) {
                                val pct = (copied * 100 / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    onProgress(pct)
                                }
                            }
                        }
                    }
                }
                if (tmp.length() == 0L) throw IllegalStateException("empty download")
                tmp.renameTo(out) || (out.exists() && out.delete() && tmp.renameTo(out))
                out
            } finally {
                conn.disconnect()
            }
        }

    /** Hand the APK to the system installer (confirmation dialog shows). */
    fun install(apk: File, ctx: Context) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )
        val sessionId = pi.createSession(params)
        val session = pi.openSession(sessionId)
        try {
            session.openWrite(apk.name, 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = android.content.Intent("com.kuva.vpn.INSTALL_COMMITTED")
                .setPackage(ctx.packageName)
            val pending = android.app.PendingIntent.getBroadcast(
                ctx,
                sessionId,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_MUTABLE,
            )
            // The installer reports back on this action. While the session
            // waits for the user it broadcasts STATUS_PENDING_USER_ACTION
            // carrying the confirm Intent — WITHOUT launching that intent the
            // dialog never appears and the session dies silently (the old
            // bug: a receiver action existed but no receiver was ever
            // registered, so "install" did nothing).
            // holder indirection: the receiver needs its own reference to
            // unregister itself, but a val cannot be read inside its own
            // initializer in Kotlin
            val holder = arrayOfNulls<android.content.BroadcastReceiver>(1)
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context, i: android.content.Intent) {
                    try {
                        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
                            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                                // Intent.EXTRA_INTENT (not PackageInstaller.EXTRA_INTENT —
                                // that constant only exists on API 34+; the official
                                // sample reads the confirm intent from Intent.EXTRA_INTENT)
                                val confirm = i.getParcelableExtra<android.content.Intent>(
                                    android.content.Intent.EXTRA_INTENT)
                                if (confirm != null) {
                                    confirm.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    c.startActivity(confirm)
                                    Log.i(TAG, "install confirm dialog launched")
                                }
                            }
                            PackageInstaller.STATUS_SUCCESS ->
                                Log.i(TAG, "install succeeded")
                            else -> Log.w(TAG, "install status: " +
                                i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1) + " " +
                                i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
                        }
                    } finally {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            runCatching {
                                holder[0]?.let { c.applicationContext.unregisterReceiver(it) }
                            }
                        }
                    }
                }
            }
            androidx.core.content.ContextCompat.registerReceiver(
                ctx.applicationContext,
                receiver,
                android.content.IntentFilter("com.kuva.vpn.INSTALL_COMMITTED"),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            holder[0] = receiver
            session.commit(pending.intentSender)
            Log.i(TAG, "install session committed: ${apk.name} (${apk.length()} B)")
        } catch (t: Throwable) {
            session.abandon()
            throw t
        } finally {
            session.close()
        }
    }

    /** Semantic-ish compare: "1.2.10" > "1.2.9". Non-numeric parts ignored. */
    fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.').map { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
        val pb = b.split('.').map { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }
}
