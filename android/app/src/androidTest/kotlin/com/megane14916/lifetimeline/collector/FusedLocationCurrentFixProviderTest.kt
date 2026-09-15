package com.megane14916.lifetimeline.collector

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.Priority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FusedLocationCurrentFixProviderTest {
  @Test
  fun requestsOnlyFreshBalancedPowerFixWithBoundedDuration() =
    kotlinx.coroutines.runBlocking {
      var capturedRequest: CurrentLocationRequest? = null
      val provider =
        FusedLocationCurrentFixProvider(
          permissionChecker = permissionChecker(granted = true),
          adapter =
            FusedCurrentLocationAdapter { request ->
              capturedRequest = request
              null
            },
        )

      assertNull(provider.getCurrentLocation())
      val request = checkNotNull(capturedRequest)
      assertEquals(0L, request.maxUpdateAgeMillis)
      assertEquals(Priority.PRIORITY_BALANCED_POWER_ACCURACY, request.priority)
      assertEquals(30_000L, request.durationMillis)
    }

  @Test
  fun doesNotCallPlayServicesWhenLocationAccessIsUnavailable() =
    kotlinx.coroutines.runBlocking {
      var requested = false
      val provider =
        FusedLocationCurrentFixProvider(
          permissionChecker = permissionChecker(granted = false),
          adapter =
            FusedCurrentLocationAdapter {
              requested = true
              null
            },
        )

      val failure = runCatching { provider.getCurrentLocation() }.exceptionOrNull()

      assertEquals(IllegalStateException::class.java, failure?.javaClass)
      assertFalse(requested)
    }

  private fun permissionChecker(granted: Boolean) =
    LocationPermissionChecker(
      apiLevel = 35,
      permissionStateProvider =
        LocationPermissionStateProvider { permission ->
          granted && permission in LOCATION_PERMISSIONS
        },
      locationServicesEnabledProvider = { granted },
    )

  private companion object {
    val LOCATION_PERMISSIONS =
      setOf(
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
      )
  }
}
