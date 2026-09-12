package com.megane14916.lifetimeline.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.megane14916.lifetimeline.data.WorkerDependencies
import com.megane14916.lifetimeline.data.remote.PhotoSyncFailureKind
import com.megane14916.lifetimeline.repository.PhotoSyncRunStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Uploads only generated WebP thumbnails under an independent photo sync lease. */
class PhotoSyncWorker(
  appContext: Context,
  workerParams: WorkerParameters,
  private val dependencies: WorkerDependencies,
) : CoroutineWorker(appContext, workerParams) {
  override suspend fun doWork(): Result {
    val executionCoordinator = dependencies.backgroundExecutionCoordinatorFactory(applicationContext)
    val lease = executionCoordinator.acquire(PhotoWorkPolicy.SYNC_LEASE_KEY) ?: return Result.success()

    return try {
      val endpoint = dependencies.pcBaseUrlProvider()
      if (endpoint.isNullOrBlank()) {
        executionCoordinator.recordSuccess(lease, "configuration_required")
        return Result.success()
      }
      val repository = dependencies.photoSyncRepositoryFactory(applicationContext, endpoint)
      val result =
        repository.syncPending(
          maxBatches = PhotoWorkPolicy.MAX_BATCHES_PER_RUN,
          deadlineNanos = deadlineNanos(),
          onBatchCompleted = { executionCoordinator.heartbeat(lease) },
        )
      when (result.status) {
        PhotoSyncRunStatus.SUCCESS,
        PhotoSyncRunStatus.NO_PENDING,
        -> {
          executionCoordinator.recordSuccess(lease, result.status.resultCode())
          Result.success()
        }

        PhotoSyncRunStatus.RETRY_LIMIT_REACHED -> {
          executionCoordinator.recordFailure(lease, "retry", "budget")
          Result.retry()
        }

        PhotoSyncRunStatus.LEASE_LOST -> {
          Result.success()
        }

        PhotoSyncRunStatus.FAILED -> {
          val failure = checkNotNull(result.failure)
          val kind = failure.kind.errorKind()
          executionCoordinator.recordFailure(
            lease,
            result = if (failure.retryable) "retry" else "failure",
            errorKind = kind,
          )
          if (failure.retryable) Result.retry() else Result.failure()
        }
      }
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (_: Throwable) {
      executionCoordinator.recordFailure(lease, "failure", "unexpected")
      Result.failure()
    } finally {
      withContext(NonCancellable) {
        executionCoordinator.release(lease)
      }
    }
  }

  private fun deadlineNanos(): Long {
    val now = System.nanoTime()
    val duration = TimeUnit.MINUTES.toNanos(PhotoWorkPolicy.MAX_RUN_MINUTES)
    return if (Long.MAX_VALUE - now < duration) Long.MAX_VALUE else now + duration
  }

  private fun PhotoSyncRunStatus.resultCode(): String =
    when (this) {
      PhotoSyncRunStatus.SUCCESS -> "success"
      PhotoSyncRunStatus.NO_PENDING -> "no_pending"
      else -> error("Only successful photo sync statuses can be recorded as success.")
    }

  private fun PhotoSyncFailureKind.errorKind(): String =
    when (this) {
      PhotoSyncFailureKind.NETWORK -> "network"
      PhotoSyncFailureKind.SERVER -> "server"
      PhotoSyncFailureKind.PROTOCOL -> "protocol"
    }
}
