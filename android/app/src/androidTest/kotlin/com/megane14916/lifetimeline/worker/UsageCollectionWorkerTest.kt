package com.megane14916.lifetimeline.worker

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.megane14916.lifetimeline.collector.PackageLabelResolver
import com.megane14916.lifetimeline.collector.RawUsageEvent
import com.megane14916.lifetimeline.collector.UsageAccessChecker
import com.megane14916.lifetimeline.collector.UsageAccessStateProvider
import com.megane14916.lifetimeline.collector.UsageEventMapper
import com.megane14916.lifetimeline.collector.UsageEventsCollector
import com.megane14916.lifetimeline.collector.UsageEventsSource
import com.megane14916.lifetimeline.data.WorkerDependencies
import com.megane14916.lifetimeline.data.local.LifeTimelineDatabase
import com.megane14916.lifetimeline.data.preferences.AppPreferences
import com.megane14916.lifetimeline.repository.BackgroundExecutionCoordinator
import com.megane14916.lifetimeline.repository.CollectionCoordinator
import com.megane14916.lifetimeline.repository.CollectionRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class UsageCollectionWorkerTest {
  private lateinit var context: Context
  private lateinit var database: LifeTimelineDatabase
  private lateinit var preferences: AppPreferences

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    database =
      Room
        .inMemoryDatabaseBuilder(context, LifeTimelineDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    preferences = AppPreferences.forTest(context, "usage-worker-${UUID.randomUUID()}")
  }

  @After
  fun tearDown() {
    database.close()
  }

  @Test
  fun successfulCollectionPersistsSessionsAndTriggersConfiguredSync() =
    runBlocking {
      var triggerCount = 0
      val coordinator = coordinator(granted = true, source = pairedUsageEvents())

      val firstResult = worker(coordinator, "https://pc.example.test/", { triggerCount++ }).doWork()
      val secondResult = worker(coordinator, "https://pc.example.test/", { triggerCount++ }).doWork()

      assertEquals(ListenableWorker.Result.success(), firstResult)
      assertEquals(ListenableWorker.Result.success(), secondResult)
      assertEquals(1, database.androidAppSessionDao().getPending().size)
      assertEquals(2, triggerCount)
    }

  @Test
  fun permissionDeniedDoesNotAdvanceCursorButStillTriggersConfiguredSync() =
    runBlocking {
      var triggerCount = 0
      val coordinator = coordinator(granted = false, source = { error("UsageEvents must not be queried.") })

      val result = worker(coordinator, "https://pc.example.test/", { triggerCount++ }).doWork()

      assertEquals(ListenableWorker.Result.success(), result)
      assertNull(database.collectorStateDao().find("android_usage_stats_v1"))
      assertEquals(1, triggerCount)
      assertEquals("permission_denied", database.backgroundWorkStateDao().find(AutomaticSyncPolicy.COLLECTION_LEASE_KEY)?.lastResult)
    }

  @Test
  fun unavailableCollectionRetriesWithoutAdvancingCursor() =
    runBlocking {
      var triggerCount = 0
      val coordinator = coordinator(granted = true, source = { throw SecurityException("usage access unavailable") })

      val result = worker(coordinator, "https://pc.example.test/", { triggerCount++ }).doWork()
      val state = checkNotNull(database.backgroundWorkStateDao().find(AutomaticSyncPolicy.COLLECTION_LEASE_KEY))

      assertEquals(ListenableWorker.Result.retry(), result)
      assertNull(database.collectorStateDao().find("android_usage_stats_v1"))
      assertEquals("retry", state.lastResult)
      assertEquals("unavailable", state.lastErrorKind)
      assertEquals(1, triggerCount)
    }

  @Test
  fun missingEndpointDoesNotTriggerSync() =
    runBlocking {
      var triggerCount = 0
      val coordinator = coordinator(granted = true, source = { emptyList() })

      val result = worker(coordinator, endpoint = null, syncTrigger = { triggerCount++ }).doWork()

      assertEquals(ListenableWorker.Result.success(), result)
      assertEquals(0, triggerCount)
    }

  @Test
  fun activeLeaseSkipsCollectionAndReleasesNothingOwnedByWorker() =
    runBlocking {
      var sourceCalled = false
      val holder =
        BackgroundExecutionCoordinator(
          database = database,
          ownerGenerator = { "holder" },
          nowMs = { NOW_MS },
        )
      checkNotNull(holder.acquire(AutomaticSyncPolicy.COLLECTION_LEASE_KEY))
      val coordinator =
        coordinator(granted = true, source = {
          sourceCalled = true
          emptyList()
        })

      val result = worker(coordinator, "https://pc.example.test/").doWork()

      assertEquals(ListenableWorker.Result.success(), result)
      assertTrue(!sourceCalled)
      assertEquals("holder", database.backgroundWorkStateDao().find(AutomaticSyncPolicy.COLLECTION_LEASE_KEY)?.leaseOwner)
    }

  private fun worker(
    collectionCoordinator: CollectionCoordinator,
    endpoint: String?,
    syncTrigger: () -> Unit = {},
  ): UsageCollectionWorker {
    val dependencies =
      WorkerDependencies(
        collectionCoordinatorFactory = { collectionCoordinator },
        syncRepositoryFactory = { _, _ -> error("Sync repository must not be created by collection worker.") },
        backgroundExecutionCoordinatorFactory = {
          BackgroundExecutionCoordinator(
            database = database,
            ownerGenerator = { "worker" },
            nowMs = { NOW_MS },
          )
        },
        pcBaseUrlProvider = { endpoint },
        syncTrigger = syncTrigger,
      )
    val factory = LifeTimelineWorkerFactory(dependencies)
    return TestListenableWorkerBuilder
      .from(context, UsageCollectionWorker::class.java)
      .setWorkerFactory(factory)
      .build(UsageCollectionWorker::class.java)
  }

  private fun coordinator(
    granted: Boolean,
    source: () -> List<RawUsageEvent>?,
  ): CollectionCoordinator {
    val accessChecker = UsageAccessChecker(UsageAccessStateProvider { granted })
    val collector =
      UsageEventsCollector(
        accessChecker = accessChecker,
        source = UsageEventsSource { _, _ -> source() },
        mapper = UsageEventMapper(apiLevel = 29),
        labelResolver = PackageLabelResolver { packageName -> packageName },
        selfPackageName = context.packageName,
      )
    return CollectionCoordinator(
      database = database,
      preferences = preferences,
      accessChecker = accessChecker,
      collector = collector,
      collectionRepository = CollectionRepository(database),
      nowMs = { NOW_MS },
    )
  }

  private fun pairedUsageEvents(): () -> List<RawUsageEvent> =
    {
      listOf(
        RawUsageEvent(
          timestampMs = NOW_MS - 1_000,
          packageName = "com.example.app",
          className = "ExampleActivity",
          eventType = android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED,
        ),
        RawUsageEvent(
          timestampMs = NOW_MS - 500,
          packageName = "com.example.app",
          className = "ExampleActivity",
          eventType = android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED,
        ),
      )
    }

  private companion object {
    const val NOW_MS = 10_000_000L
  }
}
