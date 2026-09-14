package com.megane14916.lifetimeline.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
  tableName = "android_location_points",
  indices = [
    Index(value = ["source_fingerprint"], unique = true),
    Index(value = ["sync_status", "recorded_at_ms", "id"]),
  ],
)
data class LocationPointEntity(
  @PrimaryKey val id: String,
  val source: String = SOURCE,
  @ColumnInfo(name = "recorded_at_ms") val recordedAtMs: Long,
  val latitude: Double,
  val longitude: Double,
  @ColumnInfo(name = "accuracy_m") val accuracyM: Double? = null,
  @ColumnInfo(name = "altitude_m") val altitudeM: Double? = null,
  @ColumnInfo(name = "speed_mps") val speedMps: Double? = null,
  @ColumnInfo(name = "elapsed_realtime_nanos") val elapsedRealtimeNanos: Long,
  @ColumnInfo(name = "source_fingerprint") val sourceFingerprint: String,
  @ColumnInfo(name = "sync_status") val syncStatus: String = SYNC_PENDING,
  @ColumnInfo(name = "received_at_ms") val receivedAtMs: Long,
  @ColumnInfo(name = "synced_at_ms") val syncedAtMs: Long? = null,
  @ColumnInfo(name = "last_error_kind") val lastErrorKind: String? = null,
) {
  companion object {
    const val SOURCE = "android_fused_location"
    const val SYNC_PENDING = "pending"
    const val SYNCED = "synced"
  }
}
