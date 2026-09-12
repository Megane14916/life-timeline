package com.megane14916.lifetimeline.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.megane14916.lifetimeline.data.local.AndroidMediaItemEntity

@Dao
interface AndroidMediaItemDao {
  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertIfAbsent(item: AndroidMediaItemEntity): Long

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertAllIfAbsent(items: List<AndroidMediaItemEntity>): List<Long>

  @Query("SELECT * FROM android_media_items WHERE id = :id LIMIT 1")
  suspend fun findById(id: String): AndroidMediaItemEntity?

  @Query("SELECT * FROM android_media_items WHERE source = :source AND source_id = :sourceId LIMIT 1")
  suspend fun findBySource(
    source: String,
    sourceId: String,
  ): AndroidMediaItemEntity?

  @Query(
    """
    UPDATE android_media_items
    SET thumbnail_state = :state,
        thumbnail_relative_path = :relativePath,
        thumbnail_sha256 = :sha256,
        thumbnail_size_bytes = :sizeBytes,
        latitude = :latitude,
        longitude = :longitude,
        last_error_kind = :errorKind
    WHERE id = :id AND thumbnail_state = 'pending'
    """,
  )
  suspend fun updateThumbnail(
    id: String,
    state: String,
    relativePath: String?,
    sha256: String?,
    sizeBytes: Long?,
    latitude: Double?,
    longitude: Double?,
    errorKind: String?,
  ): Int

  @Query("SELECT * FROM android_media_items WHERE thumbnail_state = 'pending' ORDER BY captured_at_ms ASC, id ASC LIMIT :limit")
  suspend fun getPendingThumbnails(limit: Int = 20): List<AndroidMediaItemEntity>

  @Query("SELECT COUNT(*) FROM android_media_items WHERE thumbnail_state = 'pending'")
  suspend fun countPendingThumbnails(): Int

  @Query("SELECT COUNT(*) FROM android_media_items WHERE sync_status = 'pending'")
  suspend fun countPendingSync(): Int

  @Query("SELECT COUNT(*) FROM android_media_items WHERE sync_status = 'pending' AND thumbnail_state IN ('ready', 'unavailable')")
  suspend fun countPendingSyncable(): Int

  @Query("SELECT COALESCE(SUM(thumbnail_size_bytes), 0) FROM android_media_items WHERE thumbnail_relative_path IS NOT NULL")
  suspend fun totalStoredThumbnailBytes(): Long

  @Query(
    """
    SELECT * FROM android_media_items
    WHERE sync_status = 'pending' AND thumbnail_state IN ('ready', 'unavailable')
    ORDER BY captured_at_ms ASC, id ASC LIMIT :limit
    """,
  )
  suspend fun getPendingSyncBatch(limit: Int): List<AndroidMediaItemEntity>

  @Query(
    """
    UPDATE android_media_items
    SET sync_status = 'synced',
        synced_at_ms = :syncedAtMs,
        thumbnail_state = CASE WHEN thumbnail_state = 'ready' THEN 'cleaned' ELSE thumbnail_state END,
        thumbnail_relative_path = CASE WHEN thumbnail_state = 'ready' THEN NULL ELSE thumbnail_relative_path END
    WHERE id = :id
      AND sync_status = 'pending'
      AND thumbnail_state = :expectedThumbnailState
      AND thumbnail_sha256 IS :expectedSha256
      AND thumbnail_relative_path IS :expectedRelativePath
    """,
  )
  suspend fun acknowledgeSynced(
    id: String,
    expectedThumbnailState: String,
    expectedSha256: String?,
    expectedRelativePath: String?,
    syncedAtMs: Long,
  ): Int

  @Query("SELECT thumbnail_relative_path FROM android_media_items WHERE thumbnail_relative_path IS NOT NULL")
  suspend fun getReferencedThumbnailPaths(): List<String>
}
