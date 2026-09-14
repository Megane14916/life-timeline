package com.megane14916.lifetimeline.repository

import android.Manifest
import com.megane14916.lifetimeline.collector.LocationPermissionChecker
import com.megane14916.lifetimeline.collector.LocationPermissionStateProvider
import com.megane14916.lifetimeline.data.local.LocationPointEntity
import com.megane14916.lifetimeline.data.preferences.AppSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocationUpdateProcessorTest {
  @Test
  fun doesNotPersistWhenUserHasOptedOut() =
    runBlocking {
      var persistCalls = 0
      val processor =
        LocationUpdateProcessor(
          settingsProvider = { settings(enabled = false) },
          permissionChecker = permissionChecker(),
          repository = LocationCollectionRepository(CountingStore { persistCalls += 1 }),
          deviceIdProvider = { DEVICE_ID },
        )

      assertNull(processor.persistBatch(listOf(fix())))
      assertEquals(0, persistCalls)
    }

  @Test
  fun rechecksOptInBeforeWritingAndPersistsWhenStillEnabled() =
    runBlocking {
      var settingsReads = 0
      var inserts = 0
      val store = CountingStore { inserts += 1 }
      val processor =
        LocationUpdateProcessor(
          settingsProvider = {
            settingsReads += 1
            settings(enabled = settingsReads == 1, startedAtMs = 1_800_000_000_000L)
          },
          permissionChecker = permissionChecker(),
          repository = LocationCollectionRepository(store),
          deviceIdProvider = { DEVICE_ID },
          nowMs = { 1_800_000_010_000L },
        )

      assertNull(processor.persistBatch(listOf(fix())))
      assertEquals(0, inserts)
    }

  @Test
  fun persistsSyntheticFixToTheLocationStoreWhenOptedIn() =
    runBlocking {
      val store = CountingStore {}
      val processor =
        LocationUpdateProcessor(
          settingsProvider = { settings(enabled = true, startedAtMs = 1_800_000_000_000L) },
          permissionChecker = permissionChecker(),
          repository = LocationCollectionRepository(store),
          deviceIdProvider = { DEVICE_ID },
          nowMs = { 1_800_000_010_000L },
        )

      val result = processor.persistBatch(listOf(fix()))

      assertEquals(1, result?.insertedCount)
      assertEquals(1, store.points.size)
      assertEquals(35.0, store.points.single().latitude, 0.0)
      assertEquals(139.0, store.points.single().longitude, 0.0)
    }

  private fun settings(
    enabled: Boolean,
    startedAtMs: Long? = if (enabled) 1_800_000_000_000L else null,
  ) = AppSettings(
    deviceId = DEVICE_ID,
    pcBaseUrl = null,
    lastCollectionAtMs = null,
    lastSyncAtMs = null,
    locationCollectionEnabled = enabled,
    locationCollectionStartedAtMs = startedAtMs,
  )

  private fun permissionChecker() =
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
      locationServicesEnabledProvider = { true },
    )

  private fun fix() =
    LocationFix(
      recordedAtMs = 1_800_000_005_000L,
      latitude = 35.0,
      longitude = 139.0,
      accuracyM = 12.0,
      elapsedRealtimeNanos = 123_456_789L,
    )

  private class CountingStore(
    private val onInsert: () -> Unit,
  ) : LocationPointStore {
    val points = mutableListOf<LocationPointEntity>()

    override suspend fun insertBatchIfAbsent(points: List<LocationPointEntity>): List<LocationPointInsertResult> {
      onInsert()
      this.points += points
      return points.map { LocationPointInsertResult(inserted = true) }
    }

    override suspend fun getPendingBatch(limit: Int): List<LocationPointEntity> = points.take(limit)

    override suspend fun countPending(): Int = points.size

    override suspend fun markPendingAsSynced(
      ids: List<String>,
      syncedAtMs: Long,
    ): Int = 0

    override suspend fun deleteSyncedBatch(limit: Int): Int = 0
  }

  private companion object {
    const val DEVICE_ID = "01K00000000000000000000001"
  }
}
