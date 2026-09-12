package com.megane14916.lifetimeline.worker

import android.content.Context
import android.os.CancellationSignal
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.megane14916.lifetimeline.collector.PhotoAccessState
import com.megane14916.lifetimeline.data.WorkerDependencies
import com.megane14916.lifetimeline.repository.PhotoCollectionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext

/** Collects a bounded page of new photos under a lease dedicated to the photo source. */
class PhotoCollectionWorker(
  appContext: Context,
  workerParams: WorkerParameters,
  private val dependencies: WorkerDependencies,
) : CoroutineWorker(appContext, workerParams) {
  override suspend fun doWork(): Result {
    val executionCoordinator = dependencies.backgroundExecutionCoordinatorFactory(applicationContext)
    val lease = executionCoordinator.acquire(PhotoWorkPolicy.COLLECTION_LEASE_KEY) ?: return Result.success()

    return try {
      if (!dependencies.photoCollectionEnabledProvider()) {
        executionCoordinator.recordSuccess(lease, "disabled")
        return Result.success()
      }
      val startedAt = dependencies.photoCollectionStartedAtProvider()
      if (startedAt == null) {
        executionCoordinator.recordSuccess(lease, "configuration_required")
        return Result.success()
      }
      val access = dependencies.photoAccessCheckerFactory(applicationContext).currentAccess()
      if (access == PhotoAccessState.DENIED) {
        executionCoordinator.recordSuccess(lease, "permission_required")
        return Result.success()
      }

      val cancellationSignal = CancellationSignal()
      val cancellationHandle =
        currentCoroutineContext().job.invokeOnCompletion { cause ->
          if (cause is CancellationException || isStopped) cancellationSignal.cancel()
        }
      try {
        val result =
          dependencies.photoCollectionRepositoryFactory(applicationContext).collect(
            access = access,
            collectionStartedAtMs = startedAt,
            cancellationSignal = cancellationSignal,
          )
        when (result.status) {
          PhotoCollectionStatus.COMPLETED -> {
            executionCoordinator.recordSuccess(lease, "success")
            if (result.hasMore || dependencies.pendingPhotoThumbnailCountProvider() > 0) {
              dependencies.photoCollectionTrigger()
            }
            if (!dependencies.pcBaseUrlProvider().isNullOrBlank() && dependencies.pendingPhotoSyncableCountProvider() > 0) {
              dependencies.photoSyncTrigger()
            }
          }

          PhotoCollectionStatus.PHOTO_ACCESS_REQUIRED -> {
            executionCoordinator.recordSuccess(lease, "permission_required")
          }

          PhotoCollectionStatus.ACCESS_REVOKED -> {
            executionCoordinator.recordFailure(lease, "failure", "permission")
          }
        }
        Result.success()
      } finally {
        cancellationHandle.dispose()
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
}
