package com.megane14916.lifetimeline.worker

import android.content.Context
import android.util.Log
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
import org.junit.Assert.assertEquals
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
  fun immediatePhotoScanDoesNotReplaceThePeriodicPhotoSchedule() {
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
    assertEquals(1, immediate.size)
  }

  private fun scheduler(): BackgroundWorkScheduler = BackgroundWorkScheduler(workManager)

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

  private companion object {
    const val DIAGNOSTIC_TAG = "LifeTimelineWorkInfo"

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
