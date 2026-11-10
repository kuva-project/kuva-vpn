package com.kuva.vpn.util

import java.util.Locale

object Formatters {
    fun speed(bytesPerSec: Long): String = bytes(bytesPerSec) + "/s"

    fun bytes(b: Long): String = when {
        b >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", b.toDouble() / (1L shl 30))
        b >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", b.toDouble() / (1L shl 20))
        b >= 1L shl 10 -> String.format(Locale.US, "%.1f KB", b.toDouble() / (1L shl 10))
        else -> "$b B"
    }

    fun duration(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%02d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%02d:%02d", m, sec)
    }

    fun ping(ms: Int?): String = when {
        ms == null -> "—"
        ms < 0 -> "timeout"
        else -> "$ms ms"
    }
}
