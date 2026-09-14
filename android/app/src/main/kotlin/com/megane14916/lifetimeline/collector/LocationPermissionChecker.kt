package com.megane14916.lifetimeline.collector

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build

enum class LocationAccessState {
  DISABLED,
  FOREGROUND_PERMISSION_REQUIRED,
  BACKGROUND_PERMISSION_REQUIRED,
  LOCATION_SERVICES_OFF,
  APPROXIMATE,
  PRECISE,
}

fun interface LocationPermissionStateProvider {
  fun isGranted(permission: String): Boolean
}

/** Reads opt-in, OS permissions, and device location services afresh at each use. */
@SuppressLint("InlinedApi")
class LocationPermissionChecker(
  private val apiLevel: Int,
  private val permissionStateProvider: LocationPermissionStateProvider,
  private val locationServicesEnabledProvider: () -> Boolean,
) {
  fun currentAccess(collectionEnabled: Boolean): LocationAccessState =
    resolveAccess(
      apiLevel = apiLevel,
      collectionEnabled = collectionEnabled,
      coarseGranted = permissionStateProvider.isGranted(Manifest.permission.ACCESS_COARSE_LOCATION),
      fineGranted = permissionStateProvider.isGranted(Manifest.permission.ACCESS_FINE_LOCATION),
      backgroundGranted =
        apiLevel < Build.VERSION_CODES.Q ||
          permissionStateProvider.isGranted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
      locationServicesEnabled = locationServicesEnabledProvider(),
    )

  fun hasForegroundPermission(): Boolean =
    permissionStateProvider.isGranted(Manifest.permission.ACCESS_COARSE_LOCATION) ||
      permissionStateProvider.isGranted(Manifest.permission.ACCESS_FINE_LOCATION)

  fun hasBackgroundPermission(): Boolean =
    apiLevel < Build.VERSION_CODES.Q ||
      permissionStateProvider.isGranted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

  fun foregroundPermissions(): Array<String> = arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)

  fun backgroundPermission(): String = Manifest.permission.ACCESS_BACKGROUND_LOCATION

  companion object {
    fun from(context: Context): LocationPermissionChecker {
      val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
      return LocationPermissionChecker(
        apiLevel = Build.VERSION.SDK_INT,
        permissionStateProvider =
          LocationPermissionStateProvider { permission ->
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
          },
        locationServicesEnabledProvider = { locationManager.isLocationEnabledCompat() },
      )
    }

    fun resolveAccess(
      apiLevel: Int,
      collectionEnabled: Boolean,
      coarseGranted: Boolean,
      fineGranted: Boolean,
      backgroundGranted: Boolean,
      locationServicesEnabled: Boolean,
    ): LocationAccessState =
      when {
        !collectionEnabled -> LocationAccessState.DISABLED
        !coarseGranted && !fineGranted -> LocationAccessState.FOREGROUND_PERMISSION_REQUIRED
        apiLevel >= Build.VERSION_CODES.Q && !backgroundGranted -> LocationAccessState.BACKGROUND_PERMISSION_REQUIRED
        !locationServicesEnabled -> LocationAccessState.LOCATION_SERVICES_OFF
        fineGranted -> LocationAccessState.PRECISE
        else -> LocationAccessState.APPROXIMATE
      }
  }
}

@SuppressLint("InlinedApi")
private fun LocationManager.isLocationEnabledCompat(): Boolean =
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
    isLocationEnabled
  } else {
    isProviderEnabled(LocationManager.GPS_PROVIDER) || isProviderEnabled(LocationManager.NETWORK_PROVIDER)
  }
