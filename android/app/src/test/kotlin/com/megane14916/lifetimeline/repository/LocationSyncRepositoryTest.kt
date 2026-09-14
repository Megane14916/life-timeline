package com.megane14916.lifetimeline.repository

import com.megane14916.lifetimeline.data.local.LocationPointEntity
import com.megane14916.lifetimeline.data.remote.LocationSyncDevice
import com.megane14916.lifetimeline.data.remote.LocationSyncFailureKind
import com.megane14916.lifetimeline.data.remote.LocationSyncRemoteException
import com.megane14916.lifetimeline.data.remote.LocationSyncResponse
import com.megane14916.lifetimeline.data.remote.LocationSyncUploader
import com.megane14916.lifetimeline.domain.generateUlid
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class LocationSyncRepositoryTest {
  @Test
  fun partialAcknowledgementRemovesOnlyAcceptedPointsAndContinuesInRecordedOrder() =
    runTest {
      val store = FakeLocationPointStore(listOf(point(1_000L), point(2_000L)))
      val repository = LocationCollectionRepository(store)
      val sent = mutableListOf<List<String>>()
      val uploader =
        LocationSyncUploader { request ->
          sent += request.locations.map { it.id }
          LocationSyncResponse(schemaVersion = 1, accepted = listOf(request.locations.first().id))
        }

      val result = LocationSyncRepository(repository, uploader, device()).syncPending()

      assertEquals(LocationSyncRunStatus.SUCCESS, result.status)
      assertEquals(2, result.acceptedCount)
      assertEquals(2, sent.size)
      assertTrue(sent[0].first() < sent[1].first())
      assertEquals(0, repository.countPending())
    }

  @Test
  fun ioFailurePreservesAllPendingPointsForRetryAfterPcReturns() =
    runTest {
      val store = FakeLocationPointStore(listOf(point(1_000L), point(2_000L)))
      val repository = LocationCollectionRepository(store)
      val uploader = LocationSyncUploader { throw IOException("offline") }

      val result = LocationSyncRepository(repository, uploader, device()).syncPending()

      assertEquals(LocationSyncRunStatus.FAILED, result.status)
      assertEquals(LocationSyncFailureKind.NETWORK, result.failure?.kind)
      assertTrue(result.failure?.retryable == true)
      assertEquals(2, repository.countPending())
    }

  @Test
  fun acknowledgementLossRetainsStableIdsForAnIdempotentRetry() =
    runTest {
      val repository = LocationCollectionRepository(FakeLocationPointStore(listOf(point(1_000L))))
      val serverAccepted = mutableSetOf<String>()
      val sentIds = mutableListOf<String>()
      var loseFirstResponse = true
      val uploader =
        LocationSyncUploader { request ->
          val ids = request.locations.map { it.id }
          sentIds += ids
          serverAccepted += ids
          if (loseFirstResponse) {
            loseFirstResponse = false
            throw IOException("response lost after server commit")
          }
          LocationSyncResponse(schemaVersion = 1, accepted = ids.filter { it in serverAccepted })
        }
      val syncRepository = LocationSyncRepository(repository, uploader, device())

      val first = syncRepository.syncPending()
      assertEquals(LocationSyncRunStatus.FAILED, first.status)
      assertEquals(1, repository.countPending())

      val second = syncRepository.syncPending()
      assertEquals(LocationSyncRunStatus.SUCCESS, second.status)
      assertEquals(sentIds.first(), sentIds.last())
      assertEquals(0, repository.countPending())
    }

  @Test
  fun malformedOrUnknownAckDoesNotCleanPendingPoints() =
    runTest {
      val store = FakeLocationPointStore(listOf(point(1_000L)))
      val repository = LocationCollectionRepository(store)
      val uploader = LocationSyncUploader { LocationSyncResponse(schemaVersion = 1, accepted = listOf(generateUlid(3_000L))) }

      val result = LocationSyncRepository(repository, uploader, device()).syncPending()

      assertEquals(LocationSyncRunStatus.FAILED, result.status)
      assertEquals(LocationSyncFailureKind.PROTOCOL, result.failure?.kind)
      assertFalse(result.failure?.retryable ?: true)
      assertEquals(1, repository.countPending())
    }

  @Test
  fun runBatchLimitLeavesRemainderPendingForASeparateRetry() =
    runTest {
      val store = FakeLocationPointStore((1..201).map { point(it * 1_000L) })
      val repository = LocationCollectionRepository(store)
      val uploader =
        LocationSyncUploader { request ->
          LocationSyncResponse(schemaVersion = 1, accepted = request.locations.map { it.id })
        }

      val result = LocationSyncRepository(repository, uploader, device()).syncPending(maxBatches = 1)

      assertEquals(LocationSyncRunStatus.RETRY_LIMIT_REACHED, result.status)
      assertEquals(200, result.acceptedCount)
      assertEquals(1, repository.countPending())
    }

  @Test
  fun nonRetryableHttpFailureDoesNotModifyPendingRows() =
    runTest {
      val store = FakeLocationPointStore(listOf(point(1_000L)))
      val repository = LocationCollectionRepository(store)
      val uploader =
        LocationSyncUploader {
          throw LocationSyncRemoteException(LocationSyncFailureKind.PROTOCOL, retryable = false)
        }

      val result = LocationSyncRepository(repository, uploader, device()).syncPending()

      assertEquals(LocationSyncFailureKind.PROTOCOL, result.failure?.kind)
      assertEquals(1, repository.countPending())
    }

  private fun device() = LocationSyncDevice(id = generateUlid(500L), name = "Android test", platform = "android")

  private fun point(timestamp: Long) =
    LocationPointEntity(
      id = generateUlid(timestamp),
      recordedAtMs = timestamp,
      latitude = 35.0,
      longitude = 139.0,
      accuracyM = 12.0,
      elapsedRealtimeNanos = timestamp * 1_000_000L,
      sourceFingerprint = "fingerprint-$timestamp",
      receivedAtMs = timestamp,
    )

  private class FakeLocationPointStore(
    points: List<LocationPointEntity>,
  ) : LocationPointStore {
    private val values = points.associateByTo(linkedMapOf()) { it.id }

    override suspend fun insertBatchIfAbsent(points: List<LocationPointEntity>): List<LocationPointInsertResult> =
      points.map { point ->
        val existing = values[point.id]
        if (existing == null) {
          values[point.id] = point
          LocationPointInsertResult(inserted = true)
        } else {
          LocationPointInsertResult(inserted = false, existing = existing)
        }
      }

    override suspend fun getPendingBatch(limit: Int): List<LocationPointEntity> =
      values.values
        .filter { it.syncStatus == LocationPointEntity.SYNC_PENDING }
        .sortedWith(compareBy<LocationPointEntity> { it.recordedAtMs }.thenBy { it.id })
        .take(limit)

    override suspend fun countPending(): Int = values.values.count { it.syncStatus == LocationPointEntity.SYNC_PENDING }

    override suspend fun markPendingAsSynced(
      ids: List<String>,
      syncedAtMs: Long,
    ): Int {
      var changed = 0
      ids.forEach { id ->
        val point = values[id]
        if (point?.syncStatus == LocationPointEntity.SYNC_PENDING) {
          values[id] = point.copy(syncStatus = LocationPointEntity.SYNCED, syncedAtMs = syncedAtMs)
          changed += 1
        }
      }
      return changed
    }

    override suspend fun deleteSyncedBatch(limit: Int): Int {
      val ids =
        values.values
          .filter { it.syncStatus == LocationPointEntity.SYNCED }
          .take(limit)
          .map { it.id }
      ids.forEach(values::remove)
      return ids.size
    }
  }
}
