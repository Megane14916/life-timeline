package com.megane14916.lifetimeline.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
  tableName = "android_app_sessions",
  foreignKeys = [
    ForeignKey(
      entity = AndroidAppEntity::class,
      parentColumns = ["id"],
      childColumns = ["app_id"],
      onDelete = ForeignKey.RESTRICT,
    ),
  ],
  indices = [
    Index(value = ["app_id"]),
    Index(value = ["source_key"], unique = true),
    Index(value = ["sync_status", "started_at_ms", "id"]),
  ],
)
data class AndroidAppSessionEntity(
  @PrimaryKey val id: String,
  @ColumnInfo(name = "app_id") val appId: String,
  @ColumnInfo(name = "started_at_ms") val startedAtMs: Long,
  @ColumnInfo(name = "ended_at_ms") val endedAtMs: Long,
  @ColumnInfo(name = "duration_ms") val durationMs: Long,
  val source: String,
  @ColumnInfo(name = "source_key") val sourceKey: String,
  @ColumnInfo(name = "sync_status") val syncStatus: String,
  @ColumnInfo(name = "collected_at_ms") val collectedAtMs: Long,
  @ColumnInfo(name = "synced_at_ms") val syncedAtMs: Long? = null,
)
