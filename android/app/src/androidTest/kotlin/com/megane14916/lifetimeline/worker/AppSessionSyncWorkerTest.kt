package com.megane14916.lifetimeline.worker

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.megane14916.lifetimeline.data.WorkerDependencies
import com.megane14916.lifetimeline.data.local.AndroidAppEntity
import com.megane14916.lifetimeline.data.local.AndroidAppSessionEntity
import com.megane14916.lifetimeline.data.local.LifeTimelineDatabase
import com.megane14916.lifetimeline.data.remote.SyncApi
import com.megane14916.lifetimeline.data.remote.SyncAppDto
import com.megane14916.lifetimeline.data.remote.SyncAppSessionsRequest
import com.megane14916.lifetimeline.data.remote.SyncAppSessionsResponse
import com.megane14916.lifetimeline.data.remote.SyncDeviceDto
import com.megane14916.lifetimeline.repository.BackgroundExecutionCoordinator
import com.megane14916.lifetimeline.repository.PendingSessionStore
import com.megane14916.lifetimeline.repository.SyncRepository
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Response
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AppSessionSyncWorkerTest {
  private lateinit var context: Context
  private lateinit var database: LifeTimelineDatabase

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    database =
      Room
        .inMemoryDatabaseBuilder(context, LifeTimelineDatabase::class.java)
        .allowMainThreadQueries()
        .build()
  }

  @After
  fun tearDown() {
    database.close()
  }

  @Test
  fun missingEndpointCompletesWithoutCreatingRepository() =
    runBlocking {
      val store = FakePendingSessionStore(listOf(session(1)))
      var repositoryFactoryCalls = 0

      val result =
        worker(
          repository = null,
          endpoint = null,
          repositoryFactoryCalls = { repositoryFactoryCalls++ },
        ).doWork()
      val state = checkNotNull(database.backgroundWorkStateDao().find(AutomaticSyncPolicy.SYNC_LEASE_KEY))

      assertEquals(ListenableWorker.Result.success(), result)
      assertEquals(0, repositoryFactoryCalls)
      assertEquals(1, store.pending.size)
      assertEquals("configuration_required", state.lastResult)
      assertNull(state.lastErrorKind)
    }

  @Test
  fun successfulSyncMarksAcceptedSessionsAndRecordsSuccess() =
    runBlocking {
      val store = FakePendingSessionStore(listOf(session(1)))
      val api = RecordingSyncApi { request -> successResponse(request) }
      var successAtMs: Long? = null

      val result =
        worker(
          repository = repository(store, api),
          endpoint = "https://pc.example.test/",
          syncSuccessRecorder = { successAtMs = it },
        ).doWork()
      val state = checkNotNull(database.backgroundWorkStateDao().find(AutomaticSyncPolicy.SYNC_LEASE_KEY))

      assertEquals(ListenableWorker.Result.success(), result)
      assertEquals(0, store.pending.size)
      assertNotNull(successAtMs)
      assertEquals("success", state.lastResult)
      assertNull(state.lastErrorKind)
      assertNull(state.leaseOwner)
    }

  @Test
  fun networkFailureRetriesAndKeepsPendingSessions() =
    runBlocking {
      val store = FakePendingSessionStore(listOf(session(1)))
      val api = RecordingSyncApi { throw IOException("offline") }

      val result = worker(repository(store, api), "https://pc.example.test/").doWork()
      val state = checkNotNull(database.backgroundWorkStateDao().find(AutomaticSyncPolicy.SYNC_LEASE_KEY))

      assertEquals(ListenableWorker.Result.retry(), result)
      assertEquals(1, store.pending.size)
      assertEquals("retry", state.lastResult)
      assertEquals("network", state.lastErrorKind)
    }

  @Test
  fun permanentHttpFailureDoesNotRetry() =
    runBlocking {
      val store = FakePendingSessionStore(listOf(session(1)))
      val api =
        RecordingSyncApi {
          Response.error(
            409,
            "conflict".toResponseBody("text/plain".toMediaType()),
          )
        }

      val result = worker(repository(store, api), "https://pc.example.test/").doWork()
      val state = checkNotNull(database.backgroundWorkStateDao().find(AutomaticSyncPolicy.SYNC_LEASE_KEY))

      assertEquals(ListenableWorker.Result.failure(), result)
      assertEquals(1, store.pending.size)
      assertEquals("failure", state.lastResult)
      assertEquals("server", state.lastErrorKind)
    }

  @Test
  fun batchBudgetRetriesAndNextRunResumesFromRemainingPending() =
    runBlocking {
      val store = FakePendingSessionStore((0..(AutomaticSyncPolicy.MAX_BATCHES_PER_RUN * 100)).map(::session))
      val api = RecordingSyncApi { request -> successResponse(request) }
      val repository = repository(store, api)

      val firstResult = worker(repository, "https://pc.example.test/").doWork()
      val pendingAfterFirstRun = store.pending.size
      val secondResult = worker(repository, "https://pc.example.test/").doWork()

      assertEquals(ListenableWorker.Result.retry(), firstResult)
      assertEquals(1, pendingAfterFirstRun)
      assertEquals(ListenableWorker.Result.success(), secondResult)
      assertEquals(0, store.pending.size)
      assertEquals(AutomaticSyncPolicy.MAX_BATCHES_PER_RUN + 1, api.requests.size)
    }

  private fun worker(
    repository: SyncRepository?,
    endpoint: String?,
    repositoryFactoryCalls: () -> Unit = {},
    syncSuccessRecorder: suspend (Long) -> Unit = {},
  ): AppSessionSyncWorker {
    val dependencies =
      WorkerDependencies(
        collectionCoordinatorFactory = { error("Collection coordinator must not be created by sync worker.") },
        syncRepositoryFactory = { _, _ ->
          repositoryFactoryCalls()
          checkNotNull(repository)
        },
        backgroundExecutionCoordinatorFactory = {
          BackgroundExecutionCoordinator(
            database = database,
            ownerGenerator = { "sync-worker" },
            nowMs = { NOW_MS },
          )
        },
        pcBaseUrlProvider = { endpoint },
        syncSuccessRecorder = syncSuccessRecorder,
      )
    val factory = LifeTimelineWorkerFactory(dependencies)
    return TestListenableWorkerBuilder
      .from(context, AppSessionSyncWorker::class.java)
      .setWorkerFactory(factory)
      .build(AppSessionSyncWorker::class.java)
  }

  private fun repository(
    store: FakePendingSessionStore,
    api: SyncApi,
  ) = SyncRepository(
    pendingStore = store,
    appProvider = { ids -> ids.map { SyncAppDto(it, "com.example.app", "Example App") } },
    syncApi = api,
    device = SyncDeviceDto("device-1", "Test Device", "android"),
    nowMs = { NOW_MS },
  )

  private fun successResponse(request: SyncAppSessionsRequest): Response<SyncAppSessionsResponse> =
    Response.success(
      SyncAppSessionsResponse(
        schemaVersion = 1,
        accepted = request.sessions.map { it.id },
      ),
    )

  private fun session(index: Int) =
    AndroidAppSessionEntity(
      id = "session-$index-${UUID.randomUUID()}",
      appId = "app-1",
      startedAtMs = NOW_MS + index,
      endedAtMs = NOW_MS + index + 100,
      durationMs = 100,
      source = "android_usage_stats",
      sourceKey = "source-$index-${UUID.randomUUID()}",
      syncStatus = "pending",
      collectedAtMs = NOW_MS,
    )

  private companion object {
    const val NOW_MS = 10_000_000L
  }
}

private class FakePendingSessionStore(
  initialSessions: List<AndroidAppSessionEntity>,
) : PendingSessionStore {
  val pending = initialSessions.toMutableList()

  override suspend fun getPendingSessions(): List<AndroidAppSessionEntity> = pending.toList()

  override suspend fun markAcceptedAsSynced(
    ids: List<String>,
    syncedAtMs: Long,
  ): Int {
    val accepted = pending.filter { it.id in ids }
    pending.removeAll { it.id in ids }
    return accepted.size
  }

  override suspend fun countPending(): Int = pending.size
}

private class RecordingSyncApi(
  private val responder: suspend (SyncAppSessionsRequest) -> Response<SyncAppSessionsResponse>,
) : SyncApi {
  val requests = mutableListOf<SyncAppSessionsRequest>()

  override suspend fun syncAppSessions(request: SyncAppSessionsRequest): Response<SyncAppSessionsResponse> {
    requests += request
    return responder(request)
  }
}
