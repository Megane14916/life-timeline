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

  private fun scheduler(): BackgroundWorkScheduler =
    BackgroundWorkScheduler(
      WorkManager.getInstance(ApplicationProvider.getApplicationContext<Context>()),
    )
}
