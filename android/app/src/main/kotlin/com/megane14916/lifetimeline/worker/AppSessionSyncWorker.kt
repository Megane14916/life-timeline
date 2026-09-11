package com.megane14916.lifetimeline.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Persistent worker class for the sync schedule.
 *
 * Sync behavior is implemented in P3-05. The class name is already a persisted WorkManager
 * contract, so it must remain stable while that behavior is added.
 */
class AppSessionSyncWorker(
  appContext: Context,
  workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
  override suspend fun doWork(): Result = Result.success()
}
