package com.kuva.vpn.core.discovery

import android.util.Log
import com.kuva.vpn.core.config.parser.ShareLinkParser
import com.kuva.vpn.domain.model.Profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Free-configuration discovery. Fetches from PUBLIC sources only via HTTPS,
 * using official/authorized endpoints. Each source is an adapter implementing
 * [FreeConfigSource]; new sources plug in without touching the pipeline.
 *
 * Guarantees:
 *  - failures are isolated per-source and never damage existing data
 *  - duplicates are removed by content fingerprint
 *  - only syntactically valid configs survive validation
 */
@Singleton
class ConfigDiscoveryEngine @Inject constructor(
    private val shareLinkParser: ShareLinkParser,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    data class SourceResult(
        val source: String,
        val found: Int,
        val valid: Int,
        val error: String?,
    )

    data class DiscoveryReport(
        val results: List<SourceResult>,
        val newProfiles: List<Profile>,
    )

    /**
     * Fetches ONE source and parses it LINE BY LINE so that:
     *  - the "found" counter updates LIVE (via [onFound])
     *  - Stop is responsive even in the middle of one source
     *  - collection stops exactly at [maxTotal] unique configs
     * Dedup against [existingFingerprints] and [into].
     */
    suspend fun discoverSource(
        source: FreeConfigSource,
        existingFingerprints: Set<String>,
        into: LinkedHashMap<String, Profile>,
        maxTotal: Int = 100,
        isCancelled: () -> Boolean = { false },
        onFound: (Int) -> Unit = {},
        minFromSource: Int = 0,   // per-source quota (skip the cap check)
    ): SourceResult = withContext(Dispatchers.IO) {
        try {
            if (isCancelled()) return@withContext SourceResult(source.name, 0, 0, null)
            // some sources HTML-escape & as &amp; — unescape before parsing,
            // otherwise reality pbk/sid params are lost (core rejects config)
            val raw = source.fetch(client).replace(Regex("&amp;", RegexOption.IGNORE_CASE), "&")
            var scanned = 0
            // perSource counter: quota mode ignores the global cap so small
            // sources still deliver their share before the cap kicks in later
            var perSource = 0
            for (line in raw.lineSequence()) {
                if (isCancelled()) break
                // Quota: keep scanning this source while it still owes
                // configs (perSource < minFromSource), but the global cap of
                // maxTotal is ALWAYS enforced so the total can never exceed
                // the budget. (Fix: the previous patch stopped each source
                // after 8 LINES — most quota was never delivered and the
                // search came back nearly empty.)
                if (into.size >= maxTotal) break
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                if (!trimmed.contains("://")) continue
                val parsed = runCatching { shareLinkParser.parse(trimmed) }.getOrDefault(emptyList())
                for (p0 in parsed) {
                    if (into.size >= maxTotal) break
                    val p = p0.copy(
                        scope = Profile.Scope.FREE,
                        source = source.name,
                        discoveredAt = System.currentTimeMillis(),
                        fingerprintHash = Profile.fingerprintOf(p0),
                        isFromSearch = true,
                    )
                    val fp = p.fingerprintHash ?: continue
                    if (existingFingerprints.contains(fp) || into.containsKey(fp)) continue
                    into[fp] = p
                    scanned++
                    perSource++
                    onFound(into.size)
                    // per-source quota: once this source has delivered its
                    // share of unique configs, stop scanning it and move on.
                    if (minFromSource > 0 && perSource >= minFromSource) break
                }
            }
            // fallback: nothing found line-by-line but text looks like JSON batch
            if (scanned == 0 && into.isEmpty() &&
                (raw.trimStart().startsWith("{") || raw.trimStart().startsWith("["))) {
                val parsed = runCatching { shareLinkParser.parse(raw) }.getOrDefault(emptyList())
                for (p0 in parsed) {
                    if (isCancelled() || into.size >= maxTotal) break
                    val p = p0.copy(
                        scope = Profile.Scope.FREE,
                        source = source.name,
                        discoveredAt = System.currentTimeMillis(),
                        fingerprintHash = Profile.fingerprintOf(p0),
                        isFromSearch = true,
                    )
                    val fp = p.fingerprintHash ?: continue
                    if (existingFingerprints.contains(fp) || into.containsKey(fp)) continue
                    into[fp] = p
                    scanned++
                    perSource++
                    onFound(into.size)
                }
            }
            SourceResult(source.name, scanned, scanned, null)
        } catch (e: Exception) {
            Log.w(TAG, "source ${source.name} failed: ${e.message}")
            SourceResult(source.name, 0, 0, e.message ?: "failed")
        }
    }

    suspend fun discover(
        sources: List<FreeConfigSource>,
        existingFingerprints: Set<String>,
        onProgress: (done: Int, total: Int, sourceName: String, found: Int) -> Unit = { _, _, _, _ -> },
    ): DiscoveryReport = withContext(Dispatchers.IO) {
        val allNew = LinkedHashMap<String, Profile>() // fingerprint -> profile (dedup)
        val results = mutableListOf<SourceResult>()

        sources.forEachIndexed { idx, source ->
            onProgress(idx, sources.size, source.name, allNew.size)
            val r = try {
                val raw = source.fetch(client)
                val profiles = shareLinkParser.parse(raw).map { p ->
                    p.copy(
                        scope = Profile.Scope.FREE,
                        source = source.name,
                        discoveredAt = System.currentTimeMillis(),
                        fingerprintHash = Profile.fingerprintOf(p),
                        isFromSearch = true,
                    )
                }
                // dedup within this batch and against existing repo
                val fresh = profiles.filter { p ->
                    p.fingerprintHash != null &&
                        !existingFingerprints.contains(p.fingerprintHash) &&
                        !allNew.containsKey(p.fingerprintHash)
                }
                fresh.forEach { allNew[it.fingerprintHash!!] = it }
                SourceResult(source.name, profiles.size, fresh.size, null)
            } catch (e: Exception) {
                Log.w(TAG, "source ${source.name} failed: ${e.message}")
                SourceResult(source.name, 0, 0, e.message ?: "failed")
            }
            results.add(r)
            onProgress(idx + 1, sources.size, source.name, allNew.size)
        }

        DiscoveryReport(results, allNew.values.toList())
    }

    companion object {
        private const val TAG = "KuvaDiscovery"
    }
}

