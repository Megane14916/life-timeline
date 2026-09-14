package com.megane14916.lifetimeline.repository

import androidx.room.withTransaction
import com.megane14916.lifetimeline.data.local.LifeTimelineDatabase
import com.megane14916.lifetimeline.data.local.LocationPointEntity

/** Room adapter; each bounded insert page is atomic and does not advance or discard other pages. */
class RoomLocationPointStore(
  private val database: LifeTimelineDatabase,
) : LocationPointStore {
  override suspend fun insertBatchIfAbsent(points: List<LocationPointEntity>): List<LocationPointInsertResult> =
    database.withTransaction {
      val dao = database.locationPointDao()
      val rowIds = dao.insertAllIfAbsent(points)
      points.mapIndexed { index, point ->
        if (rowIds[index] != -1L) {
          LocationPointInsertResult(inserted = true)
        } else {
          val existing = dao.findByFingerprint(point.sourceFingerprint) ?: dao.findById(point.id)
          LocationPointInsertResult(inserted = false, existing = existing)
        }
      }
    }

  override suspend fun getPendingBatch(limit: Int): List<LocationPointEntity> = database.locationPointDao().getPendingBatch(limit)

  override suspend fun countPending(): Int = database.locationPointDao().countPending()

  override suspend fun latestReceivedAt(): Long? = database.backgroundWorkStateDao().find(LOCATION_RECEIVE_WORK_KEY)?.lastSuccessAtMs

  override suspend fun recordLatestReceivedAt(receivedAtMs: Long) {
    database.backgroundWorkStateDao().recordLocationReceivedAt(receivedAtMs)
  }

  override suspend fun markPendingAsSynced(
    ids: List<String>,
    syncedAtMs: Long,
  ): Int = database.locationPointDao().markPendingAsSynced(ids, syncedAtMs)

  override suspend fun deleteSyncedBatch(limit: Int): Int = database.locationPointDao().deleteSyncedBatch(limit)

  private companion object {
    const val LOCATION_RECEIVE_WORK_KEY = "location_receive_v1"
  }
}
