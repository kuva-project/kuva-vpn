package com.kuva.vpn.core.tunnel

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File

/**
 * Thin Kotlin wrapper around the JNI bridge class com.v2ray.ang.service.TProxyService.
 *
 * The prebuilt libhev-socks5-tunnel.so was compiled for v2rayNG and registers
 * its native methods on that exact class, so all load/native work happens
 * there; this object only builds the YAML config and forwards the calls.
 * See TProxyService.kt before renaming anything.
 */
object Tun2Socks {

    /**
     * Probes whether the native library can actually load. Does the work of
     * System.loadLibrary exactly once; false means the .so is missing or its
     * JNI class could not be found — in that case the VPN service must not
     * try to start the tunnel and should surface an error to the user.
     */
    @Volatile
    private var libAvailable: Boolean? = null

    fun isNativeAvailable(): Boolean {
        val cached = libAvailable
        if (cached != null) return cached
        libAvailable = try {
            Class.forName("com.v2ray.ang.service.TProxyService")
            true
        } catch (t: Throwable) {
            Log.e("KuvaTun", "hev native bridge missing", t)
            false
        }
        return libAvailable!!
    }

    fun start(
        context: Context,
        vpnInterface: ParcelFileDescriptor,
        mtu: Int,
        ipv4: String,
        ipv6: String?,
        socksPort: Int
    ) {
        val conf = buildString {
            appendLine("tunnel:")
            appendLine("  mtu: $mtu")
            appendLine("  ipv4: $ipv4")
            if (!ipv6.isNullOrEmpty()) appendLine("  ipv6: '$ipv6'")
            appendLine("socks5:")
            appendLine("  port: $socksPort")
            appendLine("  address: 127.0.0.1")
            appendLine("  udp: 'udp'")
            appendLine("misc:")
            appendLine("  tcp-read-write-timeout: 300000")
            appendLine("  udp-read-write-timeout: 60000")
            appendLine("  log-level: warn")
        }
        val f = File(context.filesDir, "hev-tunnel.yaml")
        f.writeText(conf)
        try {
            val ok = com.v2ray.ang.service.TProxyService.start(f.absolutePath, vpnInterface.fd)
            if (ok) {
                Log.i("KuvaTun", "hev tunnel started on fd ${vpnInterface.fd}")
            } else {
                Log.e("KuvaTun", "hev tunnel refused to start")
            }
        } catch (t: Throwable) {
            // covers NoClassDefFoundError if the bridge class itself is absent
            Log.e("KuvaTun", "hev tunnel start failed", t)
        }
    }

    fun stop() {
        try {
            com.v2ray.ang.service.TProxyService.stop()
        } catch (t: Throwable) {
            Log.w("KuvaTun", "hev tunnel stop: ${t.message}")
        }
    }

    fun isRunning(): Boolean = try {
        com.v2ray.ang.service.TProxyService.isRunning()
    } catch (_: Throwable) {
        false
    }

    /**
     * Real cumulative byte counters from the native tunnel:
     * Pair(uplinkBytes, downlinkBytes) since tunnel start, or null.
     */
    fun stats(): Pair<Long, Long>? = try {
        com.v2ray.ang.service.TProxyService.getStats()?.let { a ->
            if (a.size >= 2) Pair(a[0], a[1]) else null
        }
    } catch (_: Throwable) {
        null
    }
}
