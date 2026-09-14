package com.megane14916.lifetimeline.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import androidx.work.WorkManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BackgroundWorkSchedulerTest {
  @Test
  fun collectionRequestUsesStablePeriodicCadence() {
    val scheduler = scheduler()
    val request = scheduler.collectionWorkRequest()

    assertEquals(UsageCollectionWorker::class.java.name, request.workSpec.workerClassName)
    assertEquals(
      TimeUnit.MINUTES.toMillis(AutomaticSyncPolicy.COLLECTION_INTERVAL_MINUTES),
      request.workSpec.intervalDuration,
    )
    assertEquals(
      TimeUnit.MINUTES.toMillis(AutomaticSyncPolicy.COLLECTION_FLEX_MINUTES),
      request.workSpec.flexDuration,
    )
  }

  @Test
  fun syncRequestUsesRequiredConstraintsAndDoesNotPersistEndpoint() {
    val scheduler = scheduler()
    val request = scheduler.syncWorkRequest()
    val workSpec = request.workSpec

    assertEquals(AppSessionSyncWorker::class.java.name, workSpec.workerClassName)
    assertEquals(NetworkType.CONNECTED, workSpec.constraints.requiredNetworkType)
    assertTrue(workSpec.constraints.requiresBatteryNotLow())
    assertEquals(BackoffPolicy.EXPONENTIAL, workSpec.backoffPolicy)
    assertEquals(
      TimeUnit.MINUTES.toMillis(AutomaticSyncPolicy.SYNC_BACKOFF_MINUTES),
      workSpec.backoffDelayDuration,
    )
    assertTrue(workSpec.input.keyValueMap.isEmpty())
  }

  @Test
  fun locationRegistrationIsUnconstrainedAndWatchdogUsesDocumentedCadence() {
    val scheduler = scheduler()
    val registration = scheduler.locationRegistrationWorkRequest().workSpec
    val watchdog = scheduler.locationRegistrationWatchdogWorkRequest().workSpec

    assertEquals(LocationRegistrationWorker::class.java.name, registration.workerClassName)
    assertEquals(NetworkType.NOT_REQUIRED, registration.constraints.requiredNetworkType)
    assertEquals(false, registration.constraints.requiresBatteryNotLow())
    assertEquals(LocationRegistrationWorker::class.java.name, watchdog.workerClassName)
    assertEquals(TimeUnit.HOURS.toMillis(12), watchdog.intervalDuration)
    assertEquals(TimeUnit.HOURS.toMillis(1), watchdog.flexDuration)
  }

  @Test
  fun locationSyncHasIndependentNameConnectedBatteryConstraintAndFifteenMinuteBackoff() {
    val scheduler = scheduler()
    val workSpec = scheduler.locationSyncWorkRequest().workSpec

    assertEquals(LocationSyncWorker::class.java.name, workSpec.workerClassName)
    assertEquals(LocationWorkPolicy.SYNC_WORK_NAME, "life_timeline_location_sync_v1")
    assertEquals(NetworkType.CONNECTED, workSpec.constraints.requiredNetworkType)
    assertTrue(workSpec.constraints.requiresBatteryNotLow())
    assertEquals(BackoffPolicy.EXPONENTIAL, workSpec.backoffPolicy)
    assertEquals(TimeUnit.MINUTES.toMillis(15), workSpec.backoffDelayDuration)
    assertTrue(workSpec.input.keyValueMap.isEmpty())
    assertTrue(LocationWorkPolicy.SYNC_WORK_NAME != AutomaticSyncPolicy.SYNC_WORK_NAME)
    assertTrue(LocationWorkPolicy.SYNC_WORK_NAME != PhotoWorkPolicy.SYNC_WORK_NAME)
  }

  private fun scheduler(): BackgroundWorkScheduler =
    BackgroundWorkScheduler(
      WorkManager.getInstance(ApplicationProvider.getApplicationContext<Context>()),
    )
}
