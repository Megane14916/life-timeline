package com.megane14916.lifetimeline.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "collector_state")
data class CollectorStateEntity(
  @PrimaryKey val collector: String,
  @ColumnInfo(name = "cursor_at_ms") val cursorAtMs: Long,
  @ColumnInfo(name = "cursor_key") val cursorKey: String,
  @ColumnInfo(name = "last_collected_at_ms") val lastCollectedAtMs: Long,
)
