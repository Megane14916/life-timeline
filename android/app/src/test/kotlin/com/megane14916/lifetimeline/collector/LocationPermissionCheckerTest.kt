package com.megane14916.lifetimeline.collector

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Test

class LocationPermissionCheckerTest {
  @Test
  fun optInIsRequiredEvenWhenEverySystemPermissionIsGranted() {
    assertEquals(
      LocationAccessState.DISABLED,
      resolve(apiLevel = 35, enabled = false, coarse = true, fine = true, background = true),
    )
  }

  @Test
  fun reportsForegroundAndBackgroundPermissionStagesSeparately() {
    assertEquals(
      LocationAccessState.FOREGROUND_PERMISSION_REQUIRED,
      resolve(apiLevel = 35, enabled = true, coarse = false, fine = false, background = false),
    )
    assertEquals(
      LocationAccessState.BACKGROUND_PERMISSION_REQUIRED,
      resolve(apiLevel = 35, enabled = true, coarse = true, fine = false, background = false),
    )
  }

  @Test
  fun distinguishesApproximatePreciseAndDisabledLocationServices() {
    assertEquals(
      LocationAccessState.APPROXIMATE,
      resolve(apiLevel = 35, enabled = true, coarse = true, fine = false, background = true),
    )
    assertEquals(
      LocationAccessState.PRECISE,
      resolve(apiLevel = 35, enabled = true, coarse = true, fine = true, background = true),
    )
    assertEquals(
      LocationAccessState.LOCATION_SERVICES_OFF,
      resolve(
        apiLevel = 35,
        enabled = true,
        coarse = true,
        fine = true,
        background = true,
        locationServices = false,
      ),
    )
  }

  @Test
  fun backgroundPermissionIsNotRequiredBeforeAndroid10() {
    assertEquals(
      LocationAccessState.PRECISE,
      resolve(apiLevel = 28, enabled = true, coarse = false, fine = true, background = false),
    )
    assertEquals(
      arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION).toList(),
      LocationPermissionChecker(28, LocationPermissionStateProvider { false }, { true })
        .foregroundPermissions()
        .toList(),
    )
  }

  private fun resolve(
    apiLevel: Int,
    enabled: Boolean,
    coarse: Boolean,
    fine: Boolean,
    background: Boolean,
    locationServices: Boolean = true,
  ): LocationAccessState =
    LocationPermissionChecker.resolveAccess(
      apiLevel = apiLevel,
      collectionEnabled = enabled,
      coarseGranted = coarse,
      fineGranted = fine,
      backgroundGranted = background,
      locationServicesEnabled = locationServices,
    )
}
