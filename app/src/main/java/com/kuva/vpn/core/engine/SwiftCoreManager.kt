package com.kuva.vpn.core.engine

import android.content.Context
import android.util.Log
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lifecycle manager for the bundled configless core ("Kuva Swift"): a stock
 * MASQUE/WARP userspace binary shipped per-ABI as libaether.so and exec'd as a
 * separate process. The process runs under the app's own uid, and the VPN
 * builder already excludes the app's uid from the TUN, so the core's outbound
 * sockets can never loop back into the tunnel.
 *
 * The core exposes a local SOCKS5 proxy; the existing TUN -> SOCKS pipeline
 * (hev-socks5-tunnel) forwards captured device traffic to it on the very same
 * port Xray uses, so no JNI work is involved here.
 *
 * The WARP identity is kept in filesDir/swift so restarts reuse the same
 * device registration instead of enrolling a new one every launch (the
 * registration endpoint rate-limits repeated enrollments).
 */
@Singleton
class SwiftCoreManager @Inject constructor() {

    @Volatile
    private var process: Process? = null

    /** Invoked when the core process dies. Set by the VPN service. */
    @Volatile
    var onExited: (() -> Unit)? = null

    /** True when the current ABI ships the core binary. */
    fun isAvailable(context: Context): Boolean = binary(context) != null

    private fun binary(context: Context): File? {
        val dir = context.applicationInfo.nativeLibraryDir ?: return null
        return File(dir, LIB_NAME).takeIf { it.exists() }
    }

    @Synchronized
    fun start(context: Context, bindPort: Int, scanMode: String = "balanced"): Boolean {
        if (process != null) return true
        val bin = binary(context) ?: run {
            Log.e(TAG, "swift core binary missing on this ABI")
            return false
        }
        killLeftovers()
        val cfgDir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
        val args = listOf(
            bin.absolutePath,
            "--masque", "--h2", "--fragment",
            "--scan", scanMode,
            "--noize", "firewall",
            "-4",
            "--quick-reconnect",
            "--bind", "127.0.0.1:$bindPort",
        )
        return try {
            val pb = ProcessBuilder(args)
            pb.directory(cfgDir)
            pb.environment()["HOME"] = cfgDir.absolutePath
            val p = pb.start()
            process = p
            // Drain both pipes continuously: a full pipe buffer would block the core.
            val log = File(cfgDir, LOG_NAME)
            Thread { runCatching { p.inputStream.bufferedReader().forEachLine { appendLog(log, it) } } }
                .apply { isDaemon = true }.start()
            Thread { runCatching { p.errorStream.bufferedReader().forEachLine { appendLog(log, it) } } }
                .apply { isDaemon = true }.start()
            Thread {
                val code = runCatching { p.waitFor() }.getOrDefault(-1)
                Log.w(TAG, "swift core exited (code=$code)")
                if (process === p) process = null
                onExited?.invoke()
            }.apply { isDaemon = true }.start()
            Log.i(TAG, "swift core started")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "swift core failed to start", t)
            process = null
            false
        }
    }

    /** Live progress while the core is coming up (0..100), read by the UI. */
    @Volatile
    var scanProgressPct: Int = 0
        private set

    /** Polls the SOCKS5 port; true as soon as the core accepts TCP there. */
    fun awaitSocksReady(bindPort: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        scanProgressPct = 5
        while (System.currentTimeMillis() < deadline) {
            if (process == null) { scanProgressPct = 0; return false }
            try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", bindPort), 500) }
                scanProgressPct = 100
                return true
            } catch (_: Exception) {
                // smooth ramp toward ~90%: the core IS scanning, we just can't
                // see inside it — the bar should feel alive, not lie
                scanProgressPct = (scanProgressPct + 2).coerceAtMost(90)
                onProgress?.invoke(scanProgressPct)
                try {
                    Thread.sleep(500)
                } catch (_: InterruptedException) {
                    scanProgressPct = 0
                    return false
                }
            }
        }
        scanProgressPct = 0
        return false
    }

    /** Progress callback wired by the service; called from the poller thread. */
    var onProgress: ((Int) -> Unit)? = null

    fun isRunning(): Boolean = process != null

    @Synchronized
    fun stop() {
        val p = process ?: return
        process = null
        onExited = null
        runCatching { p.destroy() }
        Thread {
            runCatching {
                if (!p.waitFor(2, TimeUnit.SECONDS)) {
                    p.destroyForcibly()
                    p.waitFor(2, TimeUnit.SECONDS)
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * A previous app process may have died without stopping the core; the
     * orphan keeps the SOCKS port busy and would silently serve the next
     * session. Same-uid processes are killable without root.
     */
    private fun killLeftovers() {
        runCatching {
            File("/proc").listFiles { f -> f.name.all { c -> c.isDigit() } }?.forEach { dir ->
                runCatching {
                    val cmd = File(dir, "cmdline").readText()
                    if (cmd.contains(LIB_NAME)) {
                        android.os.Process.killProcess(dir.name.toInt())
                    }
                }
            }
        }
    }

    private fun appendLog(file: File, line: String) {
        runCatching {
            if (file.length() > MAX_LOG_BYTES) file.delete()
            file.appendText(line + "\n")
        }
    }

    companion object {
        private const val TAG = "KuvaSwift"
        private const val LIB_NAME = "libaether.so"
        private const val DIR_NAME = "swift"
        private const val LOG_NAME = "swift.log"
        private const val MAX_LOG_BYTES = 512L * 1024
    }
}
