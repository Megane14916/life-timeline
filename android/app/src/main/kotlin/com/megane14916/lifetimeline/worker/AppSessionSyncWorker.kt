package com.megane14916.lifetimeline.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.megane14916.lifetimeline.data.WorkerDependencies
import com.megane14916.lifetimeline.repository.SyncFailure
import com.megane14916.lifetimeline.repository.SyncFailureKind
import com.megane14916.lifetimeline.repository.SyncRunStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Persistent worker class for the sync schedule.
 *
 * Synchronizes pending sessions in bounded batches when the network and battery constraints allow.
 *
 * The class name is a persisted WorkManager contract and must remain stable.
 */
class AppSessionSyncWorker(
  appContext: Context,
  workerParams: WorkerParameters,
  private val dependencies: WorkerDependencies,
) : CoroutineWorker(appContext, workerParams) {
  override suspend fun doWork(): Result {
    val executionCoordinator = dependencies.backgroundExecutionCoordinatorFactory(applicationContext)
    val lease = executionCoordinator.acquire(AutomaticSyncPolicy.SYNC_LEASE_KEY) ?: return Result.success()

    return try {
      val endpoint = dependencies.pcBaseUrlProvider()
      if (endpoint.isNullOrBlank()) {
        executionCoordinator.recordSuccess(lease, "configuration_required")
        return Result.success()
      }

      val repository = dependencies.syncRepositoryFactory(applicationContext, endpoint)
      val syncResult =
        repository.syncAll(
          maxBatches = AutomaticSyncPolicy.MAX_BATCHES_PER_RUN,
          deadlineNanos = deadlineNanos(),
          onBatchCompleted = { executionCoordinator.heartbeat(lease) },
        )
      handleResult(executionCoordinator, lease, syncResult)
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

  private suspend fun handleResult(
    executionCoordinator: com.megane14916.lifetimeline.repository.BackgroundExecutionCoordinator,
    lease: com.megane14916.lifetimeline.repository.BackgroundLease,
    result: com.megane14916.lifetimeline.repository.SyncResult,
  ): Result {
    when (result.status) {
      SyncRunStatus.SUCCESS,
      SyncRunStatus.NO_PENDING,
      -> {
        executionCoordinator.recordSuccess(lease, result.status.resultCode())
        dependencies.syncSuccessRecorder(System.currentTimeMillis())
        return Result.success()
      }

      SyncRunStatus.RETRY_LIMIT_REACHED -> {
        executionCoordinator.recordFailure(lease, "retry", "budget")
        return Result.retry()
      }

      SyncRunStatus.LEASE_LOST -> {
        return Result.success()
      }

      SyncRunStatus.PARTIAL_SUCCESS,
      SyncRunStatus.FAILED,
      -> {
        val failure = checkNotNull(result.failure)
        val shouldRetry = failure.shouldRetry()
        executionCoordinator.recordFailure(
          lease,
          result = if (shouldRetry) "retry" else "failure",
          errorKind = failure.errorKind(),
        )
        return if (shouldRetry) Result.retry() else Result.failure()
      }
    }
  }

  private fun deadlineNanos(): Long {
    val now = System.nanoTime()
    val duration = TimeUnit.MINUTES.toNanos(AutomaticSyncPolicy.MAX_RUN_MINUTES)
    return if (Long.MAX_VALUE - now < duration) Long.MAX_VALUE else now + duration
  }

  private fun SyncRunStatus.resultCode(): String =
    when (this) {
      SyncRunStatus.SUCCESS -> "success"
      SyncRunStatus.NO_PENDING -> "no_pending"
      else -> error("Only successful sync statuses can be recorded as success.")
    }

  private fun SyncFailure.shouldRetry(): Boolean =
    when (kind) {
      SyncFailureKind.NETWORK -> true
      SyncFailureKind.SERVER -> httpStatus == 408 || httpStatus == 429 || (httpStatus != null && httpStatus >= 500)
      SyncFailureKind.PROTOCOL -> false
    }

  private fun SyncFailure.errorKind(): String =
    when (kind) {
      SyncFailureKind.NETWORK -> "network"
      SyncFailureKind.SERVER -> "server"
      SyncFailureKind.PROTOCOL -> "protocol"
    }
}
