package com.kuva.vpn.data.repository

import com.kuva.vpn.data.crypto.FieldCrypto
import com.kuva.vpn.data.db.ProfileDao
import com.kuva.vpn.data.db.ProfileEntity
import com.kuva.vpn.domain.model.Profile
import com.kuva.vpn.domain.repository.ProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileRepositoryImpl @Inject constructor(
    private val dao: ProfileDao,
    private val crypto: FieldCrypto,
) : ProfileRepository {

    // ---------- mapping ----------
    private fun ProfileEntity.toDomain(): Profile = Profile(
        id = id,
        scope = Profile.Scope.valueOf(scope),
        name = name,
        protocol = Profile.Protocol.valueOf(protocol),
        address = address,
        port = port,
        uuid = crypto.decrypt(uuidEnc),
        method = method,
        alterId = alterId,
        flow = flow,
        encryption = encryption,
        network = runCatching { Profile.Transport.valueOf(network.uppercase()) }.getOrDefault(Profile.Transport.TCP),
        security = runCatching { Profile.Security.valueOf(security.uppercase()) }.getOrDefault(Profile.Security.NONE),
        sni = sni, host = host, path = path, serviceName = serviceName, alpn = alpn,
        fingerprint = fingerprint,
        publicKey = crypto.decrypt(publicKeyEnc),
        shortId = shortId, spiderX = spiderX, allowInsecure = allowInsecure,
        source = source, discoveredAt = discoveredAt, fingerprintHash = fingerprintHash,
        pingMs = pingMs, speedDownKbps = speedDownKbps, speedUpKbps = speedUpKbps,
        lastTestedAt = lastTestedAt, country = country,
        isFavorite = isFavorite, isFromSearch = isFromSearch, inVault = inVault, subscriptionId = subscriptionId,
        rawJson = crypto.decrypt(rawJsonEnc),
    ).copy(fingerprintHash = fingerprintHash)

    private fun Profile.toEntity(existing: ProfileEntity? = null): ProfileEntity = ProfileEntity(
        id = id,
        scope = scope.name,
        name = name,
        protocol = protocol.name,
        address = address,
        port = port,
        uuidEnc = crypto.encrypt(uuid),
        method = method,
        alterId = alterId,
        flow = flow,
        encryption = encryption,
        network = network.name.lowercase(),
        security = security.name.lowercase(),
        sni = sni, host = host, path = path, serviceName = serviceName, alpn = alpn,
        fingerprint = fingerprint,
        publicKeyEnc = crypto.encrypt(publicKey),
        shortId = shortId, spiderX = spiderX, allowInsecure = allowInsecure,
        source = source, discoveredAt = discoveredAt,
        fingerprintHash = fingerprintHash ?: Profile.fingerprintOf(this),
        pingMs = pingMs, speedDownKbps = speedDownKbps, speedUpKbps = speedUpKbps,
        lastTestedAt = lastTestedAt, country = country,
        isFavorite = existing?.isFavorite ?: isFavorite,
        isSelected = existing?.isSelected ?: false,
        isFromSearch = existing?.isFromSearch ?: isFromSearch,
        inVault = existing?.inVault ?: inVault,
        subscriptionId = subscriptionId,
        rawJsonEnc = crypto.encrypt(rawJson),
    )

    // ---------- reads ----------
    // flowOn is CRITICAL: every row decrypts 3 Keystore fields (AES-GCM) and
    // without flowOn that mapping ran on the MAIN thread — every list update
    // froze the UI for the whole decrypt storm (the "app dies while
    // searching/pinging" bug).
    override fun observe(scope: Profile.Scope): Flow<List<Profile>> =
        dao.observeByScope(scope.name).map { list -> list.map { it.toDomain() } }
            .flowOn(Dispatchers.Default)

    override fun observeSearch(scope: Profile.Scope): Flow<List<Profile>> =
        dao.observeSearch(scope.name).map { list -> list.map { it.toDomain() } }
            .flowOn(Dispatchers.Default)

    override fun observeVault(scope: Profile.Scope): Flow<List<Profile>> =
        dao.observeVault(scope.name).map { list -> list.map { it.toDomain() } }
            .flowOn(Dispatchers.Default)

    override suspend fun moveToVault(ids: List<Long>) {
        if (ids.isNotEmpty()) dao.moveToVault(ids)
    }

    override fun observeSelected(): Flow<Profile?> =
        dao.observeSelected().map { it?.toDomain() }
            .flowOn(Dispatchers.Default)

    // Suspend reads also decrypt (crypto in toDomain) — keep the mapping OFF
    // the caller's (main) thread: testAllPings/connectFastest call all() right
    // on the UI scope and a 100-row decrypt storm there froze the app.
    override suspend fun getSelected(): Profile? =
        kotlinx.coroutines.withContext(Dispatchers.Default) { dao.getSelected()?.toDomain() }

    override suspend fun getById(id: Long): Profile? =
        kotlinx.coroutines.withContext(Dispatchers.Default) { dao.getById(id)?.toDomain() }

    override suspend fun count(scope: Profile.Scope): Int = dao.count(scope.name)

    override suspend fun findByFingerprint(hash: String, scope: Profile.Scope): Profile? =
        kotlinx.coroutines.withContext(Dispatchers.Default) {
            dao.findByFingerprint(scope.name, hash)?.toDomain()
        }

    override suspend fun all(scope: Profile.Scope): List<Profile> =
        kotlinx.coroutines.withContext(Dispatchers.Default) {
            dao.getByScope(scope.name).map { it.toDomain() }
        }

    // ---------- writes ----------
    override suspend fun upsert(profile: Profile): Long {
        val existing = if (profile.id != 0L) dao.getById(profile.id) else null
        return dao.insert(profile.toEntity(existing))
    }

    override suspend fun upsertAll(profiles: List<Profile>): Int {
        if (profiles.isEmpty()) return 0
        // encryption of uuid/publicKey/rawJson per row is Keystore work: keep
        // it off the main thread (search-finalize runs on the UI scope).
        kotlinx.coroutines.withContext(Dispatchers.Default) {
            dao.insertAll(profiles.map { it.toEntity() })
        }
        return profiles.size
    }

    override suspend fun deleteAll(ids: List<Long>, scope: Profile.Scope) {
        // connected-config protection: the selected row is never deletable
        val selected = dao.getSelected()
        val safe = ids.filterNot { it == selected?.id }
        if (safe.isEmpty()) return
        if (ids.isEmpty()) return
        // chunked: SQLite caps bound variables (~999)
        ids.chunked(500).forEach { dao.deleteByIds(it, scope.name) }
    }

    override suspend fun clearPingFor(ids: List<Long>) {
        if (ids.isEmpty()) return
        ids.chunked(500).forEach { dao.clearPingByIds(it) }
    }

    override suspend fun select(id: Long?) = dao.selectById(id)

    override suspend fun delete(id: Long, scope: Profile.Scope) {
        // connected-config protection: the selected row is never deletable
        if (dao.getSelected()?.id == id) return
        dao.deleteScoped(id, scope.name)
    }

    override suspend fun deleteDead(scope: Profile.Scope): Int = dao.deleteDeadScoped(scope.name)

    override suspend fun markTested(id: Long, pingMs: Int) =
        dao.markTested(id, pingMs, System.currentTimeMillis())

    override suspend fun markTestedBatch(results: List<Pair<Long, Int>>) {
        if (results.isEmpty()) return
        val now = System.currentTimeMillis()
        results.forEach { (id, ms) -> dao.markTested(id, ms, now) }
    }

    override suspend fun markSpeed(id: Long, downKbps: Double, upKbps: Double) =
        dao.markSpeed(id, downKbps, upKbps, System.currentTimeMillis())

    override suspend fun setFavorite(id: Long, fav: Boolean, scope: Profile.Scope) =
        dao.setFavorite(id, fav, scope.name)

    override suspend fun setIsFromSearch(ids: List<Long>) = dao.markFromSearch(ids)

    override suspend fun clearSearchResults(): Int = dao.deleteSearchResults(Profile.Scope.FREE.name)

    override suspend fun markNotFromSearch(ids: List<Long>) {
        if (ids.isNotEmpty()) dao.markFromSearchCleared(ids, Profile.Scope.FREE.name)
    }

    // ---------- smart selection ----------
    override suspend fun bestByPing(): Profile? {
        val candidates = dao.getByScope(Profile.Scope.FREE.name) +
            dao.getByScope(Profile.Scope.PERSONAL.name)
        return candidates
            .filter { it.pingMs != null && it.pingMs > 0 }
            .minByOrNull { it.pingMs!! }
            ?.toDomain()
    }

    /**
     * Weighted score: availability (must have passed a ping test) then
     * latency, measured speed, and no recent failure. Never trusts
     * advertised values — only locally measured ones.
     */
    override suspend fun bestByPerformance(): Profile? {
        val candidates = (dao.getByScope(Profile.Scope.FREE.name) +
            dao.getByScope(Profile.Scope.PERSONAL.name))
            .filter { it.pingMs != null && it.pingMs > 0 }
        if (candidates.isEmpty()) return bestByPing()
        return candidates.maxByOrNull { p ->
            val pingScore = 1.0 / (1.0 + p.pingMs!! / 100.0)                 // 0..1
            val downScore = ((p.speedDownKbps ?: 0.0) / 10_000.0).coerceAtMost(1.0)
            val upScore = ((p.speedUpKbps ?: 0.0) / 5_000.0).coerceAtMost(1.0)
            val testedScore = if (p.lastTestedAt != null && 
                System.currentTimeMillis() - p.lastTestedAt < 24 * 3600_000L) 1.0 else 0.5
            pingScore * 0.45 + downScore * 0.35 + upScore * 0.10 + testedScore * 0.10
        }?.toDomain()
    }
}
