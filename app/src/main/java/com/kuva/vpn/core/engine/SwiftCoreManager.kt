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

    /**
     * True once the current core process has confirmed end-to-end data flow
     * through the MASQUE tunnel (the "tunnel validated" log line). The VPN
     * service waits for this instead of guessing with a fixed timer, so the
     * UI never claims Connected before traffic actually flows.
     */
    @Volatile
    var tunnelValidated: Boolean = false
        private set

    /**
     * True once the core printed "cloudflare, warp on" — in HTTP/3 mode the
     * QUIC path needs a few extra seconds after "tunnel validated" before
     * traffic actually flows; this line marks the moment it does.
     */
    @Volatile
    var warpReady: Boolean = false
        private set

    /**
     * True once the core printed "psiphon is ready" - the moment the local
     * psiphon inbound actually carries traffic. In --psiphon-reverse mode
     * the MASQUE tunnel is dialled THROUGH psiphon, so this fires before
     * "tunnel validated" and small psiphon regions (FI, BE, ...) can take
     * a minute or more to reach it. The VPN service uses it to keep the
     * progress bar honest instead of guessing with a fixed timer.
     */
    @Volatile
    var psiphonReady: Boolean = false
        private set

    /**
     * True once the core printed its own scan progress lines — from then on
     * the progress bar is driven by REAL scan events (each "candidate ok" is
     * a gateway found), not by the time-based fallback ramp.
     */
    @Volatile
    private var logDrivenProgress: Boolean = false

    /**
     * Maps the core's scan log lines to real progress. The core stops after
     * ~6 gateways ("reached target of 6 gateways"), so every "candidate ok"
     * is roughly one sixth of the scan work; "skipping scan" means the cached
     * gateway answered and no scan happens at all.
     */
    private fun parseScanLine(line: String) {
        // Real-event progress map (patch 122). Every event must land at or
        // above the current pct — the bar NEVER moves backwards, so mixed
        // paths (cached gateway dies mid-handshake, then a full scan) stay
        // monotonic. Stage keys ride along; the UI resolves them via
        // stringResource (fa + en) — the manager never hardcodes user text.
        val event = when {
            line.contains("skipping scan") -> 30 to STAGE_SAVED_CONFIRMED
            line.contains("scan mode=") -> 40 to STAGE_FINDING_SERVER
            line.contains("candidate ok") -> {
                // each real gateway found: +5 from the scan start (40) up
                // to 68 — the tunnel stages keep honest head-room above
                val p = (scanProgressPct.coerceAtLeast(40) + 5).coerceAtMost(68)
                p to STAGE_FINDING_SERVER
            }
            line.contains("best gateway") || line.contains("reached target") -> 72 to STAGE_SELECTING
            line.contains("tls established") -> 80 to STAGE_TUNNEL
            line.contains("connect-ip status: 200") -> 88 to STAGE_TUNNEL
            line.contains("validating data-plane") -> 93 to STAGE_TESTING
            line.contains("tunnel validated") -> 97 to STAGE_TESTING
            line.contains("warp on") -> 98 to STAGE_FINAL
            else -> null
        } ?: return
        val (pct, stage) = event
        if (pct >= scanProgressPct) {
            logDrivenProgress = true
            scanProgressPct = pct
            lastStageKey = stage
            onProgress?.invoke(scanProgressPct)
            onStage?.invoke(pct, stage)
        }
    }

    // Stage keys live in the class companion object (bottom of file) —
    // Kotlin allows only ONE companion object per class.

    /** True when the current ABI ships the core binary. */
    fun isAvailable(context: Context): Boolean = binary(context) != null

    /**
     * Fired when the core's log reveals the REAL exit through Psiphon:
     * "psiphon through the tunnel exit: <ip>, <CC> via <city>, <rtt>ms ...".
     * The VPN service forwards this to the UI so the exit-IP card switches
     * from the WARP (masque) exit to the Psiphon one as soon as Psiphon is
     * ready, without depending on a client-side probe that ran too early.
     */
    @Volatile
    var onPsiphonExit: ((String, String) -> Unit)? = null

    /**
     * Fired when the core reports the MASQUE tunnel dying while the core
     * process itself keeps running ("MASQUE tunnel ended: ... reconnecting"
     * — h2 keepalive timeout, connection aborted, ...). The core reconnects
     * on its own; the service uses this to stop claiming Connected while
     * no traffic flows, then waits for [tunnelRestored].
     */
    @Volatile
    var onTunnelEnded: (() -> Unit)? = null

    /**
     * Latch for the core's own reconnect: false at startup, flips false on
     * every "MASQUE tunnel ended" line and true on every "tunnel validated"
     * line (which the core prints again after each successful reconnect).
     * The service polls this while waiting for the core to come back.
     */
    @Volatile
    var tunnelRestored: Boolean = false
        private set

    /** Parsed lazily on the log thread; ip and country code of the Psiphon exit. */
    private fun parsePsiphonExit(line: String) {
        val m = PSIPHON_EXIT_RE.find(line) ?: return
        onPsiphonExit?.invoke(m.groupValues[1], m.groupValues[2])
    }



    private fun binary(context: Context): File? {
        val dir = context.applicationInfo.nativeLibraryDir ?: return null
        return File(dir, LIB_NAME).takeIf { it.exists() }
    }

    @Synchronized
    fun start(
        context: Context,
        bindPort: Int,
        scanMode: String = "balanced",
        h3: Boolean = false,
        scan: Boolean = true,
        psiphon: Boolean = false,
        // preferred exit country, "" = Auto (ISO-2 like "DE"; the core
        // validates two ASCII letters and uppercases them itself)
        psiphonRegion: String = "",
    ): Boolean {
        if (process != null) return true
        val bin = binary(context) ?: run {
            Log.e(TAG, "swift core binary missing on this ABI")
            return false
        }
        killLeftovers()
        tunnelValidated = false
        warpReady = false
        psiphonReady = false
        logDrivenProgress = false
        tunnelRestored = false
        lastStageKey = null
        val cfgDir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
        // transport: HTTP/3 (QUIC) when enabled, HTTP/2 otherwise.
        // Psiphon mode always rides HTTP/2: with --psiphon-reverse the core
        // dials the tunnel through psiphon on an http/2 carrier and sets
        // AETHER_MASQUE_HTTP2=1 itself, so --h3 would be ignored/misleading.
        val transport = if (h3 && !psiphon) "--h3" else "--h2"
        val args = buildList {
            add(bin.absolutePath)
            add("--masque"); add(transport); add("--fragment")
            // The endpoint scan has no part in psiphon mode: the tunnel is
            // dialled through psiphon, so a scan would measure endpoints from
            // psiphon's exit and slow the start down (PattNG keeps psiphon
            // off while scanning too).
            if (scan && !psiphon) { add("--scan"); add(scanMode) }
            if (psiphon) {
                // --psiphon (chain) puts psiphon INSIDE the tunnel: the socks
                // inbound still exits via WARP, so the exit country never
                // applies and user traffic never passes through psiphon.
                // --psiphon-reverse turns it around: the whole MASQUE tunnel
                // is dialled THROUGH psiphon, so TCP and UDP/DNS alike leave
                // from the psiphon region the user picked, while the app
                // keeps dialling the core's own socks port (the psiphon
                // relay listener is TCP-only and must never carry tun2socks).
                add("--psiphon-reverse")
                // aether's --psiphon-region maps to AETHER_PSIPHON_REGION and
                // only accepts exactly two ASCII letters; anything else would
                // silently fall back to Auto, so we pre-filter here too
                val cc = psiphonRegion.trim().uppercase()
                if (cc.length == 2 && cc.all { it in 'A'..'Z' }) {
                    add("--psiphon-region"); add(cc)
                }
            }
            add("--noize"); add("firewall")
            add("-4")
            add("--quick-reconnect")
            add("--bind"); add("127.0.0.1:$bindPort")
        }
        return try {
            val pb = ProcessBuilder(args)
            pb.directory(cfgDir)
            pb.environment()["HOME"] = cfgDir.absolutePath
            if (psiphon) {
                // The core spawns psiphon-tunnel-core as a helper process. We
                // ship it per-ABI as libpt.so so Android's package installer
                // extracts it next to libaether.so in nativeLibraryDir (the
                // "pt/" folder layout the core expects on desktop is not
                // possible on Android — only lib*.so files get extracted).
                // AETHER_PSIPHON_BIN points the core straight at it.
                val pt = File(bin.parentFile, PT_NAME)
                if (!pt.exists()) {
                    Log.e(TAG, "psiphon helper binary missing on this ABI")
                    return false
                }
                pb.environment()["AETHER_PSIPHON_BIN"] = pt.absolutePath

                // Psiphon dials its CDN fronting hosts (akamai, fastly, s3)
                // BEFORE the tunnel exists, so its own resolver must use plain
                // public DNS: with --psiphon-reverse the system resolver is
                // the VPN's own DNS and is not up yet, so every lookup fails
                // with "read: connection refused" and psiphon never becomes
                // ready. The core merges this file over its built-in config
                // (AETHER_PSIPHON_CONFIG, psiphon.rs read_base_config);
                // PattNG ships the same overlay.
                val overlay = File(cfgDir, "psiphon-overlay.json")
                overlay.writeText(
                    "{\"DNSResolverAlternateServers\": " +
                        "[\"1.1.1.1\", \"1.0.0.1\", \"8.8.8.8\", \"8.8.4.4\"]}"
                )
                pb.environment()["AETHER_PSIPHON_CONFIG"] = overlay.absolutePath
            }
            val p = pb.start()
            process = p
            // Drain both pipes continuously: a full pipe buffer would block the core.
            val log = File(cfgDir, LOG_NAME)
            Thread {
                runCatching {
                    p.inputStream.bufferedReader().forEachLine {
                        appendLog(log, it)
                        parseScanLine(it)
                        parsePsiphonExit(it)
                        if (it.contains("identity ready")) {
                            if (15 >= scanProgressPct) {
                                scanProgressPct = 15
                                lastStageKey = STAGE_IDENTITY
                                onProgress?.invoke(15)
                                onStage?.invoke(15, STAGE_IDENTITY)
                            }
                        }
                        if (!tunnelValidated && it.contains("tunnel validated")) tunnelValidated = true
                        if (it.contains("tunnel validated")) tunnelRestored = true
                        if (it.contains("MASQUE tunnel ended")) {
                            tunnelRestored = false
                            onTunnelEnded?.invoke()
                        }
                        if (!warpReady && it.contains("warp on")) warpReady = true
                        if (!psiphonReady && it.contains("psiphon is ready")) psiphonReady = true
                    }
                }
            }.apply { isDaemon = true }.start()
            Thread {
                runCatching {
                    p.errorStream.bufferedReader().forEachLine {
                        appendLog(log, it)
                        // aether logs on stderr: "psiphon through the tunnel
                        // exit: ..." lives here, NOT on stdout
                        parsePsiphonExit(it)
                        if (it.contains("identity ready")) {
                            if (15 >= scanProgressPct) {
                                scanProgressPct = 15
                                lastStageKey = STAGE_IDENTITY
                                onProgress?.invoke(15)
                                onStage?.invoke(15, STAGE_IDENTITY)
                            }
                        }
                        if (!tunnelValidated && it.contains("tunnel validated")) tunnelValidated = true
                        if (it.contains("tunnel validated")) tunnelRestored = true
                        if (it.contains("MASQUE tunnel ended")) {
                            tunnelRestored = false
                            onTunnelEnded?.invoke()
                        }
                        if (!warpReady && it.contains("warp on")) warpReady = true
                        if (!psiphonReady && it.contains("psiphon is ready")) psiphonReady = true
                    }
                }
            }
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
                // fallback ramp only: when the core prints its own scan
                // lines, parseScanLine drives the bar with REAL events and
                // this timer must not fight it
                if (!logDrivenProgress) {
                    // core silent so far: honest slow ramp capped at 38 so
                    // it can never fake the tunnel stages (80+)
                    scanProgressPct = (scanProgressPct + 2).coerceAtLeast(3).coerceAtMost(38)
                    onProgress?.invoke(scanProgressPct)
                    onStage?.invoke(scanProgressPct, STAGE_PREPARE)
                }
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

    /**
     * Stage callback wired by the service: (pct, stageKey). Fired only from
     * real core log events in parseScanLine, so the label always matches
     * what the bar shows.
     */
    var onStage: ((Int, String) -> Unit)? = null

    /** Last stage key reported by the core's own log (null before any). */
    @Volatile
    var lastStageKey: String? = null
        private set

    fun isRunning(): Boolean = process != null

    @Synchronized
    fun stop() {
        val p = process ?: return
        process = null
        onExited = null
        tunnelValidated = false
        warpReady = false
        psiphonReady = false
        logDrivenProgress = false
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
                    if (cmd.contains(LIB_NAME) || cmd.contains(PT_NAME)) {
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
        // "psiphon through the tunnel exit: 217.160.50.254, DE via FRA"
        private val PSIPHON_EXIT_RE = Regex(
            "psiphon through the tunnel exit: ([0-9a-fA-F:.]+), ([A-Z]{2}) via ([A-Z]{3})"
        )
        private const val TAG = "KuvaSwift"
        private const val LIB_NAME = "libaether.so"
        private const val PT_NAME = "libpt.so"
        private const val DIR_NAME = "swift"
        private const val LOG_NAME = "swift.log"
        private const val MAX_LOG_BYTES = 512L * 1024

        // Swift progress stage keys (patch 122) — resolved to localized
        // strings in the UI (fa + en); the manager never hardcodes user text
        const val STAGE_PREPARE = "prepare"
        const val STAGE_IDENTITY = "identity"
        const val STAGE_CHECK_CACHED = "check_cached"
        const val STAGE_SAVED_CONFIRMED = "saved_confirmed"
        const val STAGE_FINDING_SERVER = "finding_server"
        const val STAGE_SELECTING = "selecting"
        const val STAGE_TUNNEL = "tunnel"
        const val STAGE_TESTING = "testing"
        const val STAGE_FINAL = "final"
    }
}
