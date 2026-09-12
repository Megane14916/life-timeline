package com.megane14916.lifetimeline.worker

import androidx.lifecycle.LiveData
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Owns the persistent WorkManager contracts used by automatic collection and sync. */
class BackgroundWorkScheduler(
  private val workManager: WorkManager,
  private val collectionWorkerClass: Class<out ListenableWorker> = UsageCollectionWorker::class.java,
  private val syncWorkerClass: Class<out ListenableWorker> = AppSessionSyncWorker::class.java,
  private val photoCollectionWorkerClass: Class<out ListenableWorker> = PhotoCollectionWorker::class.java,
  private val photoSyncWorkerClass: Class<out ListenableWorker> = PhotoSyncWorker::class.java,
) {
  /** Ensures the periodic collection and pending-session sync work both exist. */
  fun ensureScheduled() {
    workManager.enqueueUniquePeriodicWork(
      AutomaticSyncPolicy.COLLECTION_WORK_NAME,
      ExistingPeriodicWorkPolicy.UPDATE,
      collectionWorkRequest(),
    )
    enqueueSync()
    ensurePhotoCollectionScheduled()
    enqueuePhotoSync()
  }

  /** Enqueues a sync without copying the endpoint into persistent WorkRequest input data. */
  fun enqueueSync() {
    workManager.enqueueUniqueWork(
      AutomaticSyncPolicy.SYNC_WORK_NAME,
      ExistingWorkPolicy.KEEP,
      syncWorkRequest(),
    )
  }

  /** Registers the independent periodic photo collector without replacing UsageStats work. */
  fun ensurePhotoCollectionScheduled() {
    workManager.enqueueUniquePeriodicWork(
      PhotoWorkPolicy.COLLECTION_WORK_NAME,
      ExistingPeriodicWorkPolicy.UPDATE,
      photoCollectionWorkRequest(),
    )
  }

  /** Adds one immediate photo scan while leaving the periodic schedule untouched. */
  fun enqueuePhotoCollectionNow() {
    workManager.enqueueUniqueWork(
      PhotoWorkPolicy.IMMEDIATE_COLLECTION_WORK_NAME,
      ExistingWorkPolicy.KEEP,
      immediatePhotoCollectionWorkRequest(),
    )
  }

  /** Continues a bounded scan after a page limit without replacing the periodic request. */
  fun enqueuePhotoCollectionContinuation() {
    workManager.enqueueUniqueWork(
      PhotoWorkPolicy.IMMEDIATE_COLLECTION_WORK_NAME,
      ExistingWorkPolicy.APPEND_OR_REPLACE,
      immediatePhotoCollectionWorkRequest(),
    )
  }

  fun cancelPhotoCollection() {
    workManager.cancelUniqueWork(PhotoWorkPolicy.COLLECTION_WORK_NAME)
    workManager.cancelUniqueWork(PhotoWorkPolicy.IMMEDIATE_COLLECTION_WORK_NAME)
  }

  /** Enqueues the photo-only uploader; its network and battery policy is independent. */
  fun enqueuePhotoSync() {
    workManager.enqueueUniqueWork(
      PhotoWorkPolicy.SYNC_WORK_NAME,
      ExistingWorkPolicy.KEEP,
      photoSyncWorkRequest(),
    )
  }

  fun enqueueAllSync() {
    enqueueSync()
    enqueuePhotoSync()
  }

  /** Returns the WorkManager records for the unique periodic collection work. */
  fun collectionWorkInfos(): ListenableFuture<List<WorkInfo>> =
    workManager.getWorkInfosForUniqueWork(AutomaticSyncPolicy.COLLECTION_WORK_NAME)

  /** Returns the WorkManager records for the unique sync work. */
  fun syncWorkInfos(): ListenableFuture<List<WorkInfo>> = workManager.getWorkInfosForUniqueWork(AutomaticSyncPolicy.SYNC_WORK_NAME)

  fun photoCollectionWorkInfos(): ListenableFuture<List<WorkInfo>> =
    workManager.getWorkInfosForUniqueWork(PhotoWorkPolicy.COLLECTION_WORK_NAME)

  fun photoSyncWorkInfos(): ListenableFuture<List<WorkInfo>> = workManager.getWorkInfosForUniqueWork(PhotoWorkPolicy.SYNC_WORK_NAME)

  fun photoCollectionWorkInfosLiveData(): LiveData<List<WorkInfo>> =
    workManager.getWorkInfosForUniqueWorkLiveData(PhotoWorkPolicy.COLLECTION_WORK_NAME)

  fun photoSyncWorkInfosLiveData(): LiveData<List<WorkInfo>> = workManager.getWorkInfosForUniqueWorkLiveData(PhotoWorkPolicy.SYNC_WORK_NAME)

  /** Reads the current periodic collection record without duplicating schedule state in Room. */
  suspend fun currentCollectionWorkInfo(): WorkInfo? = currentWorkInfo(collectionWorkInfos())

  /** Reads the current unique sync record without duplicating schedule state in Room. */
  suspend fun currentSyncWorkInfo(): WorkInfo? = currentWorkInfo(syncWorkInfos())

  suspend fun currentPhotoCollectionWorkInfo(): WorkInfo? = currentWorkInfo(photoCollectionWorkInfos())

  suspend fun currentPhotoSyncWorkInfo(): WorkInfo? = currentWorkInfo(photoSyncWorkInfos())

  private suspend fun currentWorkInfo(future: ListenableFuture<List<WorkInfo>>): WorkInfo? =
    withContext(Dispatchers.IO) {
      runCatching { future.get().firstOrNull() }.getOrNull()
    }

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

  internal fun photoCollectionWorkRequest(): PeriodicWorkRequest =
    PeriodicWorkRequest
      .Builder(
        photoCollectionWorkerClass,
        PhotoWorkPolicy.COLLECTION_INTERVAL_MINUTES,
        TimeUnit.MINUTES,
        PhotoWorkPolicy.COLLECTION_FLEX_MINUTES,
        TimeUnit.MINUTES,
      ).setConstraints(photoCollectionConstraints())
      .build()

  internal fun immediatePhotoCollectionWorkRequest(): OneTimeWorkRequest =
    OneTimeWorkRequest
      .Builder(photoCollectionWorkerClass)
      .setConstraints(photoCollectionConstraints())
      .build()

  internal fun photoSyncWorkRequest(): OneTimeWorkRequest =
    OneTimeWorkRequest
      .Builder(photoSyncWorkerClass)
      .setConstraints(
        Constraints
          .Builder()
          .setRequiredNetworkType(NetworkType.UNMETERED)
          .setRequiresBatteryNotLow(true)
          .setRequiresStorageNotLow(true)
          .build(),
      ).setBackoffCriteria(
        BackoffPolicy.EXPONENTIAL,
        PhotoWorkPolicy.SYNC_BACKOFF_MINUTES,
        TimeUnit.MINUTES,
      ).build()

  private fun photoCollectionConstraints(): Constraints =
    Constraints
      .Builder()
      .setRequiresBatteryNotLow(true)
      .setRequiresStorageNotLow(true)
      .build()
}
