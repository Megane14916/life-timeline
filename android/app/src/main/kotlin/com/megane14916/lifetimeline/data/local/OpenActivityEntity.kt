package com.megane14916.lifetimeline.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "open_activities")
data class OpenActivityEntity(
  @PrimaryKey
  @ColumnInfo(name = "activity_key")
  val activityKey: String,
  @ColumnInfo(name = "package_name") val packageName: String,
  @ColumnInfo(name = "class_name") val className: String,
  @ColumnInfo(name = "started_at_ms") val startedAtMs: Long,
  @ColumnInfo(name = "start_event_key") val startEventKey: String,
)
