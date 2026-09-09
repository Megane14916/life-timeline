package com.megane14916.lifetimeline.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.megane14916.lifetimeline.data.local.AndroidAppSessionEntity

@Dao
interface AndroidAppSessionDao {
  @Query("SELECT * FROM android_app_sessions WHERE id = :id LIMIT 1")
  suspend fun findById(id: String): AndroidAppSessionEntity?

  @Query("SELECT * FROM android_app_sessions WHERE source_key = :sourceKey LIMIT 1")
  suspend fun findBySourceKey(sourceKey: String): AndroidAppSessionEntity?

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertIfAbsent(session: AndroidAppSessionEntity)

  @Query(
    """
    SELECT * FROM android_app_sessions
    WHERE sync_status = 'pending'
    ORDER BY started_at_ms ASC, id ASC
    LIMIT :limit
    """,
  )
  suspend fun getPending(limit: Int = 100): List<AndroidAppSessionEntity>

  @Query(
    """
    UPDATE android_app_sessions
    SET sync_status = 'synced', synced_at_ms = :syncedAtMs
    WHERE id IN (:ids) AND sync_status = 'pending'
    """,
  )
  suspend fun markAcceptedAsSynced(
    ids: List<String>,
    syncedAtMs: Long,
  ): Int

  @Query("SELECT COUNT(*) FROM android_app_sessions WHERE sync_status = 'pending'")
  suspend fun countPending(): Int
}
