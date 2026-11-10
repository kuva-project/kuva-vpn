package com.kuva.vpn.core.diagnostics

import android.util.Log
import com.kuva.vpn.core.config.XrayConfigBuilder
import com.kuva.vpn.core.engine.XrayCoreManager
import com.kuva.vpn.domain.model.Profile
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Real connectivity measurements for configurations.
 *
 * Ping  = TCP connect latency to server:port (true network RTT to the endpoint)
 *         plus, when the core is available, a full proxy round-trip via
 *         MeasureOutboundDelay. TCP fallback keeps testing usable on any device.
 * Speed = size-limited HTTP download/upload THROUGH a temporary core instance
 *         for that specific config.
 */
@Singleton
class ConfigTester @Inject constructor(
    private val xray: XrayCoreManager,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) {
    // Relay pings are serialized: every temp core binds the SAME test SOCKS
    // port, so concurrent measurements would fight over the bind and most
    // would falsely report -1.
    private val pingMutex = Mutex()
    /** Test one profile's latency. @return ms, or -1 if unreachable. */
    suspend fun testPing(profile: Profile): Int = withContext(Dispatchers.IO) {
        pingMutex.withLock {
            // Same measurement the stock clients (v2rayNG, V2Box) use: the
            // core's own MeasureOutboundDelay — a lightweight IN-PROCESS fetch
            // of the test URL through the outbound (TCP + proxy handshake +
            // relay). No temp-core lifecycle and no extra SOCKS hop, so the
            // numbers land in v2rayNG's 100-300 ms range instead of 1000+.
            // Still SERIALIZED: parallel batches shared one test port and the
            // native call answered bind failures with a fake ~7s "delay" —
            // that fake value is why dead configs showed ~7000 ms. The mutex
            // removes the bind race; the sanity window below removes the
            // poison value itself.
            val cfg = XrayConfigBuilder.buildTestConfig(profile, TEST_SOCKS_PORT)
            val ms = xray.measureOutboundDelay(cfg, XrayCoreManager.TEST_URL).toInt()
            // A healthy relay answers well under 5 s; anything above is one
            // of the native call's timeout artifacts -> treat as dead (-1),
            // exactly what v2rayNG shows as "timeout".
            if (ms in 1..4999) ms else -1
        }
    }

    /**
     * Batch ping, TWO STAGES like v2rayNG, so a 100-config list finishes in
     * seconds instead of minutes:
     *  1) raw TCP reachability — fully parallel socket connects with a 1.5 s
     *     cap; every dead server fails fast instead of burning the core's
     *     multi-second timeout each;
     *  2) real relay delay through the core — SERIALIZED (the native call
     *     shares one test port), but only for the configs that passed
     *     stage 1, and each costs ~100-300 ms.
     */
    suspend fun testPingBatch(
        profiles: List<Profile>,
        concurrency: Int = 16,
        shouldCancel: (() -> Boolean)? = null,
        onResult: suspend (Long, Int) -> Unit,
    ) = coroutineScope {
        // native core must be initialized before measureOutboundDelay can work
        runCatching { xray.initCoreEnv(appContext) }
        val verdicts = profiles.map { p ->
            async(Dispatchers.IO) { p.id to tcpAlive(p) }
        }.awaitAll()
        verdicts.filter { !it.second }.forEach { onResult(it.first, -1) }
        // Stage 2 — relay delay through the core, 12 workers in PARALLEL.
        // Each worker builds its config on a DISTINCT test port (10812..10823):
        // sharing one port was exactly why parallel relay pings used to return
        // bind-race garbage, and going serial instead made a 100-config list
        // take minutes because every dead relay burned the native ~5s timeout.
        // The withTimeoutOrNull(2s) race cap bounds the worst case regardless
        // of the native timeout (a healthy relay answers well under 1 s, so
        // 2 s still leaves a wide margin); a leaked native call dies on its
        // own shortly after and the port is free again by the next round.
        val alive = profiles.filter { p -> verdicts.any { it.first == p.id && it.second } }
        alive.chunked(12).forEach { chunk ->
            // cooperative cancellation between chunks: stopped ping leaves
            // the not-yet-tested rows untested (pingMs stays null)
            if (shouldCancel?.invoke() == true) return@forEach
            chunk.mapIndexed { i, p ->
                async(Dispatchers.IO) {
                    // port = slot index: guaranteed distinct within the chunk
                    val port = TEST_SOCKS_PORT + i
                    onResult(p.id, relayPingBounded(p, port))
                }
            }.awaitAll()
        }
    }

    /** Relay delay with a hard 2s cap. @return ms, or -1 if unreachable. */
    private suspend fun relayPingBounded(p: Profile, port: Int): Int {
        val ms = kotlinx.coroutines.withTimeoutOrNull(2000) {
            val cfg = XrayConfigBuilder.buildTestConfig(p, port)
            xray.measureOutboundDelay(cfg, XrayCoreManager.TEST_URL).toInt()
        } ?: return -1
        return if (ms in 1..1999) ms else -1
    }

    /** Raw TCP connect probe: is the server port open within 1.2 s? */
    private fun tcpAlive(p: Profile): Boolean = runCatching {
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress(p.address, p.port), 1200)
            true
        }
    }.getOrDefault(false)

    /**
     * Bandwidth-conscious speed test THROUGH a temporary core for this config.
     * Downloads up to limitMB (default 10 MB), measures achieved rate.
     */
    /** Ensure native core env exists before proxy speed tests. */
    suspend fun initSpeedEnv() = withContext(Dispatchers.IO) {
        runCatching { xray.initCoreEnv(appContext) }
    }

    /**
     * Real throughput test THROUGH the proxy: starts a temporary Xray core
     * (closed loop controller, no TUN needed), then downloads/uploads via its
     * SOCKS port. @return (downKBs, upKBs); 0.0 means the proxy didn't pass.
     */
    suspend fun testSpeed(profile: Profile, limitMB: Int = 10): Pair<Double, Double> =
        withContext(Dispatchers.IO) {
            runCatching { xray.initCoreEnv(appContext) }
            val cfg = XrayConfigBuilder.buildTestConfig(profile, TEST_SOCKS_PORT)

            val handler = object : com.kuva.vpn.core.engine.KuvaCoreCallback() {}
            val controller = xray.newController(handler)
            // startLoop BLOCKS while the core runs (same as the VPN service) —
            // it must run on its own coroutine; here we wait until the SOCKS
            // inbound accepts connections, and stopLoop in finally unblocks it.
            var started = false
            val testScope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
            try {
                testScope.launch { runCatching { controller.startLoop(cfg, -1) } }
                val deadline = System.currentTimeMillis() + 10_000
                while (System.currentTimeMillis() < deadline) {
                    started = try {
                        java.net.Socket().use {
                            it.connect(java.net.InetSocketAddress("127.0.0.1", TEST_SOCKS_PORT), 300)
                        }
                        true
                    } catch (_: Exception) {
                        kotlinx.coroutines.delay(150)
                        false
                    }
                    if (started) break
                }
            } catch (e: Exception) {
                Log.w(TAG, "temp core start failed: ${e.message}")
            }
            if (!started) {
                runCatching { controller.stopLoop() }
                testScope.cancel()
                return@withContext Pair(0.0, 0.0)
            }

            val client = OkHttpClient.Builder()
                .proxy(java.net.Proxy(java.net.Proxy.Type.SOCKS,
                    java.net.InetSocketAddress("127.0.0.1", TEST_SOCKS_PORT)))
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()

            var downKbps = 0.0
            var upKbps = 0.0

            // ---- download ----
            try {
                val start = System.nanoTime()
                var bytes = 0L
                val req = Request.Builder()
                    .url("https://speed.cloudflare.com/__down?bytes=${limitMB * 1_000_000}")
                    .build()
                client.newCall(req).execute().use { resp ->
                    val src = resp.body?.byteStream() ?: return@use
                    val buf = ByteArray(64 * 1024)
                    while (bytes < limitMB * 1_000_000L) {
                        // a mid-test stream reset must not discard the bytes
                        // already downloaded (the full 10 MB often never arrives)
                        val n = try { src.read(buf) } catch (_: Exception) { -1 }
                        if (n < 0) break
                        bytes += n
                    }
                }
                val elapsedMs = (System.nanoTime() - start) / 1_000_000
                if (elapsedMs > 200 && bytes > 0) {
                    downKbps = bytes * 8.0 / elapsedMs // kbit/s
                }
            } catch (e: Exception) {
                Log.d(TAG, "speed down test: ${e.message}")
            }

            // ---- upload (smaller cap: 1 MB) ----
            try {
                val payload = ByteArray(1_000_000)
                val start = System.nanoTime()
                val req = Request.Builder()
                    .url("https://speed.cloudflare.com/__up")
                    .post(payload.toRequestBody("application/octet-stream".toMediaType()))
                    .build()
                client.newCall(req).execute().use { }
                val elapsedMs = (System.nanoTime() - start) / 1_000_000
                if (elapsedMs > 200) {
                    upKbps = payload.size * 8.0 / elapsedMs
                }
            } catch (e: Exception) {
                Log.d(TAG, "speed up test: ${e.message}")
            }

            runCatching { controller.stopLoop() }
            testScope.cancel()
            Pair(downKbps / 8.0, upKbps / 8.0) // convert to KB/s
        }

    private fun tcpLatency(host: String, port: Int, timeoutMs: Int): Int {
        return try {
            val start = System.nanoTime()
            java.net.Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
            }
            ((System.nanoTime() - start) / 1_000_000).toInt()
        } catch (_: Exception) {
            -1
        }
    }

    companion object {
        private const val TAG = "KuvaTester"
        // 10808/10809 are reserved for the live VPN core (socks/http); the temp
// speed-test core MUST use a different port or its inbound can't bind.
private const val TEST_SOCKS_PORT = 10812
    }
}
