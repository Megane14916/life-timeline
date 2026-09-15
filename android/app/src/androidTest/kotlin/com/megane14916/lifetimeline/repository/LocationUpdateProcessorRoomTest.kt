package com.megane14916.lifetimeline.repository

import android.Manifest
import android.content.Context
import android.location.Location
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megane14916.lifetimeline.collector.LocationPermissionChecker
import com.megane14916.lifetimeline.collector.LocationPermissionStateProvider
import com.megane14916.lifetimeline.collector.toLocationFix
import com.megane14916.lifetimeline.data.local.LifeTimelineDatabase
import com.megane14916.lifetimeline.data.preferences.AppSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocationUpdateProcessorRoomTest {
  @Test
  fun syntheticFusedFixSurvivesActivityAbsenceAndReachesRoom() =
    runBlocking {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val database =
        Room
          .inMemoryDatabaseBuilder(context, LifeTimelineDatabase::class.java)
          .allowMainThreadQueries()
          .addCallback(LifeTimelineDatabase.PHOTO_INTEGRITY_CALLBACK)
          .build()
      try {
        val startedAtMs = 1_800_000_000_000L
        val processor =
          LocationUpdateProcessor(
            settingsProvider = {
              AppSettings(
                deviceId = DEVICE_ID,
                pcBaseUrl = null,
                lastCollectionAtMs = null,
                lastSyncAtMs = null,
                locationCollectionEnabled = true,
                locationCollectionStartedAtMs = startedAtMs,
              )
            },
            permissionChecker = grantedPermissionChecker(),
            repository = LocationCollectionRepository(RoomLocationPointStore(database)),
            deviceIdProvider = { DEVICE_ID },
            nowMs = { startedAtMs + 10_000 },
          )
        val syntheticFix =
          Location("synthetic-fused")
            .apply {
              time = startedAtMs + 5_000
              latitude = 35.0
              longitude = 139.0
              accuracy = 12.0f
              elapsedRealtimeNanos = 123_456_789L
            }.toLocationFix()

        val result = processor.persistBatch(listOf(syntheticFix))
        val stored = database.locationPointDao().getPendingBatch(10)

        assertEquals(1, result?.insertedCount)
        assertEquals(1, stored.size)
        assertEquals(syntheticFix.recordedAtMs, stored.single().recordedAtMs)
        assertEquals(syntheticFix.latitude, stored.single().latitude, 0.0)
        assertEquals(syntheticFix.longitude, stored.single().longitude, 0.0)
      } finally {
        database.close()
      }
    }

  private fun grantedPermissionChecker() =
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

  private companion object {
    const val DEVICE_ID = "01K00000000000000000000001"
  }
}
