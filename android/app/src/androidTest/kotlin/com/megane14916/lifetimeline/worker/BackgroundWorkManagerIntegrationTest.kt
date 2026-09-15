package com.megane14916.lifetimeline.worker

import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.testing.TestDriver
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.megane14916.lifetimeline.collector.FusedLocationUpdatesAdapter
import com.megane14916.lifetimeline.collector.LocationPermissionChecker
import com.megane14916.lifetimeline.collector.LocationPermissionStateProvider
import com.megane14916.lifetimeline.collector.LocationRequestController
import com.megane14916.lifetimeline.collector.locationPendingIntent
import com.megane14916.lifetimeline.collector.locationUpdatesReceiverIntent
import com.megane14916.lifetimeline.data.local.LifeTimelineDatabase
import com.megane14916.lifetimeline.data.preferences.AppSettings
import com.megane14916.lifetimeline.location.handleLocationUpdateIntent
import com.megane14916.lifetimeline.repository.LocationCollectionRepository
import com.megane14916.lifetimeline.repository.LocationUpdateProcessor
import com.megane14916.lifetimeline.repository.RoomLocationPointStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackgroundWorkManagerIntegrationTest {
  @Before
  fun clearExistingWork() {
    workManager.cancelAllWork().result.get()
    workManager.pruneWork().result.get()
  }

  @Test
  fun repeatedSchedulingKeepsOnePeriodicAndOneUniqueSyncWork() {
    val scheduler = scheduler()

    scheduler.ensureScheduled()
    scheduler.ensureScheduled()

    val collection = scheduler.collectionWorkInfos().get()
    val sync = scheduler.syncWorkInfos().get()

    logWorkInfo("unique-schedule collection", collection.singleOrNull())
    logWorkInfo("unique-schedule sync", sync.singleOrNull())
    assertEquals(1, collection.size)
    assertEquals(1, sync.size)
    assertEquals(1, scheduler.photoCollectionWorkInfos().get().size)
    assertEquals(1, scheduler.photoSyncWorkInfos().get().size)
  }

  @Test
  fun testDriverRunsPeriodicWorkWhenPeriodDelayIsMet() {
    val scheduler =
      BackgroundWorkScheduler(
        workManager = workManager,
        collectionWorkerClass = SuccessfulWorker::class.java,
        syncWorkerClass = SuccessfulWorker::class.java,
      )
    val request = scheduler.collectionWorkRequest()

    workManager.enqueue(request).result.get()
    testDriver.setPeriodDelayMet(request.id)

    val info = checkNotNull(workManager.getWorkInfoById(request.id).get())
    logWorkInfo("periodic-after-delay", info)
    assertEquals(WorkInfo.State.ENQUEUED, info.state)
    assertEquals(0, info.runAttemptCount)
  }

  @Test
  fun testDriverSatisfiesConstraintsAndRetryLeavesWorkEnqueued() {
    val scheduler =
      BackgroundWorkScheduler(
        workManager = workManager,
        collectionWorkerClass = SuccessfulWorker::class.java,
        syncWorkerClass = RetryingWorker::class.java,
      )
    val request = scheduler.syncWorkRequest()

    workManager.enqueue(request).result.get()
    val beforeConstraints = checkNotNull(workManager.getWorkInfoById(request.id).get())
    logWorkInfo("sync-before-constraints", beforeConstraints)
    assertEquals(WorkInfo.State.ENQUEUED, beforeConstraints.state)

    testDriver.setAllConstraintsMet(request.id)

    val afterRetry = checkNotNull(workManager.getWorkInfoById(request.id).get())
    logWorkInfo("sync-after-retry", afterRetry)
    assertEquals(WorkInfo.State.ENQUEUED, afterRetry.state)
    assertEquals(1, afterRetry.runAttemptCount)
    assertEquals(
      AutomaticSyncPolicy.SYNC_BACKOFF_MINUTES * 60 * 1_000L,
      request.workSpec.backoffDelayDuration,
    )
  }

  @Test
  fun keepPolicyDoesNotReplaceAnExistingSyncRequest() {
    val scheduler =
      BackgroundWorkScheduler(
        workManager = workManager,
        collectionWorkerClass = SuccessfulWorker::class.java,
        syncWorkerClass = RetryingWorker::class.java,
      )
    val first = scheduler.syncWorkRequest()
    val second = scheduler.syncWorkRequest()

    workManager
      .enqueueUniqueWork(
        AutomaticSyncPolicy.SYNC_WORK_NAME,
        ExistingWorkPolicy.KEEP,
        first,
      ).result
      .get()
    workManager
      .enqueueUniqueWork(
        AutomaticSyncPolicy.SYNC_WORK_NAME,
        ExistingWorkPolicy.KEEP,
        second,
      ).result
      .get()

    val info = scheduler.syncWorkInfos().get()
    logWorkInfo("keep-policy", info.singleOrNull())
    assertEquals(1, info.size)
    assertEquals(first.id, info.single().id)
  }

  @Test
  fun photoWorkersUseIndependentConstraintsBackoffAndNames() {
    val scheduler = scheduler()
    val collection = scheduler.photoCollectionWorkRequest()
    val sync = scheduler.photoSyncWorkRequest()

    assertEquals(15L * 60 * 1_000L, collection.workSpec.intervalDuration)
    assertEquals(5L * 60 * 1_000L, collection.workSpec.flexDuration)
    assertEquals(
      Constraints
        .Builder()
        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
        .setRequiresBatteryNotLow(true)
        .setRequiresStorageNotLow(true)
        .build(),
      collection.workSpec.constraints,
    )
    assertEquals(
      Constraints
        .Builder()
        .setRequiredNetworkType(NetworkType.UNMETERED)
        .setRequiresBatteryNotLow(true)
        .setRequiresStorageNotLow(true)
        .build(),
      sync.workSpec.constraints,
    )
    assertEquals(30L * 60 * 1_000L, sync.workSpec.backoffDelayDuration)
    assertEquals(0, workManager.getWorkInfosForUniqueWork(PhotoWorkPolicy.SYNC_WORK_NAME).get().size)
  }

  @Test
  fun photoSyncRemainsEnqueuedUntilAllUnmeteredAndDeviceConstraintsAreMet() {
    val scheduler =
      BackgroundWorkScheduler(
        workManager = workManager,
        collectionWorkerClass = SuccessfulWorker::class.java,
        photoSyncWorkerClass = SuccessfulWorker::class.java,
      )
    val request = scheduler.photoSyncWorkRequest()

    workManager.enqueue(request).result.get()
    assertEquals(
      WorkInfo.State.ENQUEUED,
      checkNotNull(workManager.getWorkInfoById(request.id).get()).state,
    )

    testDriver.setAllConstraintsMet(request.id)

    assertEquals(
      WorkInfo.State.SUCCEEDED,
      checkNotNull(workManager.getWorkInfoById(request.id).get()).state,
    )
  }

  @Test
  fun fakeFusedBatchFlowsThroughReceiverIntoRoomAndUniqueLocationSyncWork() =
    runBlocking {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val adapter = RecordingFusedLocationUpdatesAdapter()
      val permissionChecker = grantedLocationPermissionChecker()
      val controller = LocationRequestController(context, permissionChecker, adapter)

      controller.register()
      controller.register()

      assertEquals(2, adapter.requests.size)
      assertEquals(adapter.pendingIntents[0], adapter.pendingIntents[1])
      assertEquals(0, adapter.removedPendingIntents.size)
      val receiverIntent = locationUpdatesReceiverIntent(context)
      assertEquals(locationPendingIntent(context), adapter.pendingIntents[0])
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        assertFalse("Fused Location must be able to attach LocationResult extras", adapter.pendingIntents[0].isImmutable)
      }
      assertEquals(
        "com.megane14916.lifetimeline.location.LocationUpdatesReceiver",
        receiverIntent.component?.className,
      )
      assertEquals("${context.packageName}.LOCATION_UPDATES", receiverIntent.action)
      assertEquals("lifetimeline://${context.packageName}/location-updates/v1", receiverIntent.data.toString())

      controller.unregister()
      assertEquals(listOf(adapter.pendingIntents[0]), adapter.removedPendingIntents)

      val startedAtMs = 1_800_000_000_000L
      val syntheticLocation =
        Location("synthetic-fused")
          .apply {
            time = startedAtMs + 5_000
            latitude = 35.0
            longitude = 139.0
            accuracy = 12.0f
            elapsedRealtimeNanos = 123_456_789L
          }
      val receivedIntent =
        receiverIntent.putExtra(
          EXTRA_LOCATION_RESULT,
          LocationResult.create(listOf(syntheticLocation)),
        )
      assertTrue(LocationResult.hasResult(receivedIntent))
      assertNotNull(LocationResult.extractResult(receivedIntent))

      val database =
        Room
          .inMemoryDatabaseBuilder(context, LifeTimelineDatabase::class.java)
          .allowMainThreadQueries()
          .addCallback(LifeTimelineDatabase.PHOTO_INTEGRITY_CALLBACK)
          .build()
      try {
        val processor =
          LocationUpdateProcessor(
            settingsProvider = {
              AppSettings(
                deviceId = DEVICE_ID,
                pcBaseUrl = null,
                lastCollectionAtMs = null,
                lastSyncAtMs = null,
                locationCollectionEnabled = true,
                locationCollectionStartedAtMs = startedAtMs,
              )
            },
            permissionChecker = permissionChecker,
            repository = LocationCollectionRepository(RoomLocationPointStore(database)),
            deviceIdProvider = { DEVICE_ID },
            nowMs = { startedAtMs + 10_000 },
          )
        val scheduler = BackgroundWorkScheduler(workManager)

        assertEquals(
          0,
          handleLocationUpdateIntent(
            packageName = context.packageName,
            intent = Intent(receivedIntent).setAction("unexpected-action"),
            processor = processor,
            enqueueLocationSync = scheduler::enqueueLocationSync,
          ),
        )
        assertEquals(0, database.locationPointDao().countPending())

        assertEquals(
          1,
          handleLocationUpdateIntent(
            packageName = context.packageName,
            intent = receivedIntent,
            processor = processor,
            enqueueLocationSync = scheduler::enqueueLocationSync,
          ),
        )
        val stored = database.locationPointDao().getPendingBatch(10)
        assertEquals(1, stored.size)
        assertEquals(startedAtMs + 5_000, stored.single().recordedAtMs)
        assertEquals(1, scheduler.locationSyncWorkInfos().get().size)
        assertEquals(0, scheduler.syncWorkInfos().get().size)
        assertEquals(0, scheduler.photoSyncWorkInfos().get().size)

        val locationSyncWorkId =
          scheduler
            .locationSyncWorkInfos()
            .get()
            .single()
            .id
        assertEquals(
          0,
          handleLocationUpdateIntent(
            packageName = context.packageName,
            intent = receivedIntent,
            processor = processor,
            enqueueLocationSync = scheduler::enqueueLocationSync,
          ),
        )
        assertEquals(
          locationSyncWorkId,
          scheduler
            .locationSyncWorkInfos()
            .get()
            .single()
            .id,
        )
        assertEquals(1, database.locationPointDao().countPending())
      } finally {
        database.close()
      }
    }

  @Test
  fun immediatePhotoScanQueuesFollowUpWithoutReplacingThePeriodicPhotoSchedule() {
    val scheduler = scheduler()
    scheduler.ensurePhotoCollectionScheduled()
    val before =
      scheduler
        .photoCollectionWorkInfos()
        .get()
        .single()
        .id

    scheduler.enqueuePhotoCollectionNow()
    scheduler.enqueuePhotoCollectionNow()

    val after =
      scheduler
        .photoCollectionWorkInfos()
        .get()
        .single()
        .id
    val immediate = workManager.getWorkInfosForUniqueWork(PhotoWorkPolicy.IMMEDIATE_COLLECTION_WORK_NAME).get()
    assertEquals(before, after)
    assertEquals(2, immediate.size)
    assertEquals(2, immediate.map { it.id }.toSet().size)
    assertEquals(setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED), immediate.map { it.state }.toSet())
  }

  private fun scheduler(): BackgroundWorkScheduler = BackgroundWorkScheduler(workManager)

  private fun grantedLocationPermissionChecker() =
    LocationPermissionChecker(
      apiLevel = Build.VERSION.SDK_INT,
      permissionStateProvider = LocationPermissionStateProvider { true },
      locationServicesEnabledProvider = { true },
    )

  private fun logWorkInfo(
    label: String,
    info: WorkInfo?,
  ) {
    Log.i(DIAGNOSTIC_TAG, "$label state=${info?.state} runAttemptCount=${info?.runAttemptCount}")
  }

  class SuccessfulWorker(
    context: Context,
    parameters: WorkerParameters,
  ) : Worker(context, parameters) {
    override fun doWork(): Result = Result.success()
  }

  class RetryingWorker(
    context: Context,
    parameters: WorkerParameters,
  ) : Worker(context, parameters) {
    override fun doWork(): Result = Result.retry()
  }

  private class RecordingFusedLocationUpdatesAdapter : FusedLocationUpdatesAdapter {
    val requests = mutableListOf<LocationRequest>()
    val pendingIntents = mutableListOf<android.app.PendingIntent>()
    val removedPendingIntents = mutableListOf<android.app.PendingIntent>()

    override suspend fun requestLocationUpdates(
      request: LocationRequest,
      pendingIntent: android.app.PendingIntent,
    ) {
      requests += request
      pendingIntents += pendingIntent
    }

    override suspend fun removeLocationUpdates(pendingIntent: android.app.PendingIntent) {
      removedPendingIntents += pendingIntent
    }
  }

  private companion object {
    const val DIAGNOSTIC_TAG = "LifeTimelineWorkInfo"
    const val DEVICE_ID = "01K00000000000000000000001"
    const val EXTRA_LOCATION_RESULT = "com.google.android.gms.location.EXTRA_LOCATION_RESULT"

    lateinit var workManager: WorkManager
    lateinit var testDriver: TestDriver

    @JvmStatic
    @BeforeClass
    fun initializeWorkManager() {
      val context = ApplicationProvider.getApplicationContext<Context>()
      WorkManagerTestInitHelper.initializeTestWorkManager(context)
      workManager = WorkManager.getInstance(context)
      testDriver = checkNotNull(WorkManagerTestInitHelper.getTestDriver(context))
    }
  }
}
