package com.megane14916.lifetimeline.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "background_work_state")
data class BackgroundWorkStateEntity(
  @PrimaryKey
  @ColumnInfo(name = "work_key")
  val workKey: String,
  @ColumnInfo(name = "lease_owner")
  val leaseOwner: String? = null,
  @ColumnInfo(name = "lease_acquired_at_ms")
  val leaseAcquiredAtMs: Long? = null,
  @ColumnInfo(name = "lease_expires_at_ms")
  val leaseExpiresAtMs: Long? = null,
  @ColumnInfo(name = "last_attempt_at_ms")
  val lastAttemptAtMs: Long? = null,
  @ColumnInfo(name = "last_success_at_ms")
  val lastSuccessAtMs: Long? = null,
  @ColumnInfo(name = "last_result")
  val lastResult: String? = null,
  @ColumnInfo(name = "last_error_kind")
  val lastErrorKind: String? = null,
  @ColumnInfo(name = "consecutive_failures", defaultValue = "0")
  val consecutiveFailures: Int = 0,
  @ColumnInfo(name = "updated_at_ms")
  val updatedAtMs: Long,
)
