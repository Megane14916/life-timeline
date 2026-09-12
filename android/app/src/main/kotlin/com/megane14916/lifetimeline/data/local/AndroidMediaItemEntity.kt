package com.megane14916.lifetimeline.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
  tableName = "android_media_items",
  indices = [
    Index(value = ["source", "source_id"], unique = true),
    Index(value = ["sync_status", "captured_at_ms", "id"]),
    Index(value = ["thumbnail_state", "captured_at_ms", "id"]),
  ],
)
data class AndroidMediaItemEntity(
  @PrimaryKey val id: String,
  val source: String = SOURCE,
  @ColumnInfo(name = "source_id") val sourceId: String,
  @ColumnInfo(name = "volume_name") val volumeName: String,
  @ColumnInfo(name = "media_store_id") val mediaStoreId: Long,
  val filename: String,
  @ColumnInfo(name = "captured_at_ms") val capturedAtMs: Long,
  @ColumnInfo(name = "captured_at_source") val capturedAtSource: String,
  val width: Int? = null,
  val height: Int? = null,
  @ColumnInfo(name = "mime_type") val mimeType: String,
  val latitude: Double? = null,
  val longitude: Double? = null,
  @ColumnInfo(name = "thumbnail_state") val thumbnailState: String = THUMBNAIL_PENDING,
  @ColumnInfo(name = "thumbnail_relative_path") val thumbnailRelativePath: String? = null,
  @ColumnInfo(name = "thumbnail_sha256") val thumbnailSha256: String? = null,
  @ColumnInfo(name = "thumbnail_size_bytes") val thumbnailSizeBytes: Long? = null,
  @ColumnInfo(name = "sync_status") val syncStatus: String = SYNC_PENDING,
  @ColumnInfo(name = "discovered_at_ms") val discoveredAtMs: Long,
  @ColumnInfo(name = "synced_at_ms") val syncedAtMs: Long? = null,
  @ColumnInfo(name = "last_error_kind") val lastErrorKind: String? = null,
) {
  companion object {
    const val SOURCE = "android_media_store"
    const val THUMBNAIL_PENDING = "pending"
    const val THUMBNAIL_READY = "ready"
    const val THUMBNAIL_UNAVAILABLE = "unavailable"
    const val THUMBNAIL_CLEANED = "cleaned"
    const val SYNC_PENDING = "pending"
    const val SYNCED = "synced"
  }
}
