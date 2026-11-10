package com.kuva.vpn.core.engine

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bundled + updatable geo databases (geoip.dat / geosite.dat) used by the core
 * for routing rules like "geosite:ir -> direct".
 *
 * Bundled copies ship gzipped in assets (geoip.dat.gz / geosite.dat.gz) from
 * Loyalsoldier/v2ray-rules-dat — the same source the v2rayNG community uses as
 * the standard replacement for the stock v2fly files. The user can pull a
 * newer release at any time (GitHub API, sha256 verified, atomic install) and
 * the new databases take effect on the next core start.
 */
@Singleton
class GeoUpdater @Inject constructor() {

    companion object {
        const val RELEASE_URL =
            "https://api.github.com/repos/Loyalsoldier/v2ray-rules-dat/releases/latest"
        private const val TAG = "GeoUpdater"
        private val FILE_NAMES = listOf("geoip.dat", "geosite.dat")

        /** Marker written after a successful download-based update. */
        fun markerFile(ctx: Context): File = File(ctx.filesDir, "geo_asset_version.txt")
    }

    data class Info(val geoip: String, val geosite: String, val source: String) {
        val isEmpty: Boolean get() = geoip.isBlank() && geosite.isBlank()
    }

    fun info(ctx: Context): Info = Info(
        parseDatVersion(File(ctx.filesDir, "geoip.dat")),
        parseDatVersion(File(ctx.filesDir, "geosite.dat")),
        markerFile(ctx).takeIf { it.exists() }?.readText()?.trim().orEmpty()
    )

    /**
     * Best-effort fingerprint of a compiled geo .dat: either the legacy JSON
     * {"version": ...} header or a well-known category name plus file size.
     * Byte-level scan only — never materializes the whole file as a String.
     */
    private fun parseDatVersion(f: File): String = runCatching {
        val bytes = f.readBytes()
        val pat = "\"version\"".toByteArray()
        var found = -1
        outer@ for (i in 0..bytes.size - pat.size) {
            for (j in pat.indices) {
                if (bytes[i + j] != pat[j]) continue@outer
            }
            found = i
            break
        }
        if (found >= 0) {
            val region = String(bytes, found, minOf(64, bytes.size - found), Charsets.UTF_8)
            Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(region)?.groupValues?.get(1)
                ?.let { return it }
        }
        var i = 0
        while (i < bytes.size - 6) {
            if (bytes[i] == 0x0a.toByte()) {
                val ln = bytes[i + 1].toInt() and 0xFF
                if (ln in 3..64) {
                    val nm = String(bytes, i + 2, ln, Charsets.ISO_8859_1)
                    if (nm.all { it.code in 32..126 } &&
                        (nm == "CATEGORY-IR" || nm == "CATEGORY-ADS-ALL" || nm == "GOOGLE")
                    ) {
                        return "$nm (${f.length() / 1024 / 1024}MB)"
                    }
                }
            }
            i++
        }
        ""
    }.getOrDefault("")

