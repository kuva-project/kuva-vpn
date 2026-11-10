package com.kuva.vpn.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    // --- reads ---
    @Query("SELECT * FROM profiles WHERE scope = :scope ORDER BY isFavorite DESC, CASE WHEN pingMs IS NULL THEN 1 ELSE 0 END, CASE WHEN pingMs < 0 THEN 1 ELSE 0 END, pingMs")
    fun observeByScope(scope: String): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE scope = :scope ORDER BY isFavorite DESC, pingMs IS NULL, pingMs < 0, pingMs")
    suspend fun getByScope(scope: String): List<ProfileEntity>

    // --- two-pane free tab: search (workshop) vs vault (storage) ---
    @Query("SELECT * FROM profiles WHERE scope = :scope AND inVault = 0 ORDER BY id")
    fun observeSearch(scope: String): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE scope = :scope AND inVault = 1 ORDER BY isFavorite DESC, pingMs IS NULL, pingMs < 0, pingMs")
    fun observeVault(scope: String): Flow<List<ProfileEntity>>

    @Query("UPDATE profiles SET inVault = 1 WHERE id IN (:ids)")
    suspend fun moveToVault(ids: List<Long>)

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun getById(id: Long): ProfileEntity?

    @Query("SELECT * FROM profiles WHERE id = :id")
    fun observeById(id: Long): Flow<ProfileEntity?>

    @Query("SELECT * FROM profiles WHERE isSelected LIMIT 1")
    suspend fun getSelected(): ProfileEntity?

    @Query("SELECT * FROM profiles WHERE isSelected LIMIT 1")
    fun observeSelected(): Flow<ProfileEntity?>

    @Query("SELECT COUNT(*) FROM profiles WHERE scope = :scope")
    suspend fun count(scope: String): Int

    @Query("SELECT * FROM profiles WHERE scope = :scope AND fingerprintHash = :hash LIMIT 1")
    suspend fun findByFingerprint(scope: String, hash: String): ProfileEntity?

    // --- writes (scope-guarded) ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: ProfileEntity): Long

    // ONE transaction for the whole batch: the list Flow fires exactly once.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(profiles: List<ProfileEntity>): List<Long>

    @Update
    suspend fun update(profile: ProfileEntity)

    @Query("UPDATE profiles SET isSelected = (id = :id)")
    suspend fun selectById(id: Long?)

    @Query("DELETE FROM profiles WHERE id = :id AND scope = :scope")
    suspend fun deleteScoped(id: Long, scope: String): Int

    @Query("DELETE FROM profiles WHERE scope = :scope AND isFavorite = 0 AND (pingMs IS NULL OR pingMs < 0)")
    suspend fun deleteDeadScoped(scope: String): Int

    // inVault = 0 guard: transferred configs belong to the vault and must
    // survive "clear list" even if their isFromSearch tag lingers
    @Query("DELETE FROM profiles WHERE scope = :scope AND isFromSearch = 1 AND inVault = 0")
    suspend fun deleteSearchResults(scope: String): Int

    @Query("UPDATE profiles SET isFromSearch = 0 WHERE id IN (:ids) AND scope = :scope")
    suspend fun markFromSearchCleared(ids: List<Long>, scope: String)

    @Query("DELETE FROM profiles WHERE id IN (:ids) AND scope = :scope")
    suspend fun deleteByIds(ids: List<Long>, scope: String): Int

    @Query("UPDATE profiles SET pingMs = NULL, lastTestedAt = NULL WHERE id IN (:ids)")
    suspend fun clearPingByIds(ids: List<Long>)

    @Query("DELETE FROM profiles WHERE scope = :scope AND subscriptionId = :subId")
    suspend fun deleteBySubscription(scope: String, subId: Long): Int

    // cold-start reset: stale test numbers from a previous run must never
    // be shown as if they were fresh (see KuvaApplication.onCreate)
    @Query("UPDATE profiles SET pingMs = NULL, speedDownKbps = NULL, speedUpKbps = NULL, lastTestedAt = NULL")
    suspend fun resetAllTestResults()

    @Query("UPDATE profiles SET pingMs = :pingMs, lastTestedAt = :testedAt WHERE id = :id")
    suspend fun markTested(id: Long, pingMs: Int, testedAt: Long)

    @Query("UPDATE profiles SET speedDownKbps = :down, speedUpKbps = :up, lastTestedAt = :testedAt WHERE id = :id")
    suspend fun markSpeed(id: Long, down: Double, up: Double, testedAt: Long)

    @Query("UPDATE profiles SET isFavorite = :fav WHERE id = :id AND scope = :scope")
    suspend fun setFavorite(id: Long, fav: Boolean, scope: String)

    @Query("UPDATE profiles SET isFromSearch = 1 WHERE id IN (:ids)")
    suspend fun markFromSearch(ids: List<Long>)

    @Query("UPDATE profiles SET isFromSearch = 0 WHERE scope = :scope")
    suspend fun clearFromSearch(scope: String)
}

@Dao
interface SubscriptionDao {
    // pinned subscriptions first (v3), then alphabetical
    @Query("SELECT * FROM subscriptions ORDER BY pinned DESC, name COLLATE NOCASE")
    fun observeAll(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions ORDER BY pinned DESC, name COLLATE NOCASE")
    suspend fun getAll(): List<SubscriptionEntity>

    @Query("SELECT * FROM subscriptions WHERE id = :id")
    suspend fun getById(id: Long): SubscriptionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(sub: SubscriptionEntity): Long

    @Update
    suspend fun update(sub: SubscriptionEntity)

    @Query("DELETE FROM subscriptions WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE subscriptions SET lastUpdated = :ts, configCount = :count, lastError = NULL, uploadBytes = :up, downloadBytes = :down, totalBytes = :total, expireEpochSec = :expire WHERE id = :id")
    suspend fun markUpdated(id: Long, ts: Long, count: Int, up: Long?, down: Long?, total: Long?, expire: Long?)

    @Query("UPDATE subscriptions SET lastError = :err WHERE id = :id")
    suspend fun markError(id: Long, err: String)
}

@Dao
interface ConnectionHistoryDao {
    @Query("SELECT * FROM connection_history ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<ConnectionHistoryEntity>>

    @Insert
    suspend fun insert(entry: ConnectionHistoryEntity): Long

    @Query("UPDATE connection_history SET endedAt = :endedAt, totalDownBytes = :down, totalUpBytes = :up, exitIp = :exitIp, country = :country WHERE id = :id")
    suspend fun finish(id: Long, endedAt: Long, down: Long, up: Long, exitIp: String?, country: String?)

    @Query("DELETE FROM connection_history")
    suspend fun clear()
}

@Database(
    entities = [
        ProfileEntity::class,
        SubscriptionEntity::class,
        ConnectionHistoryEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class KuvaDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun connectionHistoryDao(): ConnectionHistoryDao

    companion object {
        /** v2: adds the vault flag for the two-pane free tab. */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN inVault INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v3: pin subscriptions to the top of the list. */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
