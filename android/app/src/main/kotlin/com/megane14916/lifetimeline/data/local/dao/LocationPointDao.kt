package com.megane14916.lifetimeline.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.megane14916.lifetimeline.data.local.LocationPointEntity

@Dao
interface LocationPointDao {
  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertAllIfAbsent(points: List<LocationPointEntity>): List<Long>

  @Query("SELECT * FROM android_location_points WHERE id = :id LIMIT 1")
  suspend fun findById(id: String): LocationPointEntity?

  @Query("SELECT * FROM android_location_points WHERE source_fingerprint = :fingerprint LIMIT 1")
  suspend fun findByFingerprint(fingerprint: String): LocationPointEntity?

  @Query(
    """
    SELECT * FROM android_location_points
    WHERE sync_status = 'pending'
    ORDER BY recorded_at_ms ASC, elapsed_realtime_nanos ASC, latitude ASC, longitude ASC, id ASC
    LIMIT :limit
    """,
  )
  suspend fun getPendingBatch(limit: Int): List<LocationPointEntity>

  @Query("SELECT COUNT(*) FROM android_location_points WHERE sync_status = 'pending'")
  suspend fun countPending(): Int

  @Query(
    """
    UPDATE android_location_points
    SET sync_status = 'synced', synced_at_ms = :syncedAtMs
    WHERE id IN (:ids) AND sync_status = 'pending'
    """,
  )
  suspend fun markPendingAsSynced(
    ids: List<String>,
    syncedAtMs: Long,
  ): Int

  @Query(
    """
    DELETE FROM android_location_points
    WHERE id IN (
      SELECT id FROM android_location_points
      WHERE sync_status = 'synced'
      ORDER BY synced_at_ms ASC, id ASC
      LIMIT :limit
    )
    """,
  )
  suspend fun deleteSyncedBatch(limit: Int): Int
}
