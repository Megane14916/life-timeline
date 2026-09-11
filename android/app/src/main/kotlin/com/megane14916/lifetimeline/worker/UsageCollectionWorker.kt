package com.megane14916.lifetimeline.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Persistent worker class for the collection schedule.
 *
 * Collection behavior is implemented in P3-04. The class name is already a persisted
 * WorkManager contract, so it must remain stable while that behavior is added.
 */
class UsageCollectionWorker(
  appContext: Context,
  workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
  override suspend fun doWork(): Result = Result.success()
}
