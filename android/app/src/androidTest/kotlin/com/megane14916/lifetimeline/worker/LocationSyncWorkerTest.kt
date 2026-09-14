package com.megane14916.lifetimeline.worker

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.megane14916.lifetimeline.data.WorkerDependencies
import com.megane14916.lifetimeline.data.local.LifeTimelineDatabase
import com.megane14916.lifetimeline.data.remote.LocationSyncDevice
import com.megane14916.lifetimeline.data.remote.LocationSyncFailureKind
import com.megane14916.lifetimeline.data.remote.LocationSyncRemoteException
import com.megane14916.lifetimeline.data.remote.LocationSyncResponse
import com.megane14916.lifetimeline.data.remote.LocationSyncUploader
import com.megane14916.lifetimeline.domain.generateUlid
import com.megane14916.lifetimeline.repository.BackgroundExecutionCoordinator
import com.megane14916.lifetimeline.repository.LocationCollectionRepository
import com.megane14916.lifetimeline.repository.LocationFix
import com.megane14916.lifetimeline.repository.LocationSyncRepository
import com.megane14916.lifetimeline.repository.RoomLocationPointStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class LocationSyncWorkerTest {
  private lateinit var context: Context
  private lateinit var database: LifeTimelineDatabase
  private lateinit var collectionRepository: LocationCollectionRepository

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    database =
      Room
        .inMemoryDatabaseBuilder(context, LifeTimelineDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    collectionRepository = LocationCollectionRepository(RoomLocationPointStore(database))
  }

  @After
  fun tearDown() {
    database.close()
  }

  @Test
  fun acceptedPointsAreCleanedAndIndependentLeaseRecordsSuccess() =
    runBlocking {
      seedPoint()
      val uploader =
        LocationSyncUploader { request ->
          LocationSyncResponse(schemaVersion = 1, accepted = request.locations.map { it.id })
        }

      val result = worker(repository(uploader), "https://pc.example.test/").doWork()
      val state = checkNotNull(database.backgroundWorkStateDao().find(LocationWorkPolicy.SYNC_LEASE_KEY))

      assertEquals(ListenableWorker.Result.success(), result)
      assertEquals(0, collectionRepository.countPending())
      assertEquals("success", state.lastResult)
      assertEquals(null, state.lastErrorKind)
      assertEquals(null, state.leaseOwner)
    }

  @Test
  fun networkFailureIsRetriedAndPendingPointIsRetained() =
    runBlocking {
      seedPoint()
      val uploader = LocationSyncUploader { throw IOException("offline") }

      val result = worker(repository(uploader), "https://pc.example.test/").doWork()
      val state = checkNotNull(database.backgroundWorkStateDao().find(LocationWorkPolicy.SYNC_LEASE_KEY))

      assertEquals(ListenableWorker.Result.retry(), result)
      assertEquals(1, collectionRepository.countPending())
      assertEquals("retry", state.lastResult)
      assertEquals("network", state.lastErrorKind)
    }

  @Test
  fun permanentContractFailureDoesNotRetryOrCleanPoints() =
    runBlocking {
      seedPoint()
      val uploader =
        LocationSyncUploader {
          throw LocationSyncRemoteException(LocationSyncFailureKind.PROTOCOL, retryable = false)
        }

      val result = worker(repository(uploader), "https://pc.example.test/").doWork()
      val state = checkNotNull(database.backgroundWorkStateDao().find(LocationWorkPolicy.SYNC_LEASE_KEY))

      assertEquals(ListenableWorker.Result.failure(), result)
      assertEquals(1, collectionRepository.countPending())
      assertEquals("failure", state.lastResult)
      assertEquals("protocol", state.lastErrorKind)
    }

  @Test
  fun missingEndpointLeavesPendingPointAndRecordsConfigurationDiagnostic() =
    runBlocking {
      seedPoint()
      var factoryCalls = 0

      val result =
        worker(
          repository = null,
          endpoint = null,
          repositoryFactoryCalls = { factoryCalls += 1 },
        ).doWork()
      val state = checkNotNull(database.backgroundWorkStateDao().find(LocationWorkPolicy.SYNC_LEASE_KEY))

      assertEquals(ListenableWorker.Result.success(), result)
      assertEquals(0, factoryCalls)
      assertEquals(1, collectionRepository.countPending())
      assertEquals("configuration_required", state.lastResult)
    }

  private suspend fun seedPoint() {
    collectionRepository.persist(
      fixes = listOf(LocationFix(NOW_MS, 35.0, 139.0, 10.0, elapsedRealtimeNanos = 1_000L)),
      deviceId = generateUlid(NOW_MS),
      collectionStartedAtMs = 0,
      nowMs = NOW_MS,
    )
  }

  private fun repository(uploader: LocationSyncUploader) =
    LocationSyncRepository(
      collectionRepository = collectionRepository,
      uploader = uploader,
      device = LocationSyncDevice(generateUlid(NOW_MS), "Test Android", "android"),
      nowMs = { NOW_MS },
    )

  private fun worker(
    repository: LocationSyncRepository?,
    endpoint: String?,
    repositoryFactoryCalls: () -> Unit = {},
  ): LocationSyncWorker {
    val dependencies =
      WorkerDependencies(
        collectionCoordinatorFactory = { error("Not used by Location sync.") },
        syncRepositoryFactory = { _, _ -> error("Not used by Location sync.") },
        backgroundExecutionCoordinatorFactory = {
          BackgroundExecutionCoordinator(database, nowMs = { NOW_MS }, ownerGenerator = { "location-sync-worker" })
        },
        pcBaseUrlProvider = { endpoint },
        locationSyncRepositoryFactory = { _, _ ->
          repositoryFactoryCalls()
          checkNotNull(repository)
        },
      )
    return TestListenableWorkerBuilder
      .from(context, LocationSyncWorker::class.java)
      .setWorkerFactory(LifeTimelineWorkerFactory(dependencies))
      .build(LocationSyncWorker::class.java)
  }

  private companion object {
    const val NOW_MS = 10_000_000L
  }
}
