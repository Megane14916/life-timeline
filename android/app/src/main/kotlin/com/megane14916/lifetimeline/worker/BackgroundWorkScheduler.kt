package com.megane14916.lifetimeline.worker

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.TimeUnit

/** Owns the persistent WorkManager contracts used by automatic collection and sync. */
class BackgroundWorkScheduler(
  private val workManager: WorkManager,
  private val collectionWorkerClass: Class<out ListenableWorker> = UsageCollectionWorker::class.java,
  private val syncWorkerClass: Class<out ListenableWorker> = AppSessionSyncWorker::class.java,
) {
  /** Ensures the periodic collection and pending-session sync work both exist. */
  fun ensureScheduled() {
    workManager.enqueueUniquePeriodicWork(
      AutomaticSyncPolicy.COLLECTION_WORK_NAME,
      ExistingPeriodicWorkPolicy.UPDATE,
      collectionWorkRequest(),
    )
    enqueueSync()
  }

  /** Enqueues a sync without copying the endpoint into persistent WorkRequest input data. */
  fun enqueueSync() {
    workManager.enqueueUniqueWork(
      AutomaticSyncPolicy.SYNC_WORK_NAME,
      ExistingWorkPolicy.KEEP,
      syncWorkRequest(),
    )
  }

  /** Returns the WorkManager records for the unique periodic collection work. */
  fun collectionWorkInfos(): ListenableFuture<List<WorkInfo>> =
    workManager.getWorkInfosForUniqueWork(AutomaticSyncPolicy.COLLECTION_WORK_NAME)

  /** Returns the WorkManager records for the unique sync work. */
  fun syncWorkInfos(): ListenableFuture<List<WorkInfo>> = workManager.getWorkInfosForUniqueWork(AutomaticSyncPolicy.SYNC_WORK_NAME)

  internal fun collectionWorkRequest(): PeriodicWorkRequest =
    PeriodicWorkRequest
      .Builder(
        collectionWorkerClass,
        AutomaticSyncPolicy.COLLECTION_INTERVAL_MINUTES,
        TimeUnit.MINUTES,
        AutomaticSyncPolicy.COLLECTION_FLEX_MINUTES,
        TimeUnit.MINUTES,
      ).build()

  internal fun syncWorkRequest(): OneTimeWorkRequest =
    OneTimeWorkRequest
      .Builder(syncWorkerClass)
      .setConstraints(
        Constraints
          .Builder()
          .setRequiredNetworkType(NetworkType.CONNECTED)
          .setRequiresBatteryNotLow(true)
          .build(),
      ).setBackoffCriteria(
        BackoffPolicy.EXPONENTIAL,
        AutomaticSyncPolicy.SYNC_BACKOFF_MINUTES,
        TimeUnit.MINUTES,
      ).build()
}
