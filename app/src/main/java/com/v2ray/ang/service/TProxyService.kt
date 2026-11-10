package com.v2ray.ang.service

import android.util.Log

/**
 * JNI bridge for the prebuilt libhev-socks5-tunnel.so.
 *
 * IMPORTANT: the native library was compiled for v2rayNG with
 * -DPKGNAME=com/v2ray/ang/service and it registers its JNI methods on
 * EXACTLY this class name inside JNI_OnLoad. Renaming, moving or removing
 * this class crashes the whole app (SIGABRT / ClassNotFoundException) the
 * moment System.loadLibrary runs. Do not touch the package or class name.
 *
 * The four external functions must stay @JvmStatic inside the companion:
 * that is how Kotlin generates static native methods on the outer class,
 * matching what RegisterNatives expects.
 */
class TProxyService private constructor() {

    companion object {
        private const val TAG = "KuvaTun"

        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyStartService(configPath: String, fd: Int): Boolean

        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyStopService(): Boolean

        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyIsRunning(): Boolean

        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyGetStats(): LongArray?

        init {
            System.loadLibrary("hev-socks5-tunnel")
        }

        @JvmStatic
        fun start(configPath: String, fd: Int): Boolean = try {
            TProxyStartService(configPath, fd)
        } catch (t: Throwable) {
            // UnsatisfiedLinkError and friends must never escape to the caller
            Log.e(TAG, "tproxy start failed", t)
            false
        }

        @JvmStatic
        fun stop(): Boolean = try {
            TProxyStopService()
        } catch (t: Throwable) {
            Log.w(TAG, "tproxy stop: ${t.message}")
            false
        }

        @JvmStatic
        fun isRunning(): Boolean = try {
            TProxyIsRunning()
        } catch (_: Throwable) {
            false
        }

        /**
         * Real cumulative byte counters from the native tunnel:
         * LongArray [uplinkBytes, downlinkBytes] since tunnel start, or null.
         */
        @JvmStatic
        fun getStats(): LongArray? = try {
            TProxyGetStats()
        } catch (_: Throwable) {
            null
        }
    }
}
