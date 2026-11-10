package com.kuva.vpn.domain.repository

import com.kuva.vpn.domain.model.Profile
import kotlinx.coroutines.flow.Flow

/**
 * Profile storage. FREE and PERSONAL scopes live in the same table but are
 * strictly partitioned by `scope`; every mutating API takes an explicit scope
 * so free-repo operations can never touch personal rows (enforced in DAO too).
 */
interface ProfileRepository {
    fun observe(scope: Profile.Scope): Flow<List<Profile>>
    fun observeSearch(scope: Profile.Scope): Flow<List<Profile>>
    fun observeVault(scope: Profile.Scope): Flow<List<Profile>>
    suspend fun moveToVault(ids: List<Long>)
    fun observeSelected(): Flow<Profile?>
    suspend fun getSelected(): Profile?
    suspend fun getById(id: Long): Profile?
    suspend fun select(id: Long?)
    suspend fun upsert(profile: Profile): Long
    suspend fun delete(id: Long, scope: Profile.Scope) // scope-guarded
    suspend fun deleteDead(scope: Profile.Scope): Int
    suspend fun markTested(id: Long, pingMs: Int)
    /** Batched ping persistence: ONE pass over the rows instead of one
     *  UPDATE per result (each UPDATE re-triggers the list Flow and forced
     *  a full re-query + recomposition of the whole screen). */
    suspend fun markTestedBatch(results: List<Pair<Long, Int>>)
    /** Batched insert (one Room transaction = one list update). */
    suspend fun upsertAll(profiles: List<Profile>): Int
    /** Batched delete, scope-guarded. */
    suspend fun deleteAll(ids: List<Long>, scope: Profile.Scope)
    /** Wipe stale ping numbers so a new test round starts clean. */
    suspend fun clearPingFor(ids: List<Long>)
    suspend fun markSpeed(id: Long, downKbps: Double, upKbps: Double)
    suspend fun setFavorite(id: Long, fav: Boolean, scope: Profile.Scope)
    suspend fun setIsFromSearch(ids: List<Long>)
    suspend fun clearSearchResults(): Int
    suspend fun markNotFromSearch(ids: List<Long>)
    suspend fun findByFingerprint(hash: String, scope: Profile.Scope): Profile?
    suspend fun count(scope: Profile.Scope): Int
    suspend fun bestByPing(): Profile?
    suspend fun bestByPerformance(): Profile?
    suspend fun all(scope: Profile.Scope): List<Profile>
}
