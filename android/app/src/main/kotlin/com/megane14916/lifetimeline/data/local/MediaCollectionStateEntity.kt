package com.megane14916.lifetimeline.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "media_collection_state")
data class MediaCollectionStateEntity(
  @PrimaryKey @ColumnInfo(name = "volume_name") val volumeName: String,
  @ColumnInfo(name = "media_store_version") val mediaStoreVersion: String? = null,
  @ColumnInfo(name = "generation_cursor") val generationCursor: Long? = null,
  @ColumnInfo(name = "date_added_cursor_sec") val dateAddedCursorSeconds: Long? = null,
  @ColumnInfo(name = "media_id_cursor") val mediaIdCursor: Long? = null,
  @ColumnInfo(name = "collection_started_at_ms") val collectionStartedAtMs: Long,
  @ColumnInfo(name = "last_scan_at_ms") val lastScanAtMs: Long? = null,
  @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
)
