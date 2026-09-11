package com.megane14916.lifetimeline.repository

import androidx.room.withTransaction
import com.megane14916.lifetimeline.data.local.BackgroundWorkStateEntity
import com.megane14916.lifetimeline.data.local.LifeTimelineDatabase
import com.megane14916.lifetimeline.domain.generateUlid
import com.megane14916.lifetimeline.worker.AutomaticSyncPolicy
import java.util.concurrent.TimeUnit

data class BackgroundLease(
  val workKey: String,
  val owner: String,
)

/** Serializes manual and automatic execution with an expiring, token-owned Room lease. */
class BackgroundExecutionCoordinator(
  private val database: LifeTimelineDatabase,
  private val nowMs: () -> Long = { System.currentTimeMillis() },
  private val ownerGenerator: (Long) -> String = { timestampMs -> generateUlid(timestampMs) },
  private val leaseTtlMs: Long = TimeUnit.MINUTES.toMillis(AutomaticSyncPolicy.LEASE_TTL_MINUTES),
  private val maxClockSkewMs: Long = TimeUnit.MINUTES.toMillis(AutomaticSyncPolicy.MAX_CLOCK_SKEW_MINUTES),
) {
  suspend fun acquire(workKey: String): BackgroundLease? {
    require(workKey.isNotBlank()) { "Work key must not be blank." }
    val currentTimeMs = checkedNow()
    val leaseOwner = ownerGenerator(currentTimeMs)
    require(leaseOwner.isNotBlank()) { "Lease owner must not be blank." }
    val expiresAtMs = safeAdd(currentTimeMs, leaseTtlMs)
    val futureCutoffMs = safeAdd(currentTimeMs, maxClockSkewMs)
    val acquired =
      database.withTransaction {
        val dao = database.backgroundWorkStateDao()
        dao.insertIfAbsent(
          BackgroundWorkStateEntity(
            workKey = workKey,
            updatedAtMs = currentTimeMs,
          ),
        )
        dao.tryAcquire(
          workKey = workKey,
          leaseOwner = leaseOwner,
          nowMs = currentTimeMs,
          expiresAtMs = expiresAtMs,
          futureCutoffMs = futureCutoffMs,
        )
      }
    return if (acquired == 1) BackgroundLease(workKey, leaseOwner) else null
  }

  suspend fun heartbeat(lease: BackgroundLease): Boolean {
    val currentTimeMs = checkedNow()
    val expiresAtMs = safeAdd(currentTimeMs, leaseTtlMs)
    return database.backgroundWorkStateDao().heartbeat(
      workKey = lease.workKey,
      leaseOwner = lease.owner,
      nowMs = currentTimeMs,
      expiresAtMs = expiresAtMs,
    ) == 1
  }

  suspend fun release(lease: BackgroundLease): Boolean =
    database.backgroundWorkStateDao().release(
      workKey = lease.workKey,
      leaseOwner = lease.owner,
      nowMs = checkedNow(),
    ) == 1

  suspend fun recordSuccess(
    lease: BackgroundLease,
    result: String,
  ): Boolean {
    require(result.isNotBlank()) { "Result code must not be blank." }
    val currentTimeMs = checkedNow()
    return database.backgroundWorkStateDao().recordSuccess(
      workKey = lease.workKey,
      leaseOwner = lease.owner,
      result = result,
      atMs = currentTimeMs,
    ) == 1
  }

  suspend fun recordFailure(
    lease: BackgroundLease,
    result: String,
    errorKind: String,
  ): Boolean {
    require(result.isNotBlank()) { "Result code must not be blank." }
    require(errorKind.isNotBlank()) { "Error kind must not be blank." }
    val currentTimeMs = checkedNow()
    return database.backgroundWorkStateDao().recordFailure(
      workKey = lease.workKey,
      leaseOwner = lease.owner,
      result = result,
      errorKind = errorKind,
      atMs = currentTimeMs,
    ) == 1
  }

  suspend fun findState(workKey: String): BackgroundWorkStateEntity? = database.backgroundWorkStateDao().find(workKey)

  private fun checkedNow(): Long {
    val currentTimeMs = nowMs()
    require(currentTimeMs >= 0) { "Background work timestamp must be non-negative." }
    return currentTimeMs
  }

  private fun safeAdd(
    value: Long,
    increment: Long,
  ): Long {
    require(increment >= 0 && value <= Long.MAX_VALUE - increment) {
      "Background work timestamp overflowed."
    }
    return value + increment
  }
}
