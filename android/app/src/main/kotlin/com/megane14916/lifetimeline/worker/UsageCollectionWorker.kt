package com.megane14916.lifetimeline.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.megane14916.lifetimeline.data.WorkerDependencies
import com.megane14916.lifetimeline.repository.CollectionRunStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Persistent worker class for the collection schedule.
 *
 * Collects UsageStats into Room without requiring network connectivity.
 *
 * The class name is a persisted WorkManager contract and must remain stable.
 */
class UsageCollectionWorker(
  appContext: Context,
  workerParams: WorkerParameters,
  private val dependencies: WorkerDependencies,
) : CoroutineWorker(appContext, workerParams) {
  override suspend fun doWork(): Result {
    val executionCoordinator = dependencies.backgroundExecutionCoordinatorFactory(applicationContext)
    val lease = executionCoordinator.acquire(AutomaticSyncPolicy.COLLECTION_LEASE_KEY) ?: return Result.success()

    return try {
      val collection = dependencies.collectionCoordinatorFactory(applicationContext).collect()
      when (collection.status) {
        CollectionRunStatus.SUCCESS,
        CollectionRunStatus.NO_DATA,
        CollectionRunStatus.PERMISSION_DENIED,
        -> {
          executionCoordinator.recordSuccess(lease, collection.status.resultCode())
          triggerSyncIfConfigured()
          Result.success()
        }

        CollectionRunStatus.UNAVAILABLE -> {
          executionCoordinator.recordFailure(lease, "retry", "unavailable")
          triggerSyncIfConfigured()
          Result.retry()
        }
      }
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (_: Throwable) {
      executionCoordinator.recordFailure(lease, "retry", "unexpected")
      Result.retry()
    } finally {
      withContext(NonCancellable) {
        executionCoordinator.release(lease)
      }
    }
  }

  private suspend fun triggerSyncIfConfigured() {
    if (!dependencies.pcBaseUrlProvider().isNullOrBlank()) {
      dependencies.syncTrigger()
    }
  }

  private fun CollectionRunStatus.resultCode(): String =
    when (this) {
      CollectionRunStatus.SUCCESS -> "success"
      CollectionRunStatus.NO_DATA -> "no_data"
      CollectionRunStatus.PERMISSION_DENIED -> "permission_denied"
      CollectionRunStatus.UNAVAILABLE -> "unavailable"
    }
}
