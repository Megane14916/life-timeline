package com.megane14916.lifetimeline.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
  tableName = "android_apps",
  indices = [Index(value = ["package_name"], unique = true)],
)
data class AndroidAppEntity(
  @PrimaryKey val id: String,
  @ColumnInfo(name = "package_name") val packageName: String,
  @ColumnInfo(name = "display_name") val displayName: String,
  @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
)
