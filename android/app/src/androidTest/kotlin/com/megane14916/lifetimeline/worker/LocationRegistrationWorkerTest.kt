package com.megane14916.lifetimeline.worker

import android.Manifest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.TestListenableWorkerBuilder
import com.megane14916.lifetimeline.LifeTimelineApplication
import com.megane14916.lifetimeline.collector.LocationPermissionChecker
import com.megane14916.lifetimeline.collector.LocationPermissionStateProvider
import com.megane14916.lifetimeline.collector.LocationRegistrationClient
import com.megane14916.lifetimeline.data.WorkerDependencies
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocationRegistrationWorkerTest {
  @Test
  fun registersOnlyAfterPermissionCheckerReportsAllowedState() =
    runBlocking {
      var registrations = 0
      var removals = 0
      val context = ApplicationProvider.getApplicationContext<Context>()
      val dependencies =
        workerDependencies(
          enabled = true,
          locationServicesEnabled = true,
          client =
            object : LocationRegistrationClient {
              override suspend fun register() {
                registrations += 1
              }

              override suspend fun unregister() {
                removals += 1
              }
            },
        )
      val worker =
        TestListenableWorkerBuilder
          .from(context, LocationRegistrationWorker::class.java)
          .setWorkerFactory(LifeTimelineWorkerFactory(dependencies))
          .build(LocationRegistrationWorker::class.java)

      assertEquals(
        androidx.work.ListenableWorker.Result
          .success(),
        worker.doWork(),
      )
      assertEquals(1, registrations)
      assertEquals(0, removals)
    }

  @Test
  fun removesRegistrationWithoutRetryWhenOptInIsOff() =
    runBlocking {
      var registrations = 0
      var removals = 0
      val context = ApplicationProvider.getApplicationContext<Context>()
      val dependencies =
        workerDependencies(
          enabled = false,
          locationServicesEnabled = true,
          client =
            object : LocationRegistrationClient {
              override suspend fun register() {
                registrations += 1
              }

              override suspend fun unregister() {
                removals += 1
              }
            },
        )
      val worker =
        TestListenableWorkerBuilder
          .from(context, LocationRegistrationWorker::class.java)
          .setWorkerFactory(LifeTimelineWorkerFactory(dependencies))
          .build(LocationRegistrationWorker::class.java)

      assertEquals(
        androidx.work.ListenableWorker.Result
          .success(),
        worker.doWork(),
      )
      assertEquals(0, registrations)
      assertEquals(1, removals)
    }

  private fun workerDependencies(
    enabled: Boolean,
    locationServicesEnabled: Boolean,
    client: LocationRegistrationClient,
  ): WorkerDependencies {
    val application = ApplicationProvider.getApplicationContext<LifeTimelineApplication>()
    return WorkerDependencies(
      collectionCoordinatorFactory = { error("Not used by Location registration.") },
      syncRepositoryFactory = { _, _ -> error("Not used by Location registration.") },
      backgroundExecutionCoordinatorFactory = { application.appContainer.backgroundExecutionCoordinator },
      locationPermissionCheckerFactory = {
        LocationPermissionChecker(
          apiLevel = 35,
          permissionStateProvider =
            LocationPermissionStateProvider { permission ->
              permission in
                setOf(
                  Manifest.permission.ACCESS_COARSE_LOCATION,
                  Manifest.permission.ACCESS_FINE_LOCATION,
                  Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                )
            },
          locationServicesEnabledProvider = { locationServicesEnabled },
        )
      },
      locationRegistrationClientFactory = { client },
      locationCollectionEnabledProvider = { enabled },
    )
  }
}
