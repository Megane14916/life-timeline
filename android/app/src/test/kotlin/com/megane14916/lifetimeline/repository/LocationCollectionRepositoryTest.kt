package com.megane14916.lifetimeline.repository

import com.megane14916.lifetimeline.data.local.LocationPointEntity
import com.megane14916.lifetimeline.domain.generateUlid
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationCollectionRepositoryTest {
  @Test
  fun sortsOutOfOrderFixesBeforePersisting() =
    runBlocking {
      val store = FakeLocationPointStore()
      val repository = LocationCollectionRepository(store)

      val result =
        repository.persist(
          fixes = listOf(fix(3_000, elapsed = 30), fix(1_000, elapsed = 10), fix(2_000, elapsed = 20)),
          deviceId = DEVICE_ID,
          collectionStartedAtMs = 0,
          nowMs = 5_000,
        )

      assertEquals(3, result.insertedCount)
      assertEquals(listOf(1_000L, 2_000L, 3_000L), store.all().map { it.recordedAtMs })
      assertEquals(listOf(10L, 20L, 30L), store.all().map { it.elapsedRealtimeNanos })
    }

  @Test
  fun redeliveryAndDuplicatesProduceTheSameIdWithoutReplacingRows() =
    runBlocking {
      val store = FakeLocationPointStore()
      val repository = LocationCollectionRepository(store)
      val point = fix(2_000, elapsed = 123, latitude = 35.123456789)

      val first = repository.persist(listOf(point, point), DEVICE_ID, 0, 5_000)
      val persistedId = store.all().single().id
      val second = repository.persist(listOf(point), DEVICE_ID, 0, 5_100)

      assertEquals(1, first.insertedCount)
      assertEquals(1, first.duplicateCount)
      assertEquals(1, second.duplicateCount)
      assertEquals(0, second.insertedCount)
      assertEquals(1, store.all().size)
      assertEquals(persistedId, store.all().single().id)
      assertEquals(35.1234568, store.all().single().latitude, 0.0)
    }

  @Test
  fun rejectsCachedFutureAndInvalidRequiredNumericValuesButNullsInvalidOptionalValues() =
    runBlocking {
      val store = FakeLocationPointStore()
      val repository = LocationCollectionRepository(store)
      val accepted = fix(10_000, elapsed = 100, accuracy = Double.NaN, altitude = Double.NEGATIVE_INFINITY, speed = -1.0)
      val result =
        repository.persist(
          fixes =
            listOf(
              fix(9_999, elapsed = 1), // cached before opt-in
              fix(310_001, elapsed = 2), // more than five minutes in the future
              fix(10_000, latitude = Double.NaN, elapsed = 3),
              fix(10_000, longitude = 180.01, elapsed = 4),
              fix(10_000, elapsed = -1),
              accepted,
            ),
          deviceId = DEVICE_ID,
          collectionStartedAtMs = 10_000,
          nowMs = 10_000,
        )

      assertEquals(1, result.insertedCount)
      assertEquals(5, result.invalidCount)
      assertNull(store.all().single().accuracyM)
      assertNull(store.all().single().altitudeM)
      assertNull(store.all().single().speedMps)
    }

  @Test
  fun acceptsOnlySmallClockSkewAndKeepsDistinctSameTimestampFixes() =
    runBlocking {
      val store = FakeLocationPointStore()
      val repository = LocationCollectionRepository(store)
      val result =
        repository.persist(
          listOf(
            fix(400_000, elapsed = 1, latitude = 35.0, longitude = 139.0),
            fix(400_000, elapsed = 2, latitude = 35.0, longitude = 139.000001),
          ),
          DEVICE_ID,
          collectionStartedAtMs = 0,
          nowMs = 100_000,
        )

      assertEquals(2, result.insertedCount)
      assertNotEquals(store.all()[0].id, store.all()[1].id)
      assertEquals(
        2,
        store
          .all()
          .map { it.sourceFingerprint }
          .toSet()
          .size,
      )
    }

  @Test
  fun doesNotOverwriteContentConflictForAnExistingFingerprint() =
    runBlocking {
      val store = FakeLocationPointStore()
      val repository = LocationCollectionRepository(store)
      val point = fix(2_000, elapsed = 123, accuracy = 5.0)
      repository.persist(listOf(point), DEVICE_ID, 0, 5_000)

      val conflict = repository.persist(listOf(point.copy(accuracyM = 9.0)), DEVICE_ID, 0, 5_000)

      assertEquals(1, conflict.conflictCount)
      assertEquals(5.0, store.all().single().accuracyM!!, 0.0)
    }

  @Test
  fun committedChunksSurviveCrashAndRedeliveryCompletesTheUncommittedRemainder() =
    runBlocking {
      val store = FakeLocationPointStore().apply { failOnInsertCall = 2 }
      val repository = LocationCollectionRepository(store)
      val fixes = (0..200).map { index -> fix(1_000L + index, elapsed = index.toLong()) }

      assertThrows(IllegalStateException::class.java) {
        runBlocking { repository.persist(fixes, DEVICE_ID, 0, 2_000) }
      }
      assertEquals(200, store.all().size)
      store.failOnInsertCall = null

      val retry = repository.persist(fixes, DEVICE_ID, 0, 2_000)

      assertEquals(1, retry.insertedCount)
      assertEquals(200, retry.duplicateCount)
      assertEquals(201, store.all().size)
    }

  @Test
  fun acknowledgementMarksOnlyAcceptedIdsAndRejectsUnknownIdsBeforeMutation() =
    runBlocking {
      val store = FakeLocationPointStore()
      val repository = LocationCollectionRepository(store)
      repository.persist((0..2).map { fix(1_000L + it, elapsed = it.toLong()) }, DEVICE_ID, 0, 2_000)
      val pending = repository.pendingBatch()

      val result = repository.acknowledge(pending.map { it.id }, listOf(pending.first().id), 3_000)
      assertEquals(1, result.markedSyncedCount)
      assertEquals(1, result.cleanedCount)
      assertEquals(2, repository.countPending())

      val unknown = generateUlid(4_000)
      assertThrows(IllegalArgumentException::class.java) {
        runBlocking { repository.acknowledge(pending.map { it.id }, listOf(unknown), 3_100) }
      }
      assertEquals(2, repository.countPending())
    }

  @Test
  fun syncedRowsLeftAtCrashBoundaryAreCleanedOnNextRunWithoutTouchingPendingRows() =
    runBlocking {
      val store = FakeLocationPointStore()
      val repository = LocationCollectionRepository(store)
      repository.persist((0..1).map { fix(1_000L + it, elapsed = it.toLong()) }, DEVICE_ID, 0, 2_000)
      val pending = repository.pendingBatch()
      store.failOnDeleteCall = 2

      assertThrows(IllegalStateException::class.java) {
        runBlocking {
          repository.acknowledge(pending.map { it.id }, listOf(pending.first().id), 3_000)
        }
      }
      assertEquals(1, store.all().count { it.syncStatus == LocationPointEntity.SYNCED })
      assertEquals(1, repository.countPending())

      assertEquals(1, repository.cleanupSynced())
      assertTrue(store.all().all { it.syncStatus == LocationPointEntity.SYNC_PENDING })
    }

  @Test
  fun rejectsDuplicateAndOutOfScopeAcknowledgements() =
    runBlocking {
      val store = FakeLocationPointStore()
      val repository = LocationCollectionRepository(store)
      repository.persist(listOf(fix(1_000, elapsed = 1)), DEVICE_ID, 0, 2_000)
      val id = repository.pendingBatch().single().id

      assertThrows(IllegalArgumentException::class.java) {
        runBlocking { repository.acknowledge(listOf(id), listOf(id, id), 3_000) }
      }
      assertThrows(IllegalArgumentException::class.java) {
        runBlocking { repository.acknowledge(emptyList(), listOf(id), 3_000) }
      }
      assertFalse(store.all().single().syncStatus == LocationPointEntity.SYNCED)
    }

  private fun fix(
    recordedAtMs: Long,
    elapsed: Long = 10,
    latitude: Double = 35.0,
    longitude: Double = 139.0,
    accuracy: Double? = 8.0,
    altitude: Double? = 15.0,
    speed: Double? = 1.0,
  ) = LocationFix(
    recordedAtMs = recordedAtMs,
    latitude = latitude,
    longitude = longitude,
    accuracyM = accuracy,
    altitudeM = altitude,
    speedMps = speed,
    elapsedRealtimeNanos = elapsed,
  )

  private class FakeLocationPointStore : LocationPointStore {
    private val pointsById = linkedMapOf<String, LocationPointEntity>()
    var failOnInsertCall: Int? = null
    var failOnDeleteCall: Int? = null
    private var insertCalls = 0
    private var deleteCalls = 0

    override suspend fun insertBatchIfAbsent(points: List<LocationPointEntity>): List<LocationPointInsertResult> {
      insertCalls += 1
      if (insertCalls == failOnInsertCall) error("synthetic process crash before transaction commit")
      val staged = pointsById.toMutableMap()
      val outcomes =
        points.map { point ->
          val existing =
            staged.values.firstOrNull { it.sourceFingerprint == point.sourceFingerprint }
              ?: staged[point.id]
          if (existing != null) {
            LocationPointInsertResult(inserted = false, existing = existing)
          } else {
            staged[point.id] = point
            LocationPointInsertResult(inserted = true)
          }
        }
      pointsById.clear()
      pointsById.putAll(staged)
      return outcomes
    }

    override suspend fun getPendingBatch(limit: Int): List<LocationPointEntity> =
      pointsById.values
        .filter { it.syncStatus == LocationPointEntity.SYNC_PENDING }
        .sortedWith(
          compareBy<LocationPointEntity> {
            it.recordedAtMs
          }.thenBy { it.elapsedRealtimeNanos }.thenBy { it.latitude }.thenBy { it.longitude }.thenBy { it.id },
        ).take(limit)

    override suspend fun countPending(): Int = pointsById.values.count { it.syncStatus == LocationPointEntity.SYNC_PENDING }

    override suspend fun markPendingAsSynced(
      ids: List<String>,
      syncedAtMs: Long,
    ): Int {
      var changed = 0
      ids.forEach { id ->
        val point = pointsById[id]
        if (point?.syncStatus == LocationPointEntity.SYNC_PENDING) {
          pointsById[id] = point.copy(syncStatus = LocationPointEntity.SYNCED, syncedAtMs = syncedAtMs)
          changed += 1
        }
      }
      return changed
    }

    override suspend fun deleteSyncedBatch(limit: Int): Int {
      deleteCalls += 1
      if (deleteCalls == failOnDeleteCall) error("synthetic process crash after ACK transaction")
      val ids =
        pointsById.values
          .filter { it.syncStatus == LocationPointEntity.SYNCED }
          .take(limit)
          .map { it.id }
      ids.forEach(pointsById::remove)
      return ids.size
    }

    fun all(): List<LocationPointEntity> = pointsById.values.toList()
  }

  private companion object {
    val DEVICE_ID = generateUlid(1_800_000_000_000)
  }
}
