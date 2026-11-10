package com.kuva.vpn.domain.usecase

import android.util.Log
import com.kuva.vpn.core.config.parser.SubscriptionParser
import com.kuva.vpn.data.crypto.FieldCrypto
import com.kuva.vpn.data.db.SubscriptionDao
import com.kuva.vpn.data.db.SubscriptionEntity
import com.kuva.vpn.data.repository.ProfileRepositoryImpl
import com.kuva.vpn.domain.model.Profile
import com.kuva.vpn.domain.repository.ProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Personal subscription management. HARD ISOLATION: this class only ever
 * writes rows with scope=PERSONAL and only deletes profiles whose
 * subscriptionId matches the refreshed subscription. Manual personal configs
 * (subscriptionId == null) are never touched by any refresh.
 */
@Singleton
class SubscriptionUseCase @Inject constructor(
    private val subDao: SubscriptionDao,
    private val profileRepo: ProfileRepository,
    private val parser: SubscriptionParser,
    private val crypto: FieldCrypto,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun observeAll(): Flow<List<SubscriptionEntity>> = subDao.observeAll()

    suspend fun add(name: String, url: String): Long =
        subDao.insert(
            SubscriptionEntity(
                name = name.ifBlank { "Subscription" },
                urlEnc = crypto.encrypt(url) ?: "",
            )
        ).also { refresh(it) }

    /** Decrypted subscription URL (for QR / share). */
    fun urlOf(sub: com.kuva.vpn.data.db.SubscriptionEntity): String? =
        crypto.decrypt(sub.urlEnc)

    @Deprecated("undo-aware delete lives in FreeConfigUseCase.deleteSubWithUndo")
    suspend fun delete(id: Long) {
        // remove configs owned by this subscription, then the subscription
        val subs = subDao.getAll()
        // delete owned profiles by iterating personal scope (the connected
        // config is deselected first if it belongs to this sub — delete()
        // would otherwise refuse to remove it and leave an orphan row)
        val owned = profileRepo.all(Profile.Scope.PERSONAL).filter { it.subscriptionId == id }
        val selectedId = profileRepo.getSelected()?.id
        if (selectedId != null && owned.any { it.id == selectedId }) {
            profileRepo.select(null)
        }
        owned.forEach { profileRepo.delete(it.id, Profile.Scope.PERSONAL) }
        subDao.delete(id)
    }

    /**
     * Fetch + parse + merge one subscription.
     * - manual personal configs preserved (subscriptionId == null untouched)
     * - FULL REPLACE: previous rows of THIS subscription are all deleted and
     *   the fresh list is inserted as-is (user preference: no survivors)
     * - sub URL and metadata stored encrypted
     */
    /** Pin / unpin a subscription (pinned subs sort to the top). */
    suspend fun setPinned(id: Long, pinned: Boolean) = withContext(Dispatchers.IO) {
        subDao.getById(id)?.let { subDao.update(it.copy(pinned = pinned)) }
    }

    /** Manual rename from the Edit Sub dialog (may be overwritten by the next refresh). */
    suspend fun rename(id: Long, name: String) = withContext(Dispatchers.IO) {
        val clean = name.trim().take(60)
        if (clean.isNotEmpty()) subDao.getById(id)?.let { subDao.update(it.copy(name = clean)) }
    }

    /**
     * Refresh with an edited URL: replace the row first (without touching
     * traffic/expiry metadata) so the fetch below uses the NEW link.
     */
    suspend fun refresh(id: Long, newUrl: String? = null): Result<Int> {
        if (newUrl != null && newUrl.startsWith("http")) {
            withContext(Dispatchers.IO) {
                subDao.getById(id)?.let { subDao.update(it.copy(urlEnc = crypto.encrypt(newUrl) ?: it.urlEnc)) }
            }
        }
        return refresh(id)
    }

    suspend fun refresh(id: Long): Result<Int> = withContext(Dispatchers.IO) {
        val sub = subDao.getById(id) ?: return@withContext Result.failure(Exception("not found"))
        val url = crypto.decrypt(sub.urlEnc) ?: return@withContext Result.failure(Exception("decrypt failed"))
        try {
            val resp = client.newCall(
                Request.Builder().url(url)
                    .header("User-Agent", "KUVAVpn/1.0")
                    .build()
            ).execute()
            resp.use { r ->
                if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
                val body = r.body?.string().orEmpty()
                // v2box-style subtitle extraction: the panel's subscription
                // title travels in the "profile-title" header (Sanaei/Marzban
                // panels send it); some panels only set Content-Disposition.
                val cd = r.header("content-disposition")
                val remoteTitle = r.header("profile-title") ?: cd?.let {
                    // filename*=UTF-8''... (RFC 5987, used for Persian titles)
                    Regex("filename\\*\\s*=\\s*[^']*''([^;]+)", RegexOption.IGNORE_CASE)
                        .find(it)?.groupValues?.get(1)
                        // plain filename="..." / filename=...
                        ?: Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
                            .find(it)?.groupValues?.get(1)
                }
                // Sanaei/x-ui panels send the title as "base64:<b64>" — v2box
                // decodes this prefix; do the same (only accept clean text)
                val decodedTitle = remoteTitle?.let { rt ->
                    if (rt.startsWith("base64:", ignoreCase = true)) {
                        runCatching {
                            String(
                                android.util.Base64.decode(
                                    rt.substring(7).trim(),
                                    android.util.Base64.DEFAULT,
                                )
                            )
                        }.getOrNull()
                            ?.takeIf { dec -> dec.isNotBlank() && dec.none { it.isISOControl() } }
                            ?: rt
                    } else rt
                }
                // strip quotes/BOM, decode %xx (panels percent-encode Persian titles)
                val title = decodedTitle
                    ?.trim()?.trim('"')?.trim('\uFEFF')
                    ?.takeIf { it.isNotBlank() }
                    ?.let { raw ->
                        runCatching {
                            java.net.URLDecoder.decode(raw.replace("+", "%20"), "UTF-8")
                        }.getOrDefault(raw)
                    }?.trim()?.takeIf { it.isNotBlank() }
                // provider title wins on every refresh (v2box behavior);
                // manual renames are overwritten by the next refresh
                if (!title.isNullOrBlank()) {
                    val cur = subDao.getById(id)
                    if (cur != null && cur.name != title.take(60)) {
                        subDao.update(cur.copy(name = title.take(60)))
                    }
                }
                val info = parser.parseUserInfo(r.header("subscription-userinfo"))
                // re-read: the name may have just been set from profile-title
                val subName = subDao.getById(id)?.name ?: sub.name
                val profiles = parser.parseBody(body).map { p ->
                    p.copy(
                        scope = Profile.Scope.PERSONAL,
                        name = p.name.trim().ifEmpty { "${p.protocol.name.lowercase()}-${p.address}:${p.port}" },
                        subscriptionId = id,
                        source = "sub:${subName}",
                        fingerprintHash = Profile.fingerprintOf(p),
                    )
                }
                if (profiles.isEmpty()) throw java.io.IOException("no valid configs in body")

                // full replace: remove ALL previous rows of this subscription.
                // The SELECTED (connected) config survives the wipe — no matter
                // which sub/source it belongs to — keeping its ping/speed data.
                // Fresh rows matching its ENDPOINT are skipped below, so a twin
                // of the connected config never enters the list.
                val owned = profileRepo.all(Profile.Scope.PERSONAL)
                    .filter { it.subscriptionId == id }
                val survivor = profileRepo.getSelected()
                owned.filter { it.id != survivor?.id }
                    .forEach { profileRepo.delete(it.id, Profile.Scope.PERSONAL) }

                // insert the fresh list; a fresh copy of the surviving
                // connected config (same fingerprint) is NOT re-added
                var added = 0
                profiles.forEach { p ->
                    if (survivor != null && sameEndpoint(p, survivor)) {
                        return@forEach
                    }
                    repo_upsert(p)
                    added++
                }

                subDao.markUpdated(
                    id = id,
                    ts = System.currentTimeMillis(),
                    count = profiles.size,
                    up = info?.upload, down = info?.download,
                    total = info?.total, expire = info?.expire,
                )
                Result.success(added)
            }
        } catch (e: Exception) {
            Log.w("KuvaSub", "refresh failed: ${e.message}")
            subDao.markError(id, e.message ?: "failed")
            Result.failure(e)
        }
    }

    /**
     * Twin detection WITHOUT the unstable cosmetics. Panels mutate, on
     * every refresh: the random "junk" inside the ws path, the case of
     * sni/host, and (BPB) the Cloudflare A-record IPs of the IPv4 rows.
     * fingerprintHash includes all of that, so it never matches the
     * surviving connected config and duplicate rows appeared.
     *
     * Core identity = protocol/port/uuid/network/security/sni/host/method.
     * BUT the BPB panel gives ALL 24 rows the SAME uuid, and 4 sibling
     * rows share one port+sni — so core identity alone would skip ALL
     * siblings of the survivor (3 real configs vanished). The stable
     * per-row discriminator is the ADDRESS (domain rows keep their
     * fronting domain; the two IPv4 rows swap between the same pair of
     * Cloudflare IPs, so one of them always matches) with the exact row
     * NAME as a fallback for panels that rotate IPs entirely.
     */
    private fun sameEndpoint(a: Profile, b: Profile): Boolean =
        a.protocol == b.protocol &&
        a.port == b.port &&
        a.uuid == b.uuid &&
        a.network == b.network &&
        a.security == b.security &&
        a.sni.equals(b.sni, ignoreCase = true) &&
        a.host.equals(b.host, ignoreCase = true) &&
        a.method == b.method &&
        (a.address.equals(b.address, ignoreCase = true) || a.name == b.name)

    suspend fun refreshAll() {
        subDao.getAll().filter { it.autoUpdate }.forEach { refresh(it.id) }
    }

    // adapter to interface
    private suspend fun repo_upsert(p: Profile) = profileRepo.upsert(p)
}