    /** Extract the bundled geo assets (gzipped or raw) into filesDir. */
    fun extractBundled(ctx: Context) {
        FILE_NAMES.forEach { name ->
            val f = File(ctx.filesDir, name)
            if (f.exists()) return@forEach  // keep downloaded updates
            val gz = "$name.gz"
            val assetName = if (runCatching {
                    ctx.assets.list("").orEmpty().contains(gz)
                }.getOrDefault(false)) gz else name
            ctx.assets.open(assetName).use { input ->
                if (assetName.endsWith(".gz")) {
                    java.util.zip.GZIPInputStream(input).use { gunzip ->
                        f.outputStream().use { output -> gunzip.copyTo(output) }
                    }
                } else {
                    f.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    /**
     * Validate and install one user-picked geo file (from a file picker).
     * Accepts raw or gzipped .dat protobuf; returns the installed file.
     */
    fun handleDownloaded(ctx: Context, uri: Uri, name: String, outDir: File): File {
        val raw = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("Cannot read selected file")
        val bytes = if (raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) {
            java.util.zip.GZIPInputStream(raw.inputStream()).readBytes()
        } else raw
        if (bytes.size < 100_000) throw IllegalArgumentException("File too small to be a geo dat")
        if (bytes[0] != 0x0a.toByte()) throw IllegalArgumentException("Not a geo dat file")
        val out = File(outDir, name)
        val tmp = File(outDir, "$name.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(out)) {
            out.writeBytes(tmp.readBytes())
            tmp.delete()
        }
        return out
    }

    /** Download the latest release from the source repo; atomic + sha256 checked. */
    suspend fun update(ctx: Context, onProgress: (String) -> Unit): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                onProgress("Checking…")
                val headers = mapOf(
                    "User-Agent" to "KUVAVpn/1.0 (geo update)",
                    "Accept" to "application/vnd.github+json",
                )
                val meta = JSONObject(httpGet(RELEASE_URL, headers))
                val tag = meta.getString("tag_name")
                val assets = meta.getJSONArray("assets")
                val urls = HashMap<String, String>()
                val sums = HashMap<String, String>()
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    val n = a.getString("name")
                    when {
                        n in FILE_NAMES -> urls[n] = a.getString("browser_download_url")
                        n.endsWith(".sha256sum") && n.dropLast(10) in FILE_NAMES ->
                            sums[n.dropLast(10)] =
                                httpGet(a.getString("browser_download_url"), headers)
                                    .trim().split(Regex("\\s+"))[0]
                    }
                }
                if (urls.keys != FILE_NAMES.toSet())
                    throw IllegalStateException("Release is missing geo assets")

                val dir = File(ctx.filesDir, "geo_update").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                for ((name, url) in urls) {
                    onProgress("Downloading $name…")
                    val bytes = httpGetBytes(url, headers)
                    val want = sums[name]?.lowercase()
                    if (!want.isNullOrBlank()) {
                        val got = MessageDigest.getInstance("SHA-256").digest(bytes)
                            .joinToString("") { "%02x".format(it) }
                        if (got != want) throw IllegalStateException("Checksum mismatch: $name")
                    }
                    if (bytes.size < 100_000 || bytes[0] != 0x0a.toByte())
                        throw IllegalStateException("Invalid download: $name")
                    File(dir, name).writeBytes(bytes)
                }
                onProgress("Installing…")
                for (name in FILE_NAMES) {
                    val dst = File(ctx.filesDir, name)
                    val tmp = File(ctx.filesDir, "$name.tmp")
                    tmp.writeBytes(File(dir, name).readBytes())
                    if (!tmp.renameTo(dst)) {
                        dst.writeBytes(tmp.readBytes())
                        tmp.delete()
                    }
                }
                dir.deleteRecursively()
                markerFile(ctx).writeText("Loyalsoldier/v2ray-rules-dat $tag")
                Log.i(TAG, "geo databases updated to $tag")
                tag
            }
        }

    /** Remove downloaded databases, then restore the bundled ones immediately. */
    fun resetToBundled(ctx: Context) {
        FILE_NAMES.forEach { File(ctx.filesDir, it).delete() }
        markerFile(ctx).delete()
        File(ctx.filesDir, "geo_update").deleteRecursively()
        extractBundled(ctx)
    }

    // HttpURLConnection is NOT Closeable in Java, so kotlin's .use{} does not
    // resolve on it — disconnect() in finally instead.
    private fun httpGet(url: String, headers: Map<String, String>): String {
        val c = http(url, headers)
        try {
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    private fun httpGetBytes(url: String, headers: Map<String, String>): ByteArray {
        val c = http(url, headers)
        try {
            return c.inputStream.readBytes()
        } finally {
            c.disconnect()
        }
    }

    private fun http(url: String, headers: Map<String, String>): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            connectTimeout = 20_000
            readTimeout = 120_000
            instanceFollowRedirects = true
        }
}
