package com.megane14916.lifetimeline.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.megane14916.lifetimeline.data.WorkerDependencies
import com.megane14916.lifetimeline.data.remote.LocationSyncFailureKind
import com.megane14916.lifetimeline.repository.LocationSyncRunStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Uploads pending Location points under their own lease and bounded WorkManager execution window. */
class LocationSyncWorker(
  appContext: Context,
  workerParams: WorkerParameters,
  private val dependencies: WorkerDependencies,
) : CoroutineWorker(appContext, workerParams) {
  override suspend fun doWork(): Result {
    val coordinator = dependencies.backgroundExecutionCoordinatorFactory(applicationContext)
    val lease = coordinator.acquire(LocationWorkPolicy.SYNC_LEASE_KEY) ?: return Result.success()
    try {
      val endpoint = dependencies.pcBaseUrlProvider()
      if (endpoint.isNullOrBlank()) {
        coordinator.recordSuccess(lease, "configuration_required")
        return Result.success()
      }

      val result =
        dependencies.locationSyncRepositoryFactory(applicationContext, endpoint).syncPending(
          maxBatches = LocationWorkPolicy.MAX_BATCHES_PER_RUN,
          deadlineNanos = deadlineNanos(),
          onBatchCompleted = { coordinator.heartbeat(lease) },
        )
      return when (result.status) {
        LocationSyncRunStatus.SUCCESS,
        LocationSyncRunStatus.NO_PENDING,
        -> {
          coordinator.recordSuccess(lease, result.status.resultCode())
          Result.success()
        }

        LocationSyncRunStatus.RETRY_LIMIT_REACHED -> {
          coordinator.recordFailure(lease, "retry", "budget")
          Result.retry()
        }

        LocationSyncRunStatus.LEASE_LOST -> {
          Result.success()
        }

        LocationSyncRunStatus.FAILED -> {
          val failure = checkNotNull(result.failure)
          coordinator.recordFailure(
            lease,
            result = if (failure.retryable) "retry" else "failure",
            errorKind = failure.kind.errorKind(),
          )
          if (failure.retryable) Result.retry() else Result.failure()
        }
      }
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (_: Throwable) {
      coordinator.recordFailure(lease, "retry", "unexpected")
      return Result.retry()
    } finally {
      withContext(NonCancellable) { coordinator.release(lease) }
    }
  }

  private fun deadlineNanos(): Long {
    val now = System.nanoTime()
    val duration = TimeUnit.MINUTES.toNanos(LocationWorkPolicy.MAX_RUN_MINUTES)
    return if (Long.MAX_VALUE - now < duration) Long.MAX_VALUE else now + duration
  }

  private fun LocationSyncRunStatus.resultCode(): String =
    when (this) {
      LocationSyncRunStatus.SUCCESS -> "success"
      LocationSyncRunStatus.NO_PENDING -> "no_pending"
      else -> error("Only successful Location sync statuses can be recorded as success.")
    }

  private fun LocationSyncFailureKind.errorKind(): String =
    when (this) {
      LocationSyncFailureKind.NETWORK -> "network"
      LocationSyncFailureKind.SERVER -> "server"
      LocationSyncFailureKind.PROTOCOL -> "protocol"
    }
}
