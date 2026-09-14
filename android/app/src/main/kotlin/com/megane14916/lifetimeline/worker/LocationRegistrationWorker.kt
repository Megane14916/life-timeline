package com.megane14916.lifetimeline.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.megane14916.lifetimeline.collector.LocationAccessState
import com.megane14916.lifetimeline.data.WorkerDependencies
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Idempotently restores Fused Location registration after opt-in, boot, update, or watchdog. */
class LocationRegistrationWorker(
  appContext: Context,
  workerParams: WorkerParameters,
  private val dependencies: WorkerDependencies,
) : CoroutineWorker(appContext, workerParams) {
  override suspend fun doWork(): Result {
    val coordinator = dependencies.backgroundExecutionCoordinatorFactory(applicationContext)
    val lease = coordinator.acquire(LocationWorkPolicy.REGISTRATION_LEASE_KEY) ?: return Result.success()
    try {
      val enabled = dependencies.locationCollectionEnabledProvider()
      val permissionChecker = dependencies.locationPermissionCheckerFactory(applicationContext)
      val access = permissionChecker.currentAccess(enabled)
      when (access) {
        LocationAccessState.DISABLED -> {
          removeRegistrationBestEffort()
          coordinator.recordSuccess(lease, "disabled")
        }

        LocationAccessState.FOREGROUND_PERMISSION_REQUIRED,
        LocationAccessState.BACKGROUND_PERMISSION_REQUIRED,
        -> {
          removeRegistrationBestEffort()
          coordinator.recordSuccess(lease, "permission_required")
        }

        LocationAccessState.LOCATION_SERVICES_OFF -> {
          removeRegistrationBestEffort()
          coordinator.recordSuccess(lease, "location_services_off")
        }

        LocationAccessState.APPROXIMATE,
        LocationAccessState.PRECISE,
        -> {
          val registrationClient = dependencies.locationRegistrationClientFactory(applicationContext)
          registrationClient.register()
          val latestAccess = permissionChecker.currentAccess(dependencies.locationCollectionEnabledProvider())
          if (latestAccess == LocationAccessState.APPROXIMATE || latestAccess == LocationAccessState.PRECISE) {
            coordinator.recordSuccess(lease, "registered")
          } else {
            runCatching { registrationClient.unregister() }
            coordinator.recordSuccess(lease, latestAccess.toResultCode())
          }
        }
      }
      return Result.success()
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (_: SecurityException) {
      removeRegistrationBestEffort()
      coordinator.recordSuccess(lease, "permission_required")
      return Result.success()
    } catch (_: Throwable) {
      coordinator.recordFailure(lease, "retry", "location_registration")
      return Result.retry()
    } finally {
      withContext(NonCancellable) { coordinator.release(lease) }
    }
  }

  private suspend fun removeRegistrationBestEffort() {
    runCatching { dependencies.locationRegistrationClientFactory(applicationContext).unregister() }
  }

  private fun LocationAccessState.toResultCode(): String =
    when (this) {
      LocationAccessState.DISABLED -> "disabled"

      LocationAccessState.FOREGROUND_PERMISSION_REQUIRED,
      LocationAccessState.BACKGROUND_PERMISSION_REQUIRED,
      -> "permission_required"

      LocationAccessState.LOCATION_SERVICES_OFF -> "location_services_off"

      LocationAccessState.APPROXIMATE,
      LocationAccessState.PRECISE,
      -> "registered"
    }
}
