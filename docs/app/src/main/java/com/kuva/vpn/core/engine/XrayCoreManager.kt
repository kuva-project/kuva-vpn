package com.kuva.vpn.core.engine

import android.content.Context
import android.util.Log
import go.Seq
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thread-safe wrapper around the AndroidLibXrayLite (libv2ray) native bindings.
 * All Xray-core lifecycle and measurement goes through here.
 */
@Singleton
class XrayCoreManager @Inject constructor() {

    private val initialized = AtomicBoolean(false)

    /** Copy geoip.dat / geosite.dat from assets into filesDir on first init. */
    fun initCoreEnv(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        try {
            Seq.setContext(context.applicationContext)
            val assetPath = context.filesDir.absolutePath
            // geo databases: copy bundled copies (gzipped assets) into the
            // core asset dir when missing. Downloaded updates (GeoUpdater)
            // already live here and are left untouched.
            if (listOf("geoip.dat", "geosite.dat")
                    .any { !java.io.File(assetPath, it).exists() }
            ) {
                GeoUpdater().extractBundled(context)
            }
            // The key becomes the xray.xudp.basekey env var; the core validates it as
            // base64 (RawURL, unpadded) decoding to EXACTLY 32 bytes — a UUID fails with
            // "BaseKey must be 32 bytes" and kills core init. Generate 32 random bytes,
            // encode them to 43-char unpadded base64, and keep one per process.
            Libv2ray.initCoreEnv(assetPath, xudpBaseKey())
            Log.i(TAG, "Xray core env initialized: ${Libv2ray.checkVersionX()}")
        } catch (e: Exception) {
            initialized.set(false)
            Log.e(TAG, "initCoreEnv failed", e)
            throw e
        }
    }

    fun coreVersion(): String = try {
        // "Lib vN, Xray-core vX" -> extract Xray version
        Libv2ray.checkVersionX().substringAfter("Xray-core ", "unknown")
    } catch (_: Exception) { "unknown" }

    /** Real latency: spins a temp core with this outbound and fetches url through it. ms or -1. */
    fun measureOutboundDelay(configJson: String, testUrl: String): Long = try {
        Libv2ray.measureOutboundDelay(configJson, testUrl)
    } catch (e: Exception) {
        Log.w(TAG, "measureOutboundDelay failed: ${e.message}")
        -1L
    }

    fun newController(handler: CoreCallbackHandler): CoreController =
        Libv2ray.newCoreController(handler)

    companion object {
        private const val TAG = "KuvaXray"
        const val TEST_URL = "https://www.gstatic.com/generate_204"

        @Volatile
        private var cachedBaseKey: String? = null

        /**
         * 32 random bytes, base64url-encoded WITHOUT padding (43 chars).
         * Exactly what xray.xudp.basekey expects; stable for this process.
         */
        @Synchronized
        private fun xudpBaseKey(): String {
            cachedBaseKey?.let { return it }
            val bytes = ByteArray(32)
            java.security.SecureRandom().nextBytes(bytes)
            val key = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            cachedBaseKey = key
            return key
        }
    }
}

/** Callback bridge from the Go core to the VPN service. */
abstract class KuvaCoreCallback : CoreCallbackHandler {
    // Signatures must match the libv2ray CoreCallbackHandler interface exactly:
    // lowercase names with Long parameters, as used in KuvaVpnService.
    override fun startup(): Long = 0
    override fun shutdown(): Long = 0
    override fun onEmitStatus(p0: Long, p1: String?): Long = 0
}
