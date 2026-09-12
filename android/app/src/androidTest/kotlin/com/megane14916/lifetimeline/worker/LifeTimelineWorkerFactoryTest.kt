package com.megane14916.lifetimeline.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.megane14916.lifetimeline.data.WorkerDependencies
import com.megane14916.lifetimeline.data.local.AndroidAppSessionEntity
import com.megane14916.lifetimeline.data.remote.SyncApi
import com.megane14916.lifetimeline.data.remote.SyncAppSessionsRequest
import com.megane14916.lifetimeline.data.remote.SyncAppSessionsResponse
import com.megane14916.lifetimeline.data.remote.SyncDeviceDto
import com.megane14916.lifetimeline.repository.PendingSessionStore
import com.megane14916.lifetimeline.repository.SyncRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Response

@RunWith(AndroidJUnit4::class)
class LifeTimelineWorkerFactoryTest {
  @Test
  fun createsBothPhotoWorkerTypesFromTheirStableNames() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val dependencies =
      WorkerDependencies(
        collectionCoordinatorFactory = { error("Collection is not used in this test.") },
        syncRepositoryFactory = { _, _ -> error("Sync is not used in this test.") },
      )
    val factory = LifeTimelineWorkerFactory(dependencies)

    val collection =
      TestListenableWorkerBuilder
        .from(context, PhotoCollectionWorker::class.java)
        .setWorkerFactory(factory)
        .build(PhotoCollectionWorker::class.java)
    val sync =
      TestListenableWorkerBuilder
        .from(context, PhotoSyncWorker::class.java)
        .setWorkerFactory(factory)
        .build(PhotoSyncWorker::class.java)

    assertEquals(PhotoCollectionWorker::class.java, collection.javaClass)
    assertEquals(PhotoSyncWorker::class.java, sync.javaClass)
  }

  @Test
  fun createsCoroutineWorkerWithFakeRepositoryDependencies() =
    runBlocking {
      val context = ApplicationProvider.getApplicationContext<Context>()
      var syncFactoryCalls = 0
      val fakeSyncRepository = fakeSyncRepository()
      val dependencies =
        WorkerDependencies(
          collectionCoordinatorFactory = { error("Collection is not used by this worker.") },
          syncRepositoryFactory = { _, _ ->
            syncFactoryCalls += 1
            fakeSyncRepository
          },
        )
      val factory =
        LifeTimelineWorkerFactory(
          dependencies = dependencies,
          creators =
            mapOf(
              FakeCoroutineWorker::class.java.name to
                WorkerCreator { appContext, workerParameters, workerDependencies ->
                  FakeCoroutineWorker(appContext, workerParameters, workerDependencies)
                },
            ),
        )

      val worker =
        TestListenableWorkerBuilder
          .from(context, FakeCoroutineWorker::class.java)
          .setWorkerFactory(factory)
          .build(FakeCoroutineWorker::class.java)
      val result = worker.doWork()

      assertEquals(ListenableWorker.Result.success(), result)
      assertEquals(1, syncFactoryCalls)
      assertSame(fakeSyncRepository, worker.repositoryUsed)
    }

  private fun fakeSyncRepository() =
    SyncRepository(
      pendingStore =
        object : PendingSessionStore {
          override suspend fun getPendingSessions(): List<AndroidAppSessionEntity> = emptyList()

          override suspend fun markAcceptedAsSynced(
            ids: List<String>,
            syncedAtMs: Long,
          ): Int = 0

          override suspend fun countPending(): Int = 0
        },
      appProvider = { emptyList() },
      syncApi =
        object : SyncApi {
          override suspend fun syncAppSessions(request: SyncAppSessionsRequest): Response<SyncAppSessionsResponse> =
            error("No HTTP request expected.")
        },
      device = SyncDeviceDto(id = "device", name = "Test", platform = "android"),
    )

  private class FakeCoroutineWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
    private val dependencies: WorkerDependencies,
  ) : CoroutineWorker(appContext, workerParameters) {
    lateinit var repositoryUsed: SyncRepository

    override suspend fun doWork(): Result {
      repositoryUsed = dependencies.syncRepositoryFactory(applicationContext, "https://example.test/").also { it.syncAll() }
      return Result.success()
    }
  }
}
