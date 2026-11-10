package com.kuva.vpn.core.engine

import android.content.Context
import java.io.File

/**
 * Persists messages the Xray core emits through CoreCallbackHandler.onEmitStatus
 * so they survive past the session and can be shared from Settings. Without this
 * the handler dropped every message (= 0) and core-side connection failures were
 * invisible to debugging.
 */
object CoreLogStore {
    private const val MAX_BYTES = 512L * 1024

    fun append(context: Context, line: String) {
        runCatching {
            val dir = File(context.cacheDir, "logs").apply { mkdirs() }
            val f = File(dir, "xray.log")
            if (f.length() > MAX_BYTES) f.delete()
            f.appendText(System.currentTimeMillis().toString() + " " + line + "\n")
        }
    }

    /**
     * Combine every log source into one FileProvider-visible snapshot:
     * onEmitStatus messages (status only), plus the core's own access and
     * error logs (the real per-connection lines: dial failures, rejections).
     * Returns null when there is nothing at all to share.
     */
    fun snapshotFile(context: Context): File? {
        val status = runCatching {
            File(File(context.cacheDir, "logs"), "xray.log")
                .takeIf { it.exists() }?.readText().orEmpty()
        }.getOrDefault("")
        val coreDir = File(context.filesDir, "logs")
        val errorLog = runCatching {
            File(coreDir, "xray_error.log").takeIf { it.exists() }?.readText().orEmpty()
        }.getOrDefault("")
        val accessLog = runCatching {
            File(coreDir, "xray_access.log").takeIf { it.exists() }?.readText().orEmpty()
        }.getOrDefault("")
        if (status.isBlank() && errorLog.isBlank() && accessLog.isBlank()) return null
        val out = File(File(context.cacheDir, "exports").apply { mkdirs() }, "kuva_xray_log.txt")
        return runCatching {
            out.writeText(buildString {
                appendLine("=== core status (onEmitStatus) ===")
                appendLine(status)
                appendLine("=== xray error log ===")
                appendLine(errorLog)
                appendLine("=== xray access log ===")
                appendLine(accessLog)
            })
            out
        }.getOrNull()
    }
}
