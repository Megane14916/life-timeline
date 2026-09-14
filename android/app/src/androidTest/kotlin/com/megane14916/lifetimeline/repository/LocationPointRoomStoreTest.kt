package com.megane14916.lifetimeline.repository

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megane14916.lifetimeline.data.local.LifeTimelineDatabase
import com.megane14916.lifetimeline.data.local.LocationPointEntity
import com.megane14916.lifetimeline.domain.generateUlid
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocationPointRoomStoreTest {
  private lateinit var database: LifeTimelineDatabase
  private lateinit var repository: LocationCollectionRepository

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    database =
      Room
        .inMemoryDatabaseBuilder(context, LifeTimelineDatabase::class.java)
        .addCallback(LifeTimelineDatabase.PHOTO_INTEGRITY_CALLBACK)
        .allowMainThreadQueries()
        .build()
    repository = LocationCollectionRepository(RoomLocationPointStore(database))
  }

  @After
  fun tearDown() {
    database.close()
  }

  @Test
  fun freshVersionFiveDatabaseStoresOrderedPendingPointsAndConditionallyCleansAcknowledgements() =
    runBlocking {
      val result =
        repository.persist(
          fixes = listOf(fix(3_000, 30), fix(1_000, 10), fix(2_000, 20)),
          deviceId = generateUlid(1_800_000_000_000),
          collectionStartedAtMs = 0,
          nowMs = 5_000,
        )
      val pending = repository.pendingBatch()

      assertEquals(3, result.insertedCount)
      assertEquals(listOf(1_000L, 2_000L, 3_000L), pending.map { it.recordedAtMs })
      assertEquals(3, repository.countPending())

      val ack = repository.acknowledge(pending.map { it.id }, listOf(pending[1].id), 6_000)

      assertEquals(1, ack.markedSyncedCount)
      assertEquals(1, ack.cleanedCount)
      assertEquals(2, repository.countPending())
      assertEquals(5_000L, repository.latestReceivedAt())
      assertEquals(null, database.locationPointDao().findById(pending[1].id))
      assertNotNull(database.locationPointDao().findById(pending[0].id))
      assertNotNull(database.locationPointDao().findById(pending[2].id))
    }

  @Test
  fun sqliteTriggersRejectInvalidCoordinatesEvenWhenRepositoryIsBypassed() {
    val invalid =
      LocationPointEntity(
        id = generateUlid(1_800_000_000_000),
        recordedAtMs = 1,
        latitude = 91.0,
        longitude = 0.0,
        elapsedRealtimeNanos = 1,
        sourceFingerprint = "invalid-coordinate",
        receivedAtMs = 1,
      )

    assertThrows(SQLiteConstraintException::class.java) {
      runBlocking { database.locationPointDao().insertAllIfAbsent(listOf(invalid)) }
    }
    assertTrue(runBlocking { database.locationPointDao().getPendingBatch(10).isEmpty() })
  }

  private fun fix(
    recordedAtMs: Long,
    elapsed: Long,
  ) = LocationFix(
    recordedAtMs = recordedAtMs,
    latitude = 35.0,
    longitude = 139.0,
    accuracyM = 8.0,
    altitudeM = 15.0,
    speedMps = 1.0,
    elapsedRealtimeNanos = elapsed,
  )
}