/** Adapter contract — implement and register to add new public sources. */
interface FreeConfigSource {
    val name: String
    /** @return raw text possibly containing share links (plain, base64, html). */
    @Throws(Exception::class)
    suspend fun fetch(client: OkHttpClient): String
}

/** Built-in public source adapters (all user-removable via settings JSON). */
object DefaultSources {

    /** Raw file endpoints (GitHub raw, gist, any public URL serving config lists). */
    class RawUrl(
        override val name: String,
        private val url: String,
    ) : FreeConfigSource {
        override suspend fun fetch(client: OkHttpClient): String {
            val req = Request.Builder().url(url)
                .header("User-Agent", "KUVAVpn/1.0 (config discovery)")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                return resp.body?.string().orEmpty()
            }
        }
    }

    /**
     * GitHub: fetches file listings of a public repo via the official REST API
     * (public access, no bypassing). Optionally authenticated via token to
     * raise rate limits (user-supplied, stored encrypted).
     */
    class GitHubRepo(
        override val name: String,
        private val repo: String,          // "owner/repo"
        private val path: String,          // in-repo path to config files
        private val githubToken: String? = null,
    ) : FreeConfigSource {
        override suspend fun fetch(client: OkHttpClient): String {
            val api = "https://api.github.com/repos/$repo/contents/$path"
            val rb = Request.Builder().url(api)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "KUVAVpn/1.0")
            githubToken?.takeIf { it.isNotBlank() }?.let { rb.header("Authorization", "Bearer $it") }
            client.newCall(rb.build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("GitHub HTTP ${resp.code}")
                val body = resp.body?.string().orEmpty()
                // extract download_url fields
                val urls = Regex("\"download_url\"\\s*:\\s*\"([^\"]+)\"").findAll(body)
                    .map { it.groupValues[1].replace("\\u0026", "&") }
                    .toList()
                val sb = StringBuilder()
                urls.take(MAX_FILES).forEach { u ->
                    try {
                        client.newCall(Request.Builder().url(u).build()).execute().use { r ->
                            if (r.isSuccessful) sb.appendLine(r.body?.string().orEmpty())
                        }
                    } catch (_: Exception) { }
                }
                return sb.toString()
            }
        }
        companion object { private const val MAX_FILES = 10 }
    }

    /**
     * Public Telegram configuration feed via the official t.me web preview
     * (public channel posts are publicly viewable; no authentication bypass,
     * no private access). Extracts links posted in recent public posts.
     */
    class TelegramPublic(
        override val name: String,
        private val channel: String,       // public channel username (no @)
        private val postLimit: Int = 5,
    ) : FreeConfigSource {
        override suspend fun fetch(client: OkHttpClient): String {
            val sb = StringBuilder()
            // t.me/s/<channel> is the public HTML preview of recent posts
            val req = Request.Builder().url("https://t.me/s/$channel")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("t.me HTTP ${resp.code}")
                val html = resp.body?.string().orEmpty()
                // extract share links and base64 blocks from post text
                val linkPattern = Regex("""(?:vless|vmess|trojan|ss|socks5?)://[^\s<>"']+""")
                linkPattern.findAll(html).forEach { sb.appendLine(it.value) }
            }
            return sb.toString()
        }
    }

    /**
     * Subscription/custom URL. Accepts plain share-link lists AND base64
     * subscription bodies (decoded automatically, standard or URL-safe
     * alphabet, padded or not). Comment/header lines are ignored by the
     * share-link parser downstream.
     */
    class SubUrl(
        override val name: String,
        private val url: String,
    ) : FreeConfigSource {
        override suspend fun fetch(client: OkHttpClient): String {
            val req = Request.Builder().url(url.trim())
                .header("User-Agent", "KUVAVpn/1.0 (config discovery)")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val body = resp.body?.string().orEmpty().trim()
                // plain share-link list: pass through untouched
                if (body.contains("://")) return body
                // base64 subscription: try standard then URL-safe alphabets
                val cleaned = body.replace(Regex("\\s+"), "")
                val decoded = runCatching {
                    android.util.Base64.decode(cleaned, android.util.Base64.DEFAULT).toString(Charsets.UTF_8)
                }.recoverCatching {
                    android.util.Base64.decode(cleaned, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP).toString(Charsets.UTF_8)
                }.getOrDefault(body)
                return if (decoded.contains("://")) decoded else body
            }
        }
    }
}
