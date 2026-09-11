package com.megane14916.lifetimeline.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.megane14916.lifetimeline.data.local.BackgroundWorkStateEntity

@Dao
interface BackgroundWorkStateDao {
  @Query("SELECT * FROM background_work_state WHERE work_key = :workKey LIMIT 1")
  suspend fun find(workKey: String): BackgroundWorkStateEntity?

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertIfAbsent(state: BackgroundWorkStateEntity)

  @Query(
    """
    UPDATE background_work_state
    SET lease_owner = :leaseOwner,
        lease_acquired_at_ms = :nowMs,
        lease_expires_at_ms = :expiresAtMs,
        last_attempt_at_ms = :nowMs,
        updated_at_ms = :nowMs
    WHERE work_key = :workKey
      AND (
        lease_owner IS NULL
        OR lease_expires_at_ms IS NULL
        OR lease_expires_at_ms <= :nowMs
        OR lease_acquired_at_ms IS NULL
        OR lease_acquired_at_ms > :futureCutoffMs
      )
    """,
  )
  suspend fun tryAcquire(
    workKey: String,
    leaseOwner: String,
    nowMs: Long,
    expiresAtMs: Long,
    futureCutoffMs: Long,
  ): Int

  @Query(
    """
    UPDATE background_work_state
    SET lease_expires_at_ms = :expiresAtMs,
        updated_at_ms = :nowMs
    WHERE work_key = :workKey AND lease_owner = :leaseOwner
    """,
  )
  suspend fun heartbeat(
    workKey: String,
    leaseOwner: String,
    nowMs: Long,
    expiresAtMs: Long,
  ): Int

  @Query(
    """
    UPDATE background_work_state
    SET lease_owner = NULL,
        lease_acquired_at_ms = NULL,
        lease_expires_at_ms = NULL,
        updated_at_ms = :nowMs
    WHERE work_key = :workKey AND lease_owner = :leaseOwner
    """,
  )
  suspend fun release(
    workKey: String,
    leaseOwner: String,
    nowMs: Long,
  ): Int

  @Query(
    """
    UPDATE background_work_state
    SET last_success_at_ms = :atMs,
        last_result = :result,
        last_error_kind = NULL,
        consecutive_failures = 0,
        updated_at_ms = :atMs
    WHERE work_key = :workKey AND lease_owner = :leaseOwner
    """,
  )
  suspend fun recordSuccess(
    workKey: String,
    leaseOwner: String,
    result: String,
    atMs: Long,
  ): Int

  @Query(
    """
    UPDATE background_work_state
    SET last_result = :result,
        last_error_kind = :errorKind,
        consecutive_failures = consecutive_failures + 1,
        updated_at_ms = :atMs
    WHERE work_key = :workKey AND lease_owner = :leaseOwner
    """,
  )
  suspend fun recordFailure(
    workKey: String,
    leaseOwner: String,
    result: String,
    errorKind: String,
    atMs: Long,
  ): Int
}
