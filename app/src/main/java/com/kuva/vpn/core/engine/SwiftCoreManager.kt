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

    // patch 236: handle of the shared swift.log, captured in start() so the
    // service can append its own decisions ([service] lines) to the SAME
    // file the core writes — one file tells the whole session's story.
    @Volatile
    private var logFile: java.io.File? = null

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

    /** Service-side reset before waiting for the core's next latch. */
    fun resetTunnelRestoredLatch() {
        tunnelRestored = false
    }

    /**
     * Fired when tunnel ends BURST (>= GATE_BURST_LIMIT within
     * GATE_BURST_WINDOW_MS) — the poisoned-gateway pattern (Hamrah-Aval
     * 2026-11: h2 handshake ok, data plane stalled 30-60 s in, core loops
     * "keepalive timeout -> retrying last known-good gateway" forever).
     * The service stops the core, wipes the gateway cache and reconnects,
     * so the fresh process rescans and lands on a different edge.
     */
    @Volatile
    var onGatePoisoned: (() -> Unit)? = null

    /**
     * patch 219: fired ONCE per core process when the psiphon layer inside
     * the tunnel dies ("psiphon: tunnel failed" / "close tunnel ssh error",
     * real case 2026-11-16: sendSshKeepAlive EOF after 9 min). The MASQUE
     * tunnel and the core itself stay alive, so onExited never fires and
     * user traffic silently falls back to the raw WARP exit — IR-geolocated
     * on Hamrah-Aval. The service cuts tun2socks immediately so not a byte
     * leaves via WARP, waits for psiphon-tunnel-core's own reconnect
     * ("psiphon is ready" again) and restores the data path, or restarts
     * the core (fast: the lastconn gateway cache is kept).
     */
    @Volatile
    var onPsiphonDied: (() -> Unit)? = null

    /** One-shot latch for [onPsiphonDied] per core process. */
    @Volatile
    var psiphonDied: Boolean = false
        private set

    private val gateEndTimes = ArrayDeque<Long>()

    @Volatile
    private var lastGateNoteAt = 0L

    /**
     * patch 219: psiphon death lines (stdout AND stderr — both drain
     * threads call this). Also clears psiphonReady so the service's wait
     * loop keys on the NEXT "psiphon is ready", i.e. a genuine reconnect.
     */
    private fun notePsiphonDeath(line: String) {
        if (line.contains("psiphon: tunnel failed") ||
            line.contains("psiphon: close tunnel ssh error")
        ) {
            psiphonReady = false
            if (!psiphonDied) {
                psiphonDied = true
                Log.w(TAG, "psiphon layer died — service will cut traffic and wait for its reconnect")
                onPsiphonDied?.invoke()
            }
        }
    }

    private fun noteTunnelEnded() {
        val now = System.currentTimeMillis()
        // the same core log line can arrive on stdout AND stderr — the two
        // drain threads must not double-count one event (real tunnel ends
        // are always >= 2 s apart: the core waits AETHER_MASQUE_RECONNECT_SECS)
        if (now - lastGateNoteAt < 1_000L) return
        lastGateNoteAt = now
        synchronized(gateEndTimes) {
            gateEndTimes.addLast(now)
            while (gateEndTimes.isNotEmpty() && now - gateEndTimes.first() > GATE_BURST_WINDOW_MS) {
                gateEndTimes.removeFirst()
            }
            if (gateEndTimes.size >= GATE_BURST_LIMIT) {
                gateEndTimes.clear()
                onGatePoisoned?.invoke()
            }
        }
    }

    /** Clear the burst counter — the service calls it on every fresh connect. */
    fun resetGateBurst() {
        synchronized(gateEndTimes) { gateEndTimes.clear() }
    }

    /**
     * Delete the core's cached last-working-gateway files
     * (aether-*-lastconn.toml, saved next to the identity). The identity
     * files are kept — deleting those would re-register the device and
     * Cloudflare rate-limits registration.
     */
    fun clearGatewayCache(context: Context) {
        val dir = File(context.filesDir, DIR_NAME)
        dir.listFiles()?.forEach { f ->
            if (f.name.endsWith("-lastconn.toml")) runCatching { f.delete() }
        }
    }

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
        // patch 217: how psiphon rides the tunnel — "chain" (--psiphon:
        // psiphon inside the tunnel, exit = psiphon, bootstrap never meets
        // the DPI) or "reverse" (--psiphon-reverse: tunnel dialled THROUGH
        // psiphon, exit = WARP)
        psiphonMode: String = "chain",
        // preferred exit country, "" = Auto (ISO-2 like "DE"; the core
        // validates two ASCII letters and uppercases them itself)
        psiphonRegion: String = "",
        // anti-DPI controls (patch 216) — all default OFF, matching the
        // core's own defaults and PattNG's out-of-the-box behaviour
        fragment: Boolean = false,
        noize: Boolean = false,
        // custom MASQUE SNI; "" = core default (www.cloudflare.com on 2.4.0)
        sni: String = "",
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
        psiphonDied = false
        lastStageKey = null
        synchronized(gateEndTimes) { gateEndTimes.clear() }
        val cfgDir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
        logFile = File(cfgDir, LOG_NAME)
        // transport: HTTP/3 (QUIC) when enabled, HTTP/2 otherwise.
        // Psiphon mode always rides HTTP/2: with --psiphon-reverse the core
        // dials the tunnel through psiphon on an http/2 carrier and sets
        // AETHER_MASQUE_HTTP2=1 itself, so --h3 would be ignored/misleading.
        val transport = if (h3 && !psiphon) "--h3" else "--h2"
        val args = buildList {
            add(bin.absolutePath)
            add("--masque"); add(transport)
            // ClientHello fragmenting is opt-in since patch 216: with the
            // flag always on, Hamrah-Aval accepted the h2 control plane but
            // stalled the data plane 30-60 s in (keepalive-timeout loop).
            // The core's own default is fragment OFF.
            if (fragment) add("--fragment")
            // Endpoint scan and psiphon: in reverse mode the tunnel is
            // dialled through psiphon, so a scan would measure endpoints
            // from psiphon's exit and slow the start down. In chain mode
            // (patch 217) the tunnel dials directly from the network and
            // the scan behaves exactly like plain Swift, so it is kept —
            // a fast gateway wins seconds there too.
            if (scan && !psiphon) { add("--scan"); add(scanMode) }
            if (scan && psiphon && psiphonMode == "chain") { add("--scan"); add(scanMode) }
            if (psiphon) {
                // --psiphon (chain, the default since patch 217) carries
                // psiphon INSIDE the tunnel: the MASQUE tunnel connects
                // directly from the network, psiphon bootstraps through it
                // (its meek round-trips never meet the DPI), and user
                // traffic leaves from the psiphon exit the region picked.
                // --psiphon-reverse turns it around: the whole MASQUE tunnel
                // is dialled THROUGH psiphon, so TCP and UDP/DNS alike leave
                // from the psiphon region while the final exit stays WARP.
                // Either way the app keeps dialling the core's own socks
                // port (the psiphon relay listener is TCP-only and must
                // never carry tun2socks).
                if (psiphonMode == "chain") add("--psiphon") else add("--psiphon-reverse")
                // aether's --psiphon-region maps to AETHER_PSIPHON_REGION and
                // only accepts exactly two ASCII letters; anything else would
                // silently fall back to Auto, so we pre-filter here too
                val cc = psiphonRegion.trim().uppercase()
                if (cc.length == 2 && cc.all { it in 'A'..'Z' }) {
                    add("--psiphon-region"); add(cc)
                }
            }
            if (noize) { add("--noize"); add("firewall") }
            add("-4")
            add("--quick-reconnect")
            // custom MASQUE SNI (patch 216): passed only when the user set
            // one; empty keeps the core's default (www.cloudflare.com)
            if (sni.isNotBlank()) { add("--masque-sni"); add(sni) }
            // patch 220: the --bind proxy ALWAYS exits via WARP. In chain
            // mode that would silently send user traffic out of the WARP
            // exit (IR-geolocated on Hamrah-Aval, 2026-11-16) while the
            // psiphon layer only serves its OWN port (1821) — exactly like
            // --tor: the usual proxy keeps the WARP exit and a second one
            // comes out of the nested layer (DOCS.en.md, Tor table). So in
            // chain mode the core's WARP proxy moves aside and the user's
            // main port is given to psiphon via --psiphon-bind (v2.4.0):
            // tun2socks then dials the psiphon exit directly.
            if (psiphon && psiphonMode == "chain") {
                add("--bind"); add("127.0.0.1:${bindPort + 2}")
                add("--psiphon-bind"); add("127.0.0.1:$bindPort")
            } else {
                add("--bind"); add("127.0.0.1:$bindPort")
            }
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
                            noteTunnelEnded()
                            onTunnelEnded?.invoke()
                        }
                        if (!warpReady && it.contains("warp on")) warpReady = true
                        if (!psiphonReady && it.contains("psiphon is ready")) psiphonReady = true
                        notePsiphonDeath(it)
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
                            noteTunnelEnded()
                            onTunnelEnded?.invoke()
                        }
                        if (!warpReady && it.contains("warp on")) warpReady = true
                        if (!psiphonReady && it.contains("psiphon is ready")) psiphonReady = true
                        notePsiphonDeath(it)
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
    /**
     * Active liveness probe through the local SOCKS port: minimal SOCKS5
     * handshake, CONNECT to cp.cloudflare.com:80 (WARP's own health
     * endpoint) and a tiny HEAD /generate_204 through the tunnel. Catches
     * silent tunnel death that neither the process-exit callback nor the
     * log-line watchdog can see. True only when a full HTTP 204 answer came
     * back within the timeout; any hiccup (connect refusal, SOCKS error,
     * read timeout) is a failure.
     */
    fun tunnelProbeReason(socksPort: Int, timeoutMs: Int): String? {
        var sock: Socket? = null
        return try {
            sock = Socket()
            sock.soTimeout = timeoutMs
            sock.connect(InetSocketAddress("127.0.0.1", socksPort), timeoutMs)
            val out = sock.getOutputStream()
            val inp = sock.getInputStream()
            // SOCKS5 greeting: no-auth
            out.write(byteArrayOf(0x05, 0x01, 0x00)); out.flush()
            val greet = ByteArray(2)
            if (readFully(inp, greet) != 2 ||
                greet[0] != 0x05.toByte() || greet[1] != 0x00.toByte()
            ) return "socks5 greeting failed"
            // CONNECT cp.cloudflare.com:80 by domain name (ATYP=3)
            val host = PROBE_HOST.toByteArray(Charsets.US_ASCII)
            val req = ByteArray(5 + host.size + 2)
            req[0] = 0x05; req[1] = 0x01; req[2] = 0x00
            req[3] = 0x03; req[4] = host.size.toByte()
            host.copyInto(req, 5)
            req[req.size - 2] = 0x00; req[req.size - 1] = 0x50  // port 80
            out.write(req); out.flush()
            // reply: VER REP RSV ATYP + variable-length BND.ADDR + BND.PORT
            val head4 = ByteArray(4)
            if (readFully(inp, head4) != 4 || head4[1] != 0x00.toByte()) return "socks5 connect refused"
            val addrLen = when (head4[3].toInt()) {
                0x01 -> 4
                0x03 -> {
                    val l = ByteArray(1)
                    if (readFully(inp, l) != 1) return "socks5 reply truncated"
                    l[0].toInt() and 0xFF
                }
                0x04 -> 16
                else -> return "socks5 bad address type"
            }
            val tail = ByteArray(addrLen + 2)
            if (readFully(inp, tail) != tail.size) return "socks5 reply truncated"
            // the real probe: 204 means the tunnel actually carries data
            val http = ("HEAD /generate_204 HTTP/1.1\r\n" +
                "Host: $PROBE_HOST\r\n" +
                "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
            out.write(http); out.flush()
            val buf = ByteArray(32)
            val n = inp.read(buf)
            if (n <= 0) return "no http answer (tunnel may be stalled)"
            val first = String(buf, 0, n, Charsets.US_ASCII)
            if (first.startsWith("HTTP/1.1 204")) return null
            // patch 240: ANY valid HTTP answer proves the tunnel carries data.
            // On IR-exit WARP, Cloudflare's own health endpoint sometimes
            // answers 403 Forbidden (challenge) while normal traffic flows —
            // the 2026-11-18 swift.log shows 13 such "failures" on a healthy
            // tunnel. Report it as "endpoint challenged" so the caller can
            // treat it as alive without losing the detail.
            if (first.startsWith("HTTP/")) return "endpoint challenged: $first"
            return "unexpected answer: $first"
        } catch (t: Throwable) {
            t.javaClass.simpleName + ": " + (t.message ?: "probe error")
        } finally {
            runCatching { sock?.close() }
        }
    }

    /**
     * Boolean wrapper over tunnelProbeReason (patch 238) — true only when a
     * full HTTP 204 answer came back within the timeout.
     */
    fun tunnelProbe(socksPort: Int, timeoutMs: Int): Boolean =
        tunnelProbeReason(socksPort, timeoutMs) == null

    /**
     * Liveness only (patch 240): true when the tunnel carried ANY data —
     * a clean 204 or any HTTP answer (e.g. a 403 challenge from the health
     * endpoint). False only for real failures: timeouts, refusals, broken
     * handshakes, no answer.
     */
    fun tunnelAlive(socksPort: Int, timeoutMs: Int): Boolean {
        val r = tunnelProbeReason(socksPort, timeoutMs)
        return r == null || r.startsWith("endpoint challenged")
    }

    /** Exact SOCKS5 reply-read: blocks until buf is full or EOF. */
    private fun readFully(inp: java.io.InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = inp.read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
        return off
    }

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
        psiphonDied = false
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

    /**
     * patch 236: service-side logging into swift.log. The core only logs
     * its own view; decisions of the app layer (user disconnect, connect
     * requests, health-probe failures, network changes) were invisible,
     * which made incident reports (2026-11-17 dashboard bug) impossible to
     * close from the log file alone. Prefix "[service]" separates app-layer
     * lines from core lines; same Iran-time stamp format as appendLog.
     * Never throws — logging must not break the connection path.
     */
    fun serviceLog(line: String) {
        runCatching {
            val f = logFile ?: return
            if (f.length() > MAX_LOG_BYTES) f.delete()
            val stamp = IRAN_LOG_TIME.format(java.time.ZonedDateTime.now(IRAN_ZONE))
            f.appendText("[$stamp] [service] $line\n")
        }
    }

    private fun appendLog(file: File, line: String) {
        runCatching {
            if (file.length() > MAX_LOG_BYTES) file.delete()
            // patch 221: the core logs its own timestamps in UTC, which made
            // log reports confusing (a 18:58 log was actually 22:28 local).
            // Prefix every line with an Iran-time stamp in brackets; the
            // core's UTC stamp stays after it for exact comparison.
            // DateTimeFormatter is thread-safe — both drain threads call this.
            val stamp = IRAN_LOG_TIME.format(java.time.ZonedDateTime.now(IRAN_ZONE))
            file.appendText("[$stamp] $line\n")
        }
    }

    companion object {
        // "psiphon through the tunnel exit: 217.160.50.254, DE via FRA"
        private val PSIPHON_EXIT_RE = Regex(
            "psiphon through the tunnel exit: ([0-9a-fA-F:.]+), ([A-Z]{2}) via ([A-Z]{3})"
        )
        private const val TAG = "KuvaSwift"
        // patch 221: Iran-time stamping for swift.log (see appendLog).
        private val IRAN_ZONE = java.time.ZoneId.of("Asia/Tehran")
        private val IRAN_LOG_TIME = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
        /** WARP's own connectivity-check endpoint, used by tunnelProbe. */
        private const val PROBE_HOST = "cp.cloudflare.com"
        private const val LIB_NAME = "libaether.so"
        private const val PT_NAME = "libpt.so"
        private const val DIR_NAME = "swift"
        private const val LOG_NAME = "swift.log"
        private const val MAX_LOG_BYTES = 512L * 1024

        // poisoned-gateway burst detection (patch 216): two tunnel ends in
        // five minutes = the cached edge is being killed mid-session
        private const val GATE_BURST_LIMIT = 2
        private const val GATE_BURST_WINDOW_MS = 5L * 60_000L

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
